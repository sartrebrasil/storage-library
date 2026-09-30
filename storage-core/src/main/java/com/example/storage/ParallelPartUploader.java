package com.example.storage;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Até {@code maxInFlight} partes subindo no executor enquanto o stream escreve em outro buffer.
 * O pool tem {@code maxInFlight + 1} buffers: com o storage lento, {@link #nextBuffer()} bloqueia
 * a thread que escreve em vez de acumular o objeto em memória.
 */
final class ParallelPartUploader implements PartUploader {

    private final MultipartSession session;
    private final Executor executor;
    private final int partSize;
    private final int maxBuffers;
    private final BlockingQueue<byte[]> freeBuffers;
    private final List<CompletableFuture<UploadedPart>> pending = new ArrayList<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private int allocatedBuffers = 1;   // o primeiro é do stream

    ParallelPartUploader(MultipartSession session, MultipartConfig config) {
        this.session = session;
        this.executor = config.executor();
        this.partSize = config.partSize();
        this.maxBuffers = config.maxInFlight() + 1;
        this.freeBuffers = new ArrayBlockingQueue<>(maxBuffers);
    }

    @Override
    public byte[] nextBuffer(byte[] sent) throws IOException {
        byte[] free = freeBuffers.poll();
        if (free != null) {
            return free;
        }
        if (allocatedBuffers < maxBuffers) {   // alocados sob demanda
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

    @Override
    public void send(int partNumber, byte[] data, int length) throws IOException {
        rethrowIfFailed();
        CompletableFuture<UploadedPart> future =
                CompletableFuture.supplyAsync(() -> session.uploadPart(partNumber, data, length), executor);
        future.whenComplete((part, error) -> {
            if (error != null) {
                failure.compareAndSet(null, unwrap(error));
            }
            freeBuffers.offer(data);   // devolve o buffer ao pool, com sucesso ou falha
        });
        pending.add(future);
    }

    @Override
    public List<UploadedPart> awaitAll() throws IOException {
        List<UploadedPart> parts = new ArrayList<>(pending.size());
        for (CompletableFuture<UploadedPart> future : pending) {
            try {
                parts.add(future.get());
            } catch (ExecutionException e) {
                rethrow(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrompido aguardando partes");
            }
        }
        return parts;
    }

    /** Uma parte que terminasse DEPOIS do abort ficaria órfã no bucket (e sendo cobrada). */
    @Override
    public void drain() {
        for (CompletableFuture<UploadedPart> future : pending) {
            try {
                future.join();
            } catch (RuntimeException ignored) {
                // a falha original já subiu por send ou awaitAll
            }
        }
    }

    private void rethrowIfFailed() throws IOException {
        Throwable t = failure.get();
        if (t != null) {
            rethrow(t);
        }
    }

    /** StorageException sobe com o tipo original: quem classifica por tipo depende disso. */
    private void rethrow(Throwable t) throws IOException {
        if (t instanceof StorageException storage) {
            throw storage;   // com o stack trace da thread da parte; a mensagem traz a chave
        }
        throw new IOException("Upload de parte falhou para " + PartUploader.describe(session), t);
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
    }
}
