package com.example.storage.azure;

import com.azure.storage.blob.BlobServiceClientBuilder;
import com.example.storage.ObjectStorage;
import com.example.storage.testkit.ObjectStorageContract;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Contrato contra um container Azure real com a imutabilidade por versão habilitada, opt-in: roda só com
 * {@code STORAGE_AZURE_IMMUTABLE_CONNECTION_STRING} e {@code STORAGE_AZURE_IMMUTABLE_CONTAINER}. A conta
 * precisa de versionamento. Os testes de retenção usam a política Unlocked por alguns minutos e não
 * conseguem apagar o que criaram: use um container dedicado, com limpeza por lifecycle.
 *
 * <pre>{@code
 * STORAGE_AZURE_IMMUTABLE_CONNECTION_STRING=... STORAGE_AZURE_IMMUTABLE_CONTAINER=meu-container \
 *   mvn -pl storage-azure test -Dtest=AzureImmutableContainerContractTest
 * }</pre>
 */
@EnabledIfEnvironmentVariable(named = "STORAGE_AZURE_IMMUTABLE_CONNECTION_STRING", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STORAGE_AZURE_IMMUTABLE_CONTAINER", matches = ".+")
class AzureImmutableContainerContractTest extends ObjectStorageContract {

    private static AzureBlobObjectStorage storage;

    private static synchronized AzureBlobObjectStorage container() {
        if (storage == null) {
            storage = AzureBlobObjectStorage.withSharedKey(new BlobServiceClientBuilder()
                    .connectionString(System.getenv("STORAGE_AZURE_IMMUTABLE_CONNECTION_STRING"))
                    .buildClient().getBlobContainerClient(System.getenv("STORAGE_AZURE_IMMUTABLE_CONTAINER")));
        }
        return storage;
    }

    @Override
    protected ObjectStorage storage() {
        return container();
    }

    @Override
    protected boolean supportsRetention() {
        return true;
    }

    @Override
    protected int listCount() {
        return 100;
    }
}
