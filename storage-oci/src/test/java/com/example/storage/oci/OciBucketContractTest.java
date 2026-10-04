package com.example.storage.oci;

import com.example.storage.ObjectStorage;
import com.example.storage.testkit.ObjectStorageContract;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.requests.GetNamespaceRequest;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

/**
 * Contrato contra um bucket OCI real, opt-in: roda só com {@code STORAGE_OCI_BUCKET} definido, autenticando
 * pelo {@code ~/.oci/config} (perfil em {@code STORAGE_OCI_PROFILE}, padrão {@code DEFAULT}). Cada execução
 * usa um prefixo próprio e apaga o que criou.
 *
 * <pre>{@code
 * STORAGE_OCI_BUCKET=meu-bucket mvn -pl storage-oci test -Dtest=OciBucketContractTest
 * }</pre>
 */
@EnabledIfEnvironmentVariable(named = "STORAGE_OCI_BUCKET", matches = ".+")
class OciBucketContractTest extends ObjectStorageContract {

    private static ObjectStorageClient client;
    private static String namespace;

    private static synchronized ObjectStorageClient client() {
        if (client == null) {
            String profile = System.getenv().getOrDefault("STORAGE_OCI_PROFILE", "DEFAULT");
            try {
                client = ObjectStorageClient.builder().build(new ConfigFileAuthenticationDetailsProvider(profile));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            namespace = client.getNamespace(GetNamespaceRequest.builder().build()).getValue();
        }
        return client;
    }

    @Override
    protected ObjectStorage storage() {
        return new OciObjectStorage(client(), namespace, System.getenv("STORAGE_OCI_BUCKET"));
    }

    @Override
    protected ObjectStorage storageWithMissingBucket() {
        return new OciObjectStorage(client(), namespace, "nao-existe-" + UUID.randomUUID());
    }

    /** O PAR de escrita não impõe {@code If-None-Match}: a condição de presignPut não é garantida. */
    @Override
    protected boolean supportsPresignedConditions() {
        return false;
    }

    /** Sem override de resposta na URL (PAR). */
    @Override
    protected boolean supportsPresignDownloadName() {
        return false;
    }
}
