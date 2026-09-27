package com.example.storage.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;

/**
 * Configuração do {@code ObjectStorage}.
 *
 * <pre>
 * storage:
 *   provider: s3            # s3 | gcs | azure | oci
 *   bucket: reports         # no Azure, o container
 *   s3:
 *     region: sa-east-1
 *     endpoint: http://localhost:9000   # MinIO/LocalStack
 *     path-style: true
 * </pre>
 *
 * @param provider sem valor, nenhum {@code ObjectStorage} é criado
 */
@ConfigurationProperties("storage")
public record StorageProperties(Provider provider,
                                String bucket,
                                @DefaultValue S3 s3,
                                @DefaultValue Gcs gcs,
                                @DefaultValue Azure azure,
                                @DefaultValue Oci oci) {

    public enum Provider { S3, GCS, AZURE, OCI }

    /**
     * @param region    sem valor, usa a cadeia padrão da AWS (AWS_REGION, profile...)
     * @param endpoint  endpoint compatível (MinIO, LocalStack); sem valor, AWS
     * @param accessKey com {@code secretKey}, credencial estática; sem valor, cadeia padrão da AWS
     */
    public record S3(String region, URI endpoint, boolean pathStyle, String accessKey, String secretKey) {
    }

    /**
     * @param projectId sem valor, usa o do ambiente (Application Default Credentials)
     */
    public record Gcs(String projectId) {
    }

    /**
     * Com {@code connectionString}, usa a chave da conta (inclui Azurite). Sem ela, usa
     * {@code endpoint} com DefaultAzureCredential (requer {@code azure-identity}).
     *
     * @param endpoint ex.: {@code https://conta.blob.core.windows.net}
     */
    public record Azure(String connectionString, String endpoint) {
    }

    /**
     * @param configFile sem valor, {@code ~/.oci/config}
     * @param namespace  sem valor, é consultado na API na inicialização
     */
    public record Oci(String configFile, @DefaultValue("DEFAULT") String profile, String namespace) {
    }
}
