package com.example.storage.web;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Limite de downloads simultâneos nesta instância. Nunca bloqueia: sem vaga, {@link #tryAcquire()} volta
 * vazio e quem chama responde {@code 429}. Não é distribuído; com N réplicas, o teto total é N vezes o
 * {@code maxConcurrent}.
 */
public final class StreamLimiter {

    private final Semaphore semaphore;

    public StreamLimiter(int maxConcurrent) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent deve ser >= 1");
        }
        this.semaphore = new Semaphore(maxConcurrent);
    }

    public Optional<Permit> tryAcquire() {
        return semaphore.tryAcquire() ? Optional.of(new Permit()) : Optional.empty();
    }

    /** Vagas livres agora. */
    public int available() {
        return semaphore.availablePermits();
    }

    /** Uma vaga ocupada. Só quem a tem consegue soltá-la, e só uma vez, por mais que feche. */
    public final class Permit implements AutoCloseable {

        private final AtomicBoolean released = new AtomicBoolean();

        private Permit() {
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }
}
