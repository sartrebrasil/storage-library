package com.example.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resultado de {@link ObjectStorage#deleteAll}: chaves que não puderam ser apagadas e o motivo. */
public record DeleteResult(Map<String, StorageException> failures) {

    public DeleteResult {
        failures = Collections.unmodifiableMap(new LinkedHashMap<>(failures));   // na ordem das chaves pedidas
    }

    public boolean isSuccess() {
        return failures.isEmpty();
    }
}
