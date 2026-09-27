package com.example.storage;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * @param partSize    tamanho de cada parte em bytes (mínimo 5 MiB, exceto a última)
 * @param maxInFlight quantas partes podem estar subindo ao mesmo tempo
 * @param executor    onde os uploads rodam (virtual threads funcionam bem aqui)
 *
 * Memória de pico por relatório ≈ (maxInFlight + 1) × partSize.
 * Tamanho máximo do objeto ≈ 10.000 × partSize (16 MiB → ~156 GiB).
 */
public record MultipartConfig(int partSize, int maxInFlight, Executor executor) {

    public static final int MIB = 1024 * 1024;
    public static final int MIN_PART_SIZE = 5 * MIB;

    public MultipartConfig {
        if (partSize < MIN_PART_SIZE) {
            throw new IllegalArgumentException("partSize deve ser >= 5 MiB");
        }
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight deve ser >= 1");
        }
        Objects.requireNonNull(executor, "executor");
    }

    public static MultipartConfig defaults(Executor executor) {
        return new MultipartConfig(16 * MIB, 4, executor);
    }
}
