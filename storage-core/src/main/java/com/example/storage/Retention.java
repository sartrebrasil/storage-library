package com.example.storage;

import java.time.Instant;
import java.util.Objects;

/**
 * Impede apagar ou sobrescrever o objeto até {@code retainUntil}. {@link Mode#GOVERNANCE} equivale a
 * "Unlocked" no GCS e no Azure; {@link Mode#COMPLIANCE}, a "Locked": ninguém a remove antes do prazo.
 */
public record Retention(Instant retainUntil, Mode mode) {

    public enum Mode { GOVERNANCE, COMPLIANCE }

    public Retention {
        Objects.requireNonNull(retainUntil, "retainUntil");
        Objects.requireNonNull(mode, "mode");
    }
}
