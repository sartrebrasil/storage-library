package com.example.storage;

import java.time.Instant;

/**
 * Propriedades completas de um objeto, devolvidas por {@link ObjectStorage#head}.
 *
 * @param version token opaco para escrita condicional (ETag no S3/Azure/OCI, generation no GCS)
 */
public record ObjectInfo(String key, long size, String version, Instant lastModified, ObjectMetadata metadata) {
}
