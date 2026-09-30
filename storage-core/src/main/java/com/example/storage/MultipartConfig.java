package com.example.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * @param partSize       tamanho de cada parte em bytes (mínimo 5 MiB, exceto a última)
 * @param maxInFlight    quantas partes podem estar subindo ao mesmo tempo; {@code 0} envia
 *                       cada parte na própria thread que escreve (modo sequencial)
 * @param executor       onde os uploads rodam (virtual threads funcionam bem aqui);
 *                       ignorado, e pode ser {@code null}, no modo sequencial
 * @param maxObjectBytes tamanho máximo do objeto; {@link #UNLIMITED} desliga o limite
 * @param digestAlgorithm algoritmo de {@link MessageDigest} ("SHA-256"...) calculado sobre os bytes
 *                       enviados; {@code null} desliga
 *
 * Memória de pico por upload ≈ (maxInFlight + 1) × partSize; no modo sequencial, um único partSize.
 * Tamanho máximo do objeto ≈ 10.000 × partSize (16 MiB → ~156 GiB).
 */
public record MultipartConfig(int partSize, int maxInFlight, Executor executor, long maxObjectBytes,
                              String digestAlgorithm) {

    public static final int MIB = 1024 * 1024;
    public static final int MIN_PART_SIZE = 5 * MIB;
    public static final long UNLIMITED = -1;

    public MultipartConfig {
        if (partSize < MIN_PART_SIZE) {
            throw new IllegalArgumentException("partSize deve ser >= 5 MiB");
        }
        if (maxInFlight < 0) {
            throw new IllegalArgumentException("maxInFlight deve ser >= 0");
        }
        if (maxInFlight > 0) {
            Objects.requireNonNull(executor, "executor");
        }
        if (maxObjectBytes < UNLIMITED) {
            throw new IllegalArgumentException("maxObjectBytes deve ser >= 0 ou UNLIMITED");
        }
        if (digestAlgorithm != null) {
            newDigest(digestAlgorithm);   // algoritmo inválido falha na configuração, não no upload
        }
    }

    public MultipartConfig(int partSize, int maxInFlight, Executor executor, long maxObjectBytes) {
        this(partSize, maxInFlight, executor, maxObjectBytes, null);
    }

    public MultipartConfig(int partSize, int maxInFlight, Executor executor) {
        this(partSize, maxInFlight, executor, UNLIMITED);
    }

    public static MultipartConfig defaults(Executor executor) {
        return new MultipartConfig(16 * MIB, 4, executor);
    }

    /** Um buffer só, sem executor: menor memória possível, sem paralelismo entre partes. */
    public static MultipartConfig sequential(int partSize) {
        return new MultipartConfig(partSize, 0, null);
    }

    public MultipartConfig withMaxObjectBytes(long maxObjectBytes) {
        return new MultipartConfig(partSize, maxInFlight, executor, maxObjectBytes, digestAlgorithm);
    }

    /** Calcula o digest dos bytes enviados; lido em {@link MultipartOutputStream#digestHex()}. */
    public MultipartConfig withDigest(String digestAlgorithm) {
        return new MultipartConfig(partSize, maxInFlight, executor, maxObjectBytes,
                Objects.requireNonNull(digestAlgorithm, "digestAlgorithm"));
    }

    public boolean isSequential() {
        return maxInFlight == 0;
    }

    static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Algoritmo de digest não suportado: " + algorithm, e);
        }
    }
}
