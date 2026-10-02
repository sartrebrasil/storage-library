package com.example.storage.spring;

import com.example.storage.ObjectStorage;

/**
 * {@link ObjectStorage} que embrulha outro. Não referencia o Micrometer, para o health check
 * desembrulhar {@link ObjectStorageMetrics} mesmo numa aplicação sem Micrometer no classpath.
 */
interface DelegatingObjectStorage extends ObjectStorage {

    ObjectStorage delegate();
}
