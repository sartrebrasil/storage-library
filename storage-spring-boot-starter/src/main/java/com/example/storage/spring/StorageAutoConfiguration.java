package com.example.storage.spring;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.example.storage.ObjectStorage;
import com.example.storage.azure.AzureBlobObjectStorage;
import com.example.storage.filesystem.FileSystemObjectStorage;
import com.example.storage.gcs.GcsObjectStorage;
import com.example.storage.oci.OciObjectStorage;
import com.example.storage.s3.S3ObjectStorage;
import com.example.storage.sftp.SftpObjectStorage;
import com.google.cloud.storage.HttpStorageOptions;
import com.google.cloud.storage.MultipartUploadClient;
import com.google.cloud.storage.MultipartUploadSettings;
import com.google.cloud.storage.Storage;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.requests.GetNamespaceRequest;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.type.AnnotationMetadata;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Cria o {@link ObjectStorage} do provedor em {@code storage.provider}. Cada provedor só é
 * configurado se o adapter correspondente estiver no classpath.
 *
 * <p>Clientes do SDK declarados pela aplicação ({@code S3Client}, {@code BlobContainerClient},
 * {@code HttpStorageOptions}, cliente OCI...) são reaproveitados; um {@code ObjectStorage}
 * próprio desliga esta auto-configuração.</p>
 *
 * <p>Vários buckets: {@code storage.buckets.<nome>=<bucket>} cria um {@code ObjectStorage} por
 * entrada ({@code <nome>ObjectStorage}, qualifier {@code <nome>}), todos com os mesmos clientes,
 * no lugar do bean único de {@code storage.bucket}.</p>
 *
 * <p>Os beans têm nome por provedor ({@code s3ObjectStorage}, {@code ociObjectStorage}...):
 * o nome padrão {@code objectStorage} colidiria com o cliente da OCI, cuja interface também
 * se chama {@code ObjectStorage}.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(StorageProperties.class)
@Import(StorageAutoConfiguration.BucketsRegistrar.class)
public class StorageAutoConfiguration {

    private static final String PREFIX = "storage";

    static String requireBucket(StorageProperties properties) {
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            throw new IllegalStateException("storage.bucket é obrigatório quando storage.provider está definido");
        }
        return properties.bucket();
    }

    static Path requireFilesystemRoot(StorageProperties properties) {
        Path root = properties.filesystem().root();
        if (root == null) {
            throw new IllegalStateException("storage.filesystem.root é obrigatório quando storage.provider=filesystem");
        }
        return root;
    }

    static String requireSftpRoot(StorageProperties properties) {
        String root = properties.sftp().root();
        if (root == null) {
            throw new IllegalStateException("storage.sftp.root é obrigatório quando storage.provider=sftp");
        }
        return root;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({S3ObjectStorage.class, S3Client.class})
    @ConditionalOnProperty(prefix = PREFIX, name = "provider", havingValue = "s3")
    static class S3StorageConfiguration {

        /**
         * Um provider só, compartilhado pelo cliente e pelo presigner: credencial estática com
         * {@code access-key}, senão a cadeia padrão da AWS (variáveis, IRSA, perfil, metadata),
         * com renovação em fundo das credenciais temporárias ({@code async-credential-update}).
         */
        @Bean
        @ConditionalOnMissingBean
        AwsCredentialsProvider s3CredentialsProvider(StorageProperties properties) {
            StorageProperties.S3 s3 = properties.s3();
            return s3.accessKey() != null
                    ? StaticCredentialsProvider.create(AwsBasicCredentials.create(s3.accessKey(), s3.secretKey()))
                    : DefaultCredentialsProvider.builder().asyncCredentialUpdateEnabled(s3.asyncCredentialUpdate()).build();
        }

        @Bean
        @ConditionalOnMissingBean
        S3Client s3Client(StorageProperties properties, AwsCredentialsProvider s3CredentialsProvider) {
            StorageProperties.S3 s3 = properties.s3();
            S3ClientBuilder builder = S3Client.builder().forcePathStyle(s3.pathStyle())
                    .credentialsProvider(s3CredentialsProvider);
            if (s3.checksum() == StorageProperties.S3.Checksum.NONE) {
                // Sem isso o SDK (>= 2.30) calcula CRC32 sozinho, mesmo sem o adapter pedir
                builder.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
            }
            if (s3.region() != null) {
                builder.region(Region.of(s3.region()));
            }
            if (s3.endpoint() != null) {
                builder.endpointOverride(s3.endpoint());
            }
            return builder.build();
        }

        /** Mesma configuração do cliente: o presigner não herda endpoint nem path-style do S3Client. */
        @Bean
        @ConditionalOnMissingBean
        S3Presigner s3Presigner(StorageProperties properties, AwsCredentialsProvider s3CredentialsProvider) {
            StorageProperties.S3 s3 = properties.s3();
            S3Presigner.Builder builder = S3Presigner.builder()
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(s3.pathStyle()).build())
                    .credentialsProvider(s3CredentialsProvider);
            if (s3.region() != null) {
                builder.region(Region.of(s3.region()));
            }
            if (s3.endpoint() != null) {
                builder.endpointOverride(s3.endpoint());
            }
            return builder.build();
        }

        @Bean
        BucketStorageFactory s3BucketStorageFactory(S3Client s3Client, S3Presigner s3Presigner,
                                                    StorageProperties properties) {
            S3ObjectStorage.Checksum checksum = S3ObjectStorage.Checksum.valueOf(properties.s3().checksum().name());
            return bucket -> new S3ObjectStorage(s3Client, s3Presigner, bucket, checksum);
        }

        @Bean
        @ConditionalOnMissingBean(ObjectStorage.class)
        @Conditional(SingleBucket.class)
        ObjectStorage s3ObjectStorage(BucketStorageFactory s3BucketStorageFactory, StorageProperties properties) {
            return s3BucketStorageFactory.create(requireBucket(properties));
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
        BucketStorageFactory gcsBucketStorageFactory(Storage gcsStorage, MultipartUploadClient gcsMultipartUploadClient) {
            return bucket -> new GcsObjectStorage(gcsStorage, gcsMultipartUploadClient, bucket);
        }

        @Bean
        @ConditionalOnMissingBean(ObjectStorage.class)
        @Conditional(SingleBucket.class)
        ObjectStorage gcsObjectStorage(BucketStorageFactory gcsBucketStorageFactory, StorageProperties properties) {
            return gcsBucketStorageFactory.create(requireBucket(properties));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({AzureBlobObjectStorage.class, BlobContainerClient.class})
    @ConditionalOnProperty(prefix = PREFIX, name = "provider", havingValue = "azure")
    static class AzureStorageConfiguration {

        /** Cliente da conta, sem container: a aplicação deriva um {@code BlobContainerClient} por container. */
        @Bean
        @ConditionalOnMissingBean
        BlobServiceClient azureBlobServiceClient(StorageProperties properties) {
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
            return builder.buildClient();
        }

        /** Não é criado com {@code ObjectStorage} da aplicação: aí {@code storage.bucket} pode faltar. */
        @Bean
        @ConditionalOnMissingBean({BlobContainerClient.class, ObjectStorage.class})
        @Conditional(SingleBucket.class)
        BlobContainerClient azureBlobContainerClient(BlobServiceClient azureBlobServiceClient,
                                                     StorageProperties properties) {
            return azureBlobServiceClient.getBlobContainerClient(requireBucket(properties));
        }

        @Bean
        BucketStorageFactory azureBucketStorageFactory(BlobServiceClient azureBlobServiceClient,
                                                       StorageProperties properties) {
            return bucket -> azureStorage(azureBlobServiceClient.getBlobContainerClient(bucket), properties);
        }

        @Bean
        @ConditionalOnMissingBean(ObjectStorage.class)
        @Conditional(SingleBucket.class)
        ObjectStorage azureObjectStorage(BlobContainerClient azureBlobContainerClient, StorageProperties properties) {
            return azureStorage(azureBlobContainerClient, properties);
        }

        /** O tipo de SAS segue a autenticação: chave da conta com connection string, senão user delegation. */
        private static ObjectStorage azureStorage(BlobContainerClient container, StorageProperties properties) {
            return properties.azure().connectionString() != null
                    ? AzureBlobObjectStorage.withSharedKey(container)
                    : AzureBlobObjectStorage.withUserDelegation(container);
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

        /** Namespace consultado na criação de cada storage (uma chamada por bucket, na inicialização). */
        @Bean
        BucketStorageFactory ociBucketStorageFactory(com.oracle.bmc.objectstorage.ObjectStorage ociObjectStorageClient,
                                                     StorageProperties properties) {
            return bucket -> {
                String namespace = properties.oci().namespace() != null ? properties.oci().namespace()
                        : ociObjectStorageClient.getNamespace(GetNamespaceRequest.builder().build()).getValue();
                return new OciObjectStorage(ociObjectStorageClient, namespace, bucket);
            };
        }

        @Bean
        @ConditionalOnMissingBean(ObjectStorage.class)
        @Conditional(SingleBucket.class)
        ObjectStorage ociObjectStorage(BucketStorageFactory ociBucketStorageFactory, StorageProperties properties) {
            return ociBucketStorageFactory.create(requireBucket(properties));
        }
    }

    /**
     * Sem SDK nem client: cada bucket vira a subpasta {@code <root>/<bucket>}, criada sob
     * demanda no primeiro {@code put}. Útil para desenvolvimento local e testes de integração
     * sem depender de um provedor de nuvem ou emulador.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(FileSystemObjectStorage.class)
    @ConditionalOnProperty(prefix = PREFIX, name = "provider", havingValue = "filesystem")
    static class FilesystemStorageConfiguration {

        @Bean
        BucketStorageFactory filesystemBucketStorageFactory(StorageProperties properties) {
            Path root = requireFilesystemRoot(properties);
            return bucket -> new FileSystemObjectStorage(root.resolve(bucket));
        }

        @Bean
        @ConditionalOnMissingBean(ObjectStorage.class)
        @Conditional(SingleBucket.class)
        ObjectStorage filesystemObjectStorage(BucketStorageFactory filesystemBucketStorageFactory,
                                              StorageProperties properties) {
            return filesystemBucketStorageFactory.create(requireBucket(properties));
        }
    }

    /**
     * Sem client próprio da aplicação: o starter conecta e autentica o {@link SSHClient} por
     * properties. Cada bucket vira a subpasta {@code <root>/<bucket>} no servidor.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SftpObjectStorage.class)
    @ConditionalOnProperty(prefix = PREFIX, name = "provider", havingValue = "sftp")
    static class SftpStorageConfiguration {

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        SSHClient sftpSshClient(StorageProperties properties) throws IOException {
            StorageProperties.Sftp sftp = properties.sftp();
            if (sftp.host() == null) {
                throw new IllegalStateException("storage.sftp.host é obrigatório quando storage.provider=sftp");
            }
            if (sftp.username() == null) {
                throw new IllegalStateException("storage.sftp.username é obrigatório quando storage.provider=sftp");
            }
            if (sftp.password() == null && sftp.privateKeyPath() == null) {
                throw new IllegalStateException("Defina storage.sftp.password ou storage.sftp.private-key-path");
            }
            requireSftpRoot(properties);   // valida tudo antes de abrir conexão de rede
            SSHClient client = new SSHClient();
            configureHostKeyVerification(client, sftp);
            try {
                client.connect(sftp.host(), sftp.port());
                if (sftp.password() != null) {
                    client.authPassword(sftp.username(), sftp.password());
                } else {
                    client.authPublickey(sftp.username(), sftp.privateKeyPath());
                }
            } catch (IOException | RuntimeException e) {
                closeQuietly(client);
                throw e;
            }
            return client;
        }

        /** Sem {@code known-hosts} nem {@code insecure-trust-all-hosts}, falha cedo em vez de aceitar qualquer host key. */
        private static void configureHostKeyVerification(SSHClient client, StorageProperties.Sftp sftp) throws IOException {
            if (sftp.knownHosts() != null) {
                client.loadKnownHosts(new File(sftp.knownHosts()));
            } else if (sftp.insecureTrustAllHosts()) {
                client.addHostKeyVerifier(new PromiscuousVerifier());
            } else {
                try {
                    client.loadKnownHosts();
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "Nenhum known_hosts encontrado (~/.ssh/known_hosts); defina storage.sftp.known-hosts "
                                    + "ou storage.sftp.insecure-trust-all-hosts=true (não recomendado fora de dev/teste)", e);
                }
            }
        }

        private static void closeQuietly(SSHClient client) {
            try {
                client.close();
            } catch (IOException ignored) {
                // a falha que importa é a original da conexão/autenticação
            }
        }

        @Bean
        BucketStorageFactory sftpBucketStorageFactory(SSHClient sftpSshClient, StorageProperties properties) {
            String root = requireSftpRoot(properties);
            return bucket -> new SftpObjectStorage(sftpSshClient, root + "/" + bucket);
        }

        @Bean
        @ConditionalOnMissingBean(ObjectStorage.class)
        @Conditional(SingleBucket.class)
        ObjectStorage sftpObjectStorage(BucketStorageFactory sftpBucketStorageFactory, StorageProperties properties) {
            return sftpBucketStorageFactory.create(requireBucket(properties));
        }
    }

    /** Cria o {@link ObjectStorage} de um bucket com os clientes do provedor configurado. */
    @FunctionalInterface
    interface BucketStorageFactory {
        ObjectStorage create(String bucket);
    }

    /** O {@code ObjectStorage} único ({@code storage.bucket}) só existe sem {@code storage.buckets}. */
    static class SingleBucket extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return buckets(context.getEnvironment()).isEmpty()
                    ? ConditionOutcome.match("storage.buckets não definido")
                    : ConditionOutcome.noMatch("storage.buckets definido");
        }
    }

    static Map<String, String> buckets(Environment environment) {
        return Binder.get(environment).bind(PREFIX + ".buckets", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
    }

    /**
     * Registra um {@code ObjectStorage} por entrada de {@code storage.buckets}: bean
     * {@code <nome>ObjectStorage} com qualifier {@code <nome>}. Os beans são registrados antes das
     * auto-configurações seguintes, então o health check os enxerga.
     */
    static class BucketsRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware, BeanFactoryAware {

        private Environment environment;
        private BeanFactory beanFactory;

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void setBeanFactory(BeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
            buckets(environment).forEach((name, bucket) -> {
                if (bucket == null || bucket.isBlank()) {
                    throw new IllegalStateException("storage.buckets." + name + " está vazio");
                }
                RootBeanDefinition definition = new RootBeanDefinition(ObjectStorage.class, () -> factory().create(bucket));
                definition.addQualifier(new AutowireCandidateQualifier(Qualifier.class, name));
                registry.registerBeanDefinition(name + "ObjectStorage", definition);
            });
        }

        private BucketStorageFactory factory() {
            BucketStorageFactory factory = beanFactory.getBeanProvider(BucketStorageFactory.class).getIfAvailable();
            if (factory == null) {
                throw new IllegalStateException("storage.buckets exige storage.provider com o adapter no classpath");
            }
            return factory;
        }
    }
}
