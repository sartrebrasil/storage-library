package com.example.storage.spring;

/**
 * Meters que a Observation não expressa ({@code storage.bytes}, {@code storage.deleteAll.keys},
 * {@code storage.operations.ttfb}, {@code storage.multipart.parts}). Não
 * referencia o Micrometer, como {@link DelegatingObjectStorage}: o decorador funciona (só com
 * observações) mesmo sem o {@code micrometer-core} no classpath.
 */
interface ExtraMeters {

    ExtraMeters NONE = new ExtraMeters() { };

    default void bytes(String operation, StorageId storage, String direction, long bytes) {
    }

    default void deletedKeys(StorageId storage, long ok, long failed) {
    }

    default void ttfb(String operation, StorageId storage, long nanos) {
    }

    default void part(StorageId storage, String outcome, long nanos) {
    }
}
