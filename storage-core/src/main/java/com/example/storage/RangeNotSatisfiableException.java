package com.example.storage;

import java.util.OptionalLong;

/**
 * A faixa pedida não existe no objeto (HTTP 416): começa no fim dele ou depois, ou o objeto é vazio.
 *
 * <p>{@link #totalSize()} alimenta o {@code Content-Range: bytes *&#47;<total>} que a RFC 9110 §15.5.17 pede
 * na resposta. Os adapters nem sempre sabem o tamanho (o S3 não o devolve de forma confiável no erro);
 * quem já fez {@code head} completa com {@link #withTotalSize}.</p>
 */
public class RangeNotSatisfiableException extends StorageException {

    private final long totalSize;

    public RangeNotSatisfiableException(String message, Throwable cause) {
        this(message, cause, -1);
    }

    public RangeNotSatisfiableException(String message, Throwable cause, long totalSize) {
        super(message, cause);
        this.totalSize = totalSize;
    }

    /** Tamanho do objeto, quando conhecido. */
    public OptionalLong totalSize() {
        return totalSize < 0 ? OptionalLong.empty() : OptionalLong.of(totalSize);
    }

    /** A mesma falha, com o tamanho do objeto. A original fica como causa, para não perder o stack trace. */
    public RangeNotSatisfiableException withTotalSize(long size) {
        if (size < 0) {
            throw new IllegalArgumentException("size deve ser >= 0");
        }
        return new RangeNotSatisfiableException(getMessage(), this, size);
    }
}
