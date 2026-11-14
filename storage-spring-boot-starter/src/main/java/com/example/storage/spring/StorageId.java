package com.example.storage.spring;

/** Identifica o storage nas métricas: tags {@code storage} (nome do bean) e {@code provider}. */
record StorageId(String name, String provider) {

    /** Pela classe do adapter, para valer também para um bean declarado pela aplicação. Sem referenciar os adapters (opcionais). */
    static StorageId of(String name, Object adapter) {
        String provider = switch (adapter.getClass().getSimpleName()) {
            case "S3ObjectStorage" -> "s3";
            case "GcsObjectStorage" -> "gcs";
            case "AzureBlobObjectStorage" -> "azure";
            case "OciObjectStorage" -> "oci";
            case "SftpObjectStorage" -> "sftp";
            case "FileSystemObjectStorage" -> "filesystem";
            default -> "unknown";   // InMemory, mock, implementação própria
        };
        return new StorageId(name, provider);
    }
}
