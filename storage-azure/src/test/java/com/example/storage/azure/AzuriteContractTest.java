package com.example.storage.azure;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.example.storage.ObjectStorage;
import com.example.storage.testkit.ObjectStorageContract;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Contrato contra o Azurite (emulador oficial). Pulado quando não há Docker. */
@Testcontainers(disabledWithoutDocker = true)
class AzuriteContractTest extends ObjectStorageContract {

    // Conta e chave públicas e fixas do emulador, documentadas pela Microsoft.
    private static final String ACCOUNT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    @Container
    private static final GenericContainer<?> AZURITE =
            new GenericContainer<>("mcr.microsoft.com/azure-storage/azurite:latest")
                    .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--skipApiVersionCheck", "--loose")
                    .withExposedPorts(10000)
                    .waitingFor(Wait.forListeningPort());

    private static AzureBlobObjectStorage storage;
    private static AzureBlobObjectStorage missing;

    @BeforeAll
    static void setUp() {
        String connectionString = "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey="
                + ACCOUNT_KEY + ";BlobEndpoint=http://" + AZURITE.getHost() + ":" + AZURITE.getMappedPort(10000)
                + "/devstoreaccount1;";
        BlobContainerClient container = new BlobServiceClientBuilder().connectionString(connectionString)
                .buildClient().createBlobContainer("contract");
        storage = AzureBlobObjectStorage.withSharedKey(container);
        missing = AzureBlobObjectStorage.withSharedKey(new BlobServiceClientBuilder()
                .connectionString(connectionString).buildClient().getBlobContainerClient("nao-existe"));
    }

    @Override
    protected ObjectStorage storage() {
        return storage;
    }

    @Override
    protected ObjectStorage storageWithMissingBucket() {
        return missing;
    }

    /** A página do Azure tem 5000 itens: 1010 objetos não exercitam paginação e só deixam o teste lento. */
    @Override
    protected int listCount() {
        return 100;
    }
}
