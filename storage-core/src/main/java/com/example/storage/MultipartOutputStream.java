package com.example.storage;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OutputStream que transforma escrita sequencial em upload multipart.
 *
 * <p>Os bytes vão para um buffer do tamanho de uma parte; quando enche, a
 * parte é despachada em background e a escrita continua em outro buffer.
 * O número de buffers é limitado ({@code maxInFlight + 1}), então se o
 * storage ficar lento a thread que lê o banco bloqueia (backpressure) em
 * vez de acumular o relatório inteiro em memória. Com {@code maxInFlight = 0}
 * ({@link MultipartConfig#sequential}), cada parte sobe na própria thread que
 * escreve, reaproveitando um único buffer.</p>
 *
 * <p><b>Contrato de finalização:</b> o objeto só é gravado com
 * {@link #commit()}. Um {@link #close()} sem commit ABORTA o upload. Assim,
 * um erro no meio da leitura do banco dentro de um try-with-resources nunca
 * publica um relatório truncado. Para entregar o stream a quem o fecha por
 * conta própria ({@code GZIPOutputStream}, {@code ObjectMapper.writeValue}),
 * use {@link #nonClosing()}.</p>
 *
 * <p>Com {@link MultipartConfig#maxObjectBytes()}, a escrita que passaria do
 * limite aborta o upload e lança {@link ObjectTooLargeException}.</p>
 *
 * <p>Falhas do storage chegam como {@link StorageException} (runtime), com o tipo
 * original, em {@code write} ou em {@link #commit()}; {@link IOException} fica para
 * interrupção, stream já finalizado e limite de partes.</p>
 *
 * <p>Não é thread-safe para escrita: um único produtor por stream.</p>
 */
public final class MultipartOutputStream extends OutputStream {

    public static final int MAX_PARTS = 10_000;

    private static final System.Logger LOG = System.getLogger(MultipartOutputStream.class.getName());

    private enum State { OPEN, COMMITTED, ABORTED }

    private final MultipartSession session;
    private final int partSize;
    private final Executor executor;
    private final boolean sequential;
    private final long maxObjectBytes;
    private final int maxBuffers;
    private final BlockingQueue<byte[]> freeBuffers;
    private final List<CompletableFuture<UploadedPart>> pending = new ArrayList<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    private byte[] buffer;
    private int position;
    private int allocatedBuffers;
    private int nextPartNumber = 1;
    private long bytesWritten;
    private State state = State.OPEN;

    public static MultipartOutputStream open(ObjectStorage storage, String key,
                                             ObjectMetadata metadata, MultipartConfig config) {
        return new MultipartOutputStream(storage.initiateMultipart(key, metadata), config);
    }

    public MultipartOutputStream(MultipartSession session, MultipartConfig config) {
        this.session = Objects.requireNonNull(session, "session");
        this.partSize = config.partSize();
        this.executor = config.executor();
        this.sequential = config.isSequential();
        this.maxObjectBytes = config.maxObjectBytes();
        this.maxBuffers = config.maxInFlight() + 1;
        this.freeBuffers = new ArrayBlockingQueue<>(maxBuffers);
        this.buffer = new byte[partSize];   // demais buffers são alocados sob demanda
        this.allocatedBuffers = 1;
    }

    @Override
    public void write(int b) throws IOException {
        ensureOpen();
        ensureWithinLimit(1);
        buffer[position++] = (byte) b;
        bytesWritten++;
        if (position == partSize) {
            dispatchFullBuffer();
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        ensureOpen();
        ensureWithinLimit(len);
        while (len > 0) {
            int n = Math.min(len, partSize - position);
            System.arraycopy(b, off, buffer, position, n);
            position += n;
            off += n;
            len -= n;
            bytesWritten += n;
            if (position == partSize) {
                dispatchFullBuffer();
            }
        }
    }

    /**
     * Intencionalmente não envia nada: partes intermediárias precisam ter
     * pelo menos 5 MiB, e wrappers (BufferedWriter, GZIP) chamam flush() à vontade.
     */
    @Override
    public void flush() {
    }

    /**
     * Envia o restante, espera todas as partes e conclui o upload. Qualquer falha aborta.
     *
     * @throws StorageException falha do storage, com o tipo original ({@link AccessDeniedException}...)
     * @throws IOException      interrupção, stream já finalizado ou limite de partes
     */
    public void commit() throws IOException {
        ensureOpen();
        try {
            // A última parte pode ser menor que 5 MiB. Se nada foi escrito,
            // ainda enviamos uma parte vazia: o upload exige ao menos uma.
            if (position > 0 || pending.isEmpty()) {
                dispatch(buffer, position);
            }
            buffer = null;
            session.complete(awaitAllParts());
            state = State.COMMITTED;
        } catch (IOException | RuntimeException e) {
            abortQuietly(e);
            throw e;   // StorageException continua StorageException: quem classifica por tipo depende disso
        }
    }

    /** Descarta explicitamente o upload. */
    public void abort() {
        if (state == State.OPEN) {
            abortQuietly(null);
        }
    }

    /** Sem {@link #commit()} prévio, fechar significa abortar. */
    @Override
    public void close() {
        abort();
    }

    public long bytesWritten() {
        return bytesWritten;
    }

    public int partCount() {
        return nextPartNumber - 1;
    }

    public String uploadId() {
        return session.uploadId();
    }

    /**
     * Visão deste stream cujo {@code close()} não faz nada, para entregar a quem fecha o
     * stream recebido ({@code GZIPOutputStream.close()}, {@code ObjectMapper.writeValue}).
     * O upload continua dependendo de {@link #commit()} neste objeto:
     *
     * <pre>{@code
     * try (MultipartOutputStream out = MultipartOutputStream.open(storage, key, metadata, config)) {
     *     try (GZIPOutputStream gzip = new GZIPOutputStream(out.nonClosing())) {
     *         writeReport(gzip);
     *     }
     *     out.commit();
     * }
     * }</pre>
     */
    public OutputStream nonClosing() {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                MultipartOutputStream.this.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                MultipartOutputStream.this.write(b, off, len);
            }

            @Override
            public void close() {
            }
        };
    }

    // ------------------------------------------------------------------

    private void dispatchFullBuffer() throws IOException {
        dispatch(buffer, position);
        buffer = acquireBuffer();   // pode bloquear: é aqui que acontece o backpressure
        position = 0;
    }

    private void dispatch(byte[] data, int length) throws IOException {
        rethrowIfFailed();
        int partNumber = nextPartNumber;
        if (partNumber > MAX_PARTS) {
            throw new IOException("Limite de " + MAX_PARTS + " partes excedido em "
                    + describe() + "; aumente o partSize");
        }
        nextPartNumber++;

        CompletableFuture<UploadedPart> future = sequential
                ? uploadNow(partNumber, data, length)
                : CompletableFuture.supplyAsync(() -> session.uploadPart(partNumber, data, length), executor);
        future.whenComplete((part, error) -> {
            if (error != null) {
                failure.compareAndSet(null, unwrap(error));
            }
            freeBuffers.offer(data);   // devolve o buffer ao pool, com sucesso ou falha
        });
        pending.add(future);
        if (sequential) {
            rethrowIfFailed();   // a parte já terminou: a falha aparece nesta mesma escrita
        }
    }

    private CompletableFuture<UploadedPart> uploadNow(int partNumber, byte[] data, int length) {
        try {
            return CompletableFuture.completedFuture(session.uploadPart(partNumber, data, length));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void ensureWithinLimit(int len) {
        if (maxObjectBytes != MultipartConfig.UNLIMITED && bytesWritten + len > maxObjectBytes) {
            ObjectTooLargeException tooLarge = new ObjectTooLargeException(
                    describe() + " passaria de " + maxObjectBytes + " bytes", maxObjectBytes);
            abortQuietly(tooLarge);
            throw tooLarge;
        }
    }

    private String describe() {
        return session.key() + " (uploadId " + session.uploadId() + ")";
    }

    private byte[] acquireBuffer() throws IOException {
        byte[] free = freeBuffers.poll();
        if (free != null) {
            return free;
        }
        if (allocatedBuffers < maxBuffers) {
            allocatedBuffers++;
            return new byte[partSize];
        }
        try {
            return freeBuffers.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrompido aguardando upload de parte");
        }
    }

    private List<UploadedPart> awaitAllParts() throws IOException {
        List<UploadedPart> parts = new ArrayList<>(pending.size());
        for (CompletableFuture<UploadedPart> future : pending) {
            try {
                parts.add(future.get());
            } catch (ExecutionException e) {
                if (e.getCause() instanceof StorageException storage) {
                    throw storage;
                }
                throw new IOException("Falha no upload de parte de " + describe(), e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrompido aguardando partes");
            }
        }
        return parts;
    }

    private void abortQuietly(Throwable cause) {
        state = State.ABORTED;
        buffer = null;
        // Espera as partes em voo: uma parte que termina DEPOIS do abort
        // ficaria órfã no bucket (e sendo cobrada).
        for (CompletableFuture<UploadedPart> future : pending) {
            try {
                future.join();
            } catch (RuntimeException ignored) {
                // a falha original já está em 'cause' ou em 'failure'
            }
        }
        try {
            session.abort();
        } catch (RuntimeException e) {
            if (cause != null) {
                cause.addSuppressed(e);
            } else {
                LOG.log(System.Logger.Level.WARNING,
                        "Falha ao abortar upload de " + describe()
                                + "; a lifecycle rule do bucket deve limpar", e);
            }
        }
    }

    private void ensureOpen() throws IOException {
        if (state != State.OPEN) {
            throw new IOException("Stream de " + describe() + " já finalizado (" + state + ")");
        }
    }

    private void rethrowIfFailed() throws IOException {
        Throwable t = failure.get();
        if (t instanceof StorageException storage) {
            throw storage;   // sobe com o stack trace da thread da parte; a mensagem traz a chave
        }
        if (t != null) {
            throw new IOException("Upload de parte falhou para " + describe(), t);
        }
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
    }
}
