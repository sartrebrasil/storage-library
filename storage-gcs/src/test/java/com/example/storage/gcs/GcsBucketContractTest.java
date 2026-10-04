package com.example.storage.gcs;

import com.example.storage.ObjectStorage;
import com.example.storage.testkit.ObjectStorageContract;
import com.google.cloud.storage.HttpStorageOptions;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.UUID;

/**
 * Contrato contra um bucket GCS real, opt-in: roda só com {@code STORAGE_GCS_BUCKET} definido, usando as
 * Application Default Credentials. Os testes de URL assinada exigem uma service account (chave ou workload
 * identity com {@code iam.serviceAccountTokenCreator}). Cada execução usa um prefixo próprio e apaga o que
 * criou.
 *
 * <pre>{@code
 * STORAGE_GCS_BUCKET=meu-bucket mvn -pl storage-gcs test -Dtest=GcsBucketContractTest
 * }</pre>
 */
@EnabledIfEnvironmentVariable(named = "STORAGE_GCS_BUCKET", matches = ".+")
class GcsBucketContractTest extends ObjectStorageContract {

    private static GcsObjectStorage storage;

    private static synchronized GcsObjectStorage bucket() {
        if (storage == null) {
            storage = GcsObjectStorage.create(HttpStorageOptions.getDefaultInstance(),
                    System.getenv("STORAGE_GCS_BUCKET"));
        }
        return storage;
    }

    @Override
    protected ObjectStorage storage() {
        return bucket();
    }

    @Override
    protected ObjectStorage storageWithMissingBucket() {
        return GcsObjectStorage.create(HttpStorageOptions.getDefaultInstance(), "nao-existe-" + UUID.randomUUID());
    }
}
