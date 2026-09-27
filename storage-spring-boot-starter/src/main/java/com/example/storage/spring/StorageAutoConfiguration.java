package com.example.storage.spring;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.example.storage.ObjectStorage;
import com.example.storage.azure.AzureBlobObjectStorage;
import com.example.storage.gcs.GcsObjectStorage;
import com.example.storage.oci.OciObjectStorage;
import com.example.storage.s3.S3ObjectStorage;
import com.google.cloud.storage.HttpStorageOptions;
import com.google.cloud.storage.MultipartUploadClient;
import com.google.cloud.storage.MultipartUploadSettings;
import com.google.cloud.storage.Storage;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.requests.GetNamespaceRequest;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;

/**
 * Cria o {@link ObjectStorage} do provedor em {@code storage.provider}. Cada provedor só é
 * configurado se o adapter correspondente estiver no classpath.
 *
 * <p>Clientes do SDK declarados pela aplicação ({@code S3Client}, {@code BlobContainerClient},
 * {@code HttpStorageOptions}, cliente OCI...) são reaproveitados; um {@code ObjectStorage}
 * próprio desliga esta auto-configuração.</p>
 *
 * <p>Os beans têm nome por provedor ({@code s3ObjectStorage}, {@code ociObjectStorage}...):
 * o nome padrão {@code objectStorage} colidiria com o cliente da OCI, cuja interface também
 * se chama {@code ObjectStorage}.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageAutoConfiguration {

    private static final String PREFIX = "storage";

    static String requireBucket(StorageProperties properties) {
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            throw new IllegalStateException("storage.bucket é obrigatório quando storage.provider está definido");
        }
        return properties.bucket();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({S3ObjectStorage.class, S3Client.class})
    @ConditionalOnProperty(prefix = PREFIX, name = "provider", havingValue = "s3")
    static class S3StorageConfiguration {

        @Bean
        @ConditionalOnMissingBean
        S3Client s3Client(StorageProperties properties) {
            StorageProperties.S3 s3 = properties.s3();
            S3ClientBuilder builder = S3Client.builder().forcePathStyle(s3.pathStyle());
            if (s3.region() != null) {
                builder.region(Region.of(s3.region()));
            }
            if (s3.endpoint() != null) {
                builder.endpointOverride(s3.endpoint());
            }
            if (s3.accessKey() != null) {
                builder.credentialsProvider(credentials(s3));
            }
            return builder.build();
        }

        /** Mesma configuração do cliente: o presigner não herda endpoint nem path-style do S3Client. */
        @Bean
        @ConditionalOnMissingBean
        S3Presigner s3Presigner(StorageProperties properties) {
            StorageProperties.S3 s3 = properties.s3();
            S3Presigner.Builder builder = S3Presigner.builder()
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(s3.pathStyle()).build());
            if (s3.region() != null) {
                builder.region(Region.of(s3.region()));
            }
            if (s3.endpoint() != null) {
                builder.endpointOverride(s3.endpoint());
            }
            if (s3.accessKey() != null) {
                builder.credentialsProvider(credentials(s3));
            }
            return builder.build();
        }

        @Bean
        @ConditionalOnMissingBean(ObjectStorage.class)
        ObjectStorage s3ObjectStorage(S3Client s3Client, S3Presigner s3Presigner, StorageProperties properties) {
            return new S3ObjectStorage(s3Client, s3Presigner, requireBucket(properties));
        }

        private static StaticCredentialsProvider credentials(StorageProperties.S3 s3) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(s3.accessKey(), s3.secretKey()));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({GcsObjectStorage.class, Storage.class})
    @ConditionalOnProperty(prefix = PREFIX, name = "provider", havingValue = "gcs")
    static class GcsStorageConfiguration {

        @Bean
        @ConditionalOnMissingBean
        HttpStorageOptions gcsStorageOptions(StorageProperties properties) {
            HttpStorageOptions.Builder builder = HttpStorageOptions.newBuilder();
            if (properties.gcs().projectId() != null) {
                builder.setProjectId(properties.gcs().projectId());
            }
            return builder.build();
        }

        @Bean
        @ConditionalOnMissingBean
        Storage gcsStorage(HttpStorageOptions options) {
            return options.getService();
        }

        @Bean
        @ConditionalOnMissingBean
        MultipartUploadClient gcsMultipartUploadClient(HttpStorageOptions options) {
            return MultipartUploadClient.create(MultipartUploadSettings.of(options));
        }

        @Bean
        @ConditionalOnMissingBean(ObjectStorage.class)
        ObjectStorage gcsObjectStorage(Storage gcsStorage, MultipartUploadClient gcsMultipartUploadClient,
                                    StorageProperties properties) {
            return new GcsObjectStorage(gcsStorage, gcsMultipartUploadClient, requireBucket(properties));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({AzureBlobObjectStorage.class, BlobContainerClient.class})
    @ConditionalOnProperty(prefix = PREFIX, name = "provider", havingValue = "azure")
    static class AzureStorageConfiguration {

        @Bean
        @ConditionalOnMissingBean
        BlobContainerClient azureBlobContainerClient(StorageProperties properties) {
            StorageProperties.Azure azure = properties.azure();
            BlobServiceClientBuilder builder = new BlobServiceClientBuilder();
            if (azure.connectionString() != null) {
                builder.connectionString(azure.connectionString());
            } else if (azure.endpoint() != null) {
                builder.endpoint(azure.endpoint()).credential(new DefaultAzureCredentialBuilder().build());
            } else {
                throw new IllegalStateException(
                        "Defina storage.azure.connection-string ou storage.azure.endpoint");
            }
            return builder.buildClient().getBlobContainerClient(requireBucket(properties));
        }

        /** O tipo de SAS segue a autenticação: chave da conta com connection string, senão user delegation. */
        @Bean
        @ConditionalOnMissingBean(ObjectStorage.class)
        ObjectStorage azureObjectStorage(BlobContainerClient azureBlobContainerClient, StorageProperties properties) {
            return properties.azure().connectionString() != null
                    ? AzureBlobObjectStorage.withSharedKey(azureBlobContainerClient)
                    : AzureBlobObjectStorage.withUserDelegation(azureBlobContainerClient);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({OciObjectStorage.class, ObjectStorageClient.class})
    @ConditionalOnProperty(prefix = PREFIX, name = "provider", havingValue = "oci")
    static class OciStorageConfiguration {

        @Bean
        @ConditionalOnMissingBean(com.oracle.bmc.objectstorage.ObjectStorage.class)
        ObjectStorageClient ociObjectStorageClient(StorageProperties properties) throws IOException {
            StorageProperties.Oci oci = properties.oci();
            ConfigFileAuthenticationDetailsProvider auth = oci.configFile() == null
                    ? new ConfigFileAuthenticationDetailsProvider(oci.profile())
                    : new ConfigFileAuthenticationDetailsProvider(oci.configFile(), oci.profile());
            return ObjectStorageClient.builder().build(auth);
        }

        @Bean
        @ConditionalOnMissingBean(ObjectStorage.class)
        ObjectStorage ociObjectStorage(com.oracle.bmc.objectstorage.ObjectStorage ociObjectStorageClient,
                                    StorageProperties properties) {
            String namespace = properties.oci().namespace() != null ? properties.oci().namespace()
                    : ociObjectStorageClient.getNamespace(GetNamespaceRequest.builder().build()).getValue();
            return new OciObjectStorage(ociObjectStorageClient, namespace, requireBucket(properties));
        }
    }
}
