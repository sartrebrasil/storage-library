package com.example.storage;

import java.util.Objects;

/**
 * Metadata, pré-condição e proteção de uma escrita.
 *
 * @param retention retenção a aplicar ao objeto, ou {@code null}
 * @param legalHold liga o legal hold na escrita
 */
public record PutOptions(ObjectMetadata metadata, Condition condition, Retention retention, boolean legalHold) {

    public PutOptions(ObjectMetadata metadata, Condition condition) {
        this(metadata, condition, null, false);
    }

    public PutOptions {
        Objects.requireNonNull(metadata, "metadata").requireWritable();
        Objects.requireNonNull(condition, "condition");
    }

    public static PutOptions of(ObjectMetadata metadata) {
        return new PutOptions(metadata, Condition.none());
    }

    public static PutOptions of(String contentType) {
        return of(ObjectMetadata.of(contentType));
    }

    public PutOptions ifNotExists() {
        return new PutOptions(metadata, Condition.ifNotExists(), retention, legalHold);
    }

    public PutOptions ifVersionMatches(String version) {
        return new PutOptions(metadata, Condition.ifVersionMatches(version), retention, legalHold);
    }

    public PutOptions withRetention(Retention retention) {
        return new PutOptions(metadata, condition, Objects.requireNonNull(retention, "retention"), legalHold);
    }

    public PutOptions withLegalHold() {
        return new PutOptions(metadata, condition, retention, true);
    }
}
