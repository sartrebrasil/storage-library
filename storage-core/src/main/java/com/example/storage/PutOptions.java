package com.example.storage;

import java.util.Objects;

/** Metadata e pré-condição de uma escrita. */
public record PutOptions(ObjectMetadata metadata, Condition condition) {

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
        return new PutOptions(metadata, Condition.ifNotExists());
    }

    public PutOptions ifVersionMatches(String version) {
        return new PutOptions(metadata, Condition.ifVersionMatches(version));
    }
}
