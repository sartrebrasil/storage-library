package com.example.storage.gcs;

import com.example.storage.ObjectStorage;
import com.example.storage.testkit.ObjectStorageContract;
import com.google.cloud.storage.HttpStorageOptions;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Contrato contra um bucket GCS real com a retenção por objeto habilitada, opt-in: roda só com
 * {@code STORAGE_GCS_RETENTION_BUCKET}. Os testes de retenção usam o modo Unlocked por alguns minutos
 * e não conseguem apagar o que criaram: use um bucket dedicado, com limpeza por lifecycle.
 *
 * <pre>{@code
 * STORAGE_GCS_RETENTION_BUCKET=meu-bucket mvn -pl storage-gcs test -Dtest=GcsRetentionContractTest
 * }</pre>
 */
@EnabledIfEnvironmentVariable(named = "STORAGE_GCS_RETENTION_BUCKET", matches = ".+")
class GcsRetentionContractTest extends ObjectStorageContract {

    private static GcsObjectStorage storage;

    private static synchronized GcsObjectStorage bucket() {
        if (storage == null) {
            storage = GcsObjectStorage.create(HttpStorageOptions.getDefaultInstance(),
                    System.getenv("STORAGE_GCS_RETENTION_BUCKET"));
        }
        return storage;
    }

    @Override
    protected ObjectStorage storage() {
        return bucket();
    }

    @Override
    protected boolean supportsRetention() {
        return true;
    }
}
