package com.example.storage;

import java.util.Map;

/** Resultado de {@link ObjectStorage#deleteAll}: chaves que não puderam ser apagadas e o motivo. */
public record DeleteResult(Map<String, StorageException> failures) {

    public DeleteResult {
        failures = Map.copyOf(failures);
    }

    public boolean isSuccess() {
        return failures.isEmpty();
    }
}
