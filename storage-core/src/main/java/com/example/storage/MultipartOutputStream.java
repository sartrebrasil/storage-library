package com.example.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/**
 * OutputStream que transforma escrita sequencial em upload multipart.
 *
 * <p>Os bytes vão para um buffer do tamanho de uma parte; quando enche, a parte vai
 * para o {@link PartUploader}. Em {@link MultipartConfig#sequential} ela sobe na própria
 * thread que escreve, com um único buffer; com {@code maxInFlight > 0}, sobe em background
 * enquanto a escrita continua em outro buffer, e o número limitado de buffers faz a thread
 * que escreve esperar (backpressure) se o storage ficar lento.</p>
 *
 * <p><b>Contrato de finalização:</b> o objeto só é gravado com
 * {@link #commit()}. Um {@link #close()} sem commit ABORTA o upload. Assim,
 * um erro no meio da leitura do banco dentro de um try-with-resources nunca
 * publica um relatório truncado. Para o caso comum, {@link #upload} faz o commit
 * e o abort por você; usando o stream direto, entregue {@link #nonClosing()} a quem
 * o fecha por conta própria ({@code GZIPOutputStream}, {@code ObjectMapper.writeValue}).</p>
 *
 * <p>Com {@link MultipartConfig#maxObjectBytes()}, a escrita que passaria do
 * limite aborta o upload e lança {@link ObjectTooLargeException}. Com
 * {@link MultipartConfig#digestAlgorithm()}, o digest dos bytes enviados fica
 * disponível em {@link #digestHex()} depois do commit.</p>
 *
 * <p>Qualquer falha aborta o upload. Falhas do storage chegam como {@link StorageException}
 * (runtime), com o tipo original, em {@code write} ou em {@link #commit()};
 * {@link IOException} fica para interrupção, stream já finalizado e limite de partes.</p>
 *
 * <p>Não é thread-safe para escrita: um único produtor por stream.</p>
 */
public final class MultipartOutputStream extends OutputStream {

    public static final int MAX_PARTS = 10_000;

    private static final System.Logger LOG = System.getLogger(MultipartOutputStream.class.getName());

    private enum State { OPEN, COMMITTED, ABORTED }

    /**
     * Upload concluído.
     *
     * @param value     o que {@link ObjectBody#writeTo} devolveu
     * @param digestHex digest dos bytes enviados, ou {@code null} sem {@link MultipartConfig#withDigest}
     */
    public record Result<T>(T value, String key, String uploadId, long bytesWritten, int partCount,
                            String digestHex) {
    }

    private final MultipartSession session;
    private final PartUploader uploader;
    private final long maxObjectBytes;
    private final MessageDigest digest;

    private byte[] buffer;
    private int position;
    private int nextPartNumber = 1;
    private long bytesWritten;
    private State state = State.OPEN;
    private String digestHex;

    public static MultipartOutputStream open(ObjectStorage storage, String key,
                                             ObjectMetadata metadata, MultipartConfig config) {
        return new MultipartOutputStream(storage.initiateMultipart(key, metadata), config);
    }

    /**
     * Abre o upload, entrega a {@code body} um stream que pode ser fechado sem efeito e faz
     * {@link #commit()} quando {@code body} retorna. Qualquer exceção aborta o upload e é relançada.
     *
     * <pre>{@code
     * Result<Long> result = MultipartOutputStream.upload(storage, key, metadata, config.withDigest("SHA-256"),
     *         ObjectBody.gzipped(out -> writeReport(out)));
     * }</pre>
     */
    public static <T> Result<T> upload(ObjectStorage storage, String key, ObjectMetadata metadata,
                                       MultipartConfig config, ObjectBody<T> body) throws IOException {
        try (MultipartOutputStream out = open(storage, key, metadata, config)) {
            T value = body.writeTo(out.nonClosing());
            out.commit();
            return new Result<>(value, key, out.uploadId(), out.bytesWritten(), out.partCount(), out.digestHex);
        }
    }

    public MultipartOutputStream(MultipartSession session, MultipartConfig config) {
        this.session = Objects.requireNonNull(session, "session");
        this.uploader = PartUploader.of(session, config);
        this.maxObjectBytes = config.maxObjectBytes();
        this.digest = config.digestAlgorithm() == null ? null : MultipartConfig.newDigest(config.digestAlgorithm());
        this.buffer = new byte[config.partSize()];
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        ensureOpen();
        ensureWithinLimit(len);
        if (digest != null) {
            digest.update(b, off, len);
        }
        bytesWritten += len;
        while (len > 0) {
            int n = Math.min(len, buffer.length - position);
            System.arraycopy(b, off, buffer, position, n);
            position += n;
            off += n;
            len -= n;
            if (position == buffer.length) {
                sendFullBuffer();
            }
        }
    }

    /**
     * Intencionalmente não envia nada: partes intermediárias precisam ter
     * pelo menos 5 MiB, e wrappers (BufferedWriter, GZIP) chamam flush() à vontade.
     */
    @Override
    public void flush() {
        // no-op
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
            if (position > 0 || partCount() == 0) {
                sendPart();
            }
            buffer = null;
            session.complete(uploader.awaitAll());
        } catch (IOException | RuntimeException e) {
            abortQuietly(e);
            throw e;
        }
        state = State.COMMITTED;
        if (digest != null) {
            digestHex = HexFormat.of().formatHex(digest.digest());
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

    /**
     * Digest em hexadecimal dos bytes enviados.
     *
     * @throws IllegalStateException sem {@link MultipartConfig#withDigest} ou antes do {@link #commit()}
     */
    public String digestHex() {
        if (digest == null || state != State.COMMITTED) {
            throw new IllegalStateException("digest disponível só com MultipartConfig.withDigest e depois do commit");
        }
        return digestHex;
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

    private void sendFullBuffer() throws IOException {
        try {
            sendPart();
            buffer = uploader.nextBuffer(buffer);
            position = 0;
        } catch (IOException | RuntimeException e) {
            abortQuietly(e);
            throw e;
        }
    }

    private void sendPart() throws IOException {
        if (nextPartNumber > MAX_PARTS) {
            throw new IOException("Limite de " + MAX_PARTS + " partes excedido em "
                    + PartUploader.describe(session) + "; aumente o partSize");
        }
        uploader.send(nextPartNumber++, buffer, position);
    }

    private void ensureWithinLimit(int len) {
        if (maxObjectBytes != MultipartConfig.UNLIMITED && bytesWritten + len > maxObjectBytes) {
            ObjectTooLargeException tooLarge = new ObjectTooLargeException(
                    PartUploader.describe(session) + " passaria de " + maxObjectBytes + " bytes", maxObjectBytes);
            abortQuietly(tooLarge);
            throw tooLarge;
        }
    }

    private void abortQuietly(Throwable cause) {
        state = State.ABORTED;
        buffer = null;
        uploader.drain();
        try {
            session.abort();
        } catch (RuntimeException e) {
            if (cause != null) {
                cause.addSuppressed(e);
            } else {
                LOG.log(System.Logger.Level.WARNING, "Falha ao abortar upload de " + PartUploader.describe(session)
                        + "; a lifecycle rule do bucket deve limpar", e);
            }
        }
    }

    private void ensureOpen() throws IOException {
        if (state != State.OPEN) {
            throw new IOException("Stream de " + PartUploader.describe(session) + " já finalizado (" + state + ")");
        }
    }
}
