package com.example.storage;

import java.time.Duration;
import java.util.Objects;

/**
 * Validade de uma URL de download proporcional ao tamanho do objeto: o tempo de baixá-lo a
 * {@code assumedBytesPerSecond}, limitado a {@code [min, max]}. Um arquivo grande não expira no meio do
 * download, e um pequeno não fica com uma URL válida por horas.
 *
 * <pre>{@code
 * URI url = storage.presignGet(key, ttl.forSize(info.size()));
 * }</pre>
 */
public record PresignTtl(long assumedBytesPerSecond, Duration min, Duration max) {

    public PresignTtl {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (assumedBytesPerSecond <= 0) {
            throw new IllegalArgumentException("assumedBytesPerSecond deve ser > 0");
        }
        if (min.toSeconds() <= 0) {
            throw new IllegalArgumentException("min deve ter pelo menos 1 segundo");
        }
        if (min.compareTo(max) > 0) {
            throw new IllegalArgumentException("min deve ser <= max");
        }
    }

    /** Truncado em segundos, a unidade das URLs pré-assinadas. */
    public Duration forSize(long sizeBytes) {
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes deve ser >= 0");
        }
        long seconds = sizeBytes / assumedBytesPerSecond;
        return Duration.ofSeconds(Math.clamp(seconds, min.toSeconds(), max.toSeconds()));
    }
}
