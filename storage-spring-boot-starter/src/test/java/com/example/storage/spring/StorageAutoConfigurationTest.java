package com.example.storage.spring;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.example.storage.ObjectStorage;
import com.example.storage.azure.AzureBlobObjectStorage;
import com.example.storage.filesystem.FileSystemObjectStorage;
import com.example.storage.gcp.GcsObjectStorage;
import com.example.storage.memory.InMemoryObjectStorage;
import com.example.storage.oci.OciObjectStorage;
import com.example.storage.aws.S3ObjectStorage;
import com.example.storage.sftp.SftpConnection;
import com.example.storage.sftp.SftpObjectStorage;
import com.example.storage.spring.exemplos.AzureClientsExample;
import com.example.storage.spring.exemplos.GcsClientsExample;
import com.example.storage.spring.exemplos.OciClientsExample;
import com.example.storage.spring.exemplos.S3ClientsExample;
import com.google.cloud.NoCredentials;
import com.google.cloud.storage.HttpStorageOptions;
import com.google.cloud.storage.MultipartUploadClient;
import com.google.cloud.storage.Storage;
import com.oracle.bmc.objectstorage.responses.GetNamespaceResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Nenhum cenário acessa a rede: os clientes são só construídos. */
class StorageAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class));

    @TempDir
    Path tempDir;

    private static final String[] MINIO = {"storage.provider=s3", "storage.bucket=reports"};

    /** O S3 com os clientes que a aplicação declararia (exemplo do emulador: MinIO em localhost:9000, path-style). */
    private ApplicationContextRunner s3() {
        return runner.withUserConfiguration(S3ClientsExample.Emulador.class).withPropertyValues(MINIO);
    }

    @Test
    void semProviderNaoCriaStorage() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ObjectStorage.class));
    }

    @Test
    void providerSemBucketFalhaNaInicializacao() {
        runner.withPropertyValues("storage.provider=s3", "storage.s3.region=us-east-1").run(context ->
                assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "storage.bucket é obrigatório quando storage.provider está definido"));
    }

    @Test
    void bucketsCriaUmStoragePorEntradaComQualifier() {
        s3().withPropertyValues("storage.bucket=",
                        "storage.buckets.reports=oobj-reports", "storage.buckets.artifacts=oobj-artifacts")
                .withUserConfiguration(QualifiedConsumer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean("s3ObjectStorage");
                    assertThat(context.getBeansOfType(ObjectStorage.class))
                            .containsOnlyKeys("reportsObjectStorage", "artifactsObjectStorage");
                    assertThat(context.getBean(QualifiedConsumer.class).artifacts.presignGet("r.csv", Duration.ofMinutes(5))
                            .toString()).startsWith("http://localhost:9000/oobj-artifacts/r.csv?");
                });
    }

    static class QualifiedConsumer {
        final ObjectStorage artifacts;

        QualifiedConsumer(@org.springframework.beans.factory.annotation.Qualifier("artifacts") ObjectStorage artifacts) {
            this.artifacts = artifacts;
        }
    }

    @Test
    void bucketEBucketsJuntosFalham() {
        s3().withPropertyValues("storage.buckets.reports=oobj-reports").run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage("Defina storage.bucket ou storage.buckets, não os dois"));
    }

    @Test
    void bucketsSemProviderFalha() {
        runner.withPropertyValues("storage.buckets.reports=oobj-reports").run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage("storage.buckets exige storage.provider com o adapter no classpath"));
    }

    @Test
    void s3UsaClienteEPresignerDaAplicacao() {
        s3().run(context -> {
            assertThat(context).hasSingleBean(S3Client.class).hasSingleBean(S3Presigner.class);
            ObjectStorage storage = context.getBean(ObjectStorage.class);
            assertThat(storage).isInstanceOf(S3ObjectStorage.class);
            // endpoint e path-style do presigner da aplicação (assinatura local, sem rede)
            assertThat(storage.presignGet("r.csv", Duration.ofMinutes(5)).toString())
                    .startsWith("http://localhost:9000/reports/r.csv?");
        });
    }

    @Test
    void s3SemClientesFalhaComAMensagemDoProvedor() {
        runner.withPropertyValues(MINIO).run(context -> assertThat(context).hasFailed().getFailure()
                .hasRootCauseMessage("storage.provider=s3: declare um bean software.amazon.awssdk.services.s3.S3Client"));
    }

    @Test
    void s3ComDoisClientesSemPrimaryFalha() {
        s3().withBean("outroS3Client", S3Client.class, () -> mock(S3Client.class))
                .run(context -> assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "storage.provider=s3: há mais de um bean software.amazon.awssdk.services.s3.S3Client; "
                                + "marque um com @Primary"));
    }

    @Test
    void s3ComPrimaryUsaOPrimario() {
        S3Client primary = mock(S3Client.class);
        s3().withBean("outroS3Client", S3Client.class, () -> primary, definition -> definition.setPrimary(true))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ObjectStorage.class)).hasFieldOrPropertyWithValue("s3", primary);
                });
    }

    @Test
    void objectStorageDaAplicacaoNaoExigeClientes() {
        runner.withPropertyValues(MINIO).withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void providerVazioNaoCriaClienteNenhum() {
        java.util.concurrent.atomic.AtomicInteger created = new java.util.concurrent.atomic.AtomicInteger();
        runner.withBean("s3Client", S3Client.class, () -> {
                    created.incrementAndGet();
                    return mock(S3Client.class);
                }, definition -> definition.setLazyInit(true))
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(ObjectStorage.class);
                    assertThat(created).hasValue(0);
                });
    }

    @Test
    void s3ChecksumAceitaNoneERejeitaValorDesconhecido() {
        s3().withPropertyValues("storage.s3.checksum=none")
                .run(context -> assertThat(context).hasSingleBean(S3ObjectStorage.class));
        s3().withPropertyValues("storage.s3.checksum=md5")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void providerAceitaMaiusculas() {
        s3().withPropertyValues("storage.provider=S3")
                .run(context -> assertThat(context).hasSingleBean(S3ObjectStorage.class));
    }

    @Test
    void semAdapterNoClasspathNaoCriaStorage() {
        s3().withClassLoader(new FilteredClassLoader(S3ObjectStorage.class))
                .run(context -> assertThat(context).doesNotHaveBean(ObjectStorage.class));
    }

    @Test
    void storageDaAplicacaoDesligaAAutoConfiguracao() {
        s3().withBean(ObjectStorage.class, InMemoryObjectStorage::new).run(context -> {
            assertThat(context).hasSingleBean(ObjectStorage.class);
            assertThat(context.getBean(ObjectStorage.class)).isInstanceOf(InMemoryObjectStorage.class);
        });
    }

    /** O Azure com o {@code BlobServiceClient} que a aplicação declararia (exemplo do emulador: Azurite). */
    private ApplicationContextRunner azure() {
        return runner.withUserConfiguration(AzureClientsExample.Emulador.class)
                .withPropertyValues("storage.provider=azure", "storage.bucket=reports");
    }

    @Test
    void azureUsaBlobServiceClientDaAplicacaoComSasPorChaveDaConta() {
        azure().withPropertyValues("storage.azure.sas=shared-key").run(context -> {
            ObjectStorage storage = context.getBean(ObjectStorage.class);
            assertThat(storage).isInstanceOf(AzureBlobObjectStorage.class)
                    .hasFieldOrPropertyWithValue("userDelegation", false);
            // SAS por chave da conta é gerado localmente; user delegation exigiria rede.
            assertThat(storage.presignGet("r.csv", Duration.ofMinutes(5)).toString())
                    .startsWith("http://127.0.0.1:10000/devstoreaccount1/reports/r.csv?").contains("sig=");
        });
    }

    @Test
    void azureComUserDelegationUsaSasPorDelegacao() {
        azure().withPropertyValues("storage.azure.sas=user-delegation").run(context ->
                assertThat(context.getBean(ObjectStorage.class)).isInstanceOf(AzureBlobObjectStorage.class)
                        .hasFieldOrPropertyWithValue("userDelegation", true));
    }

    @Test
    void azureSemSasFalha() {
        azure().run(context -> assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                "storage.azure.sas é obrigatório quando storage.provider=azure (shared-key ou user-delegation)"));
    }

    @Test
    void azureSemSasComObjectStorageDaAplicacaoSobe() {
        azure().withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void azureSemBlobServiceClientFalhaCitandoOContainerClient() {
        runner.withPropertyValues("storage.provider=azure", "storage.bucket=reports", "storage.azure.sas=shared-key")
                .run(context -> assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "storage.provider=azure: declare um bean com.azure.storage.blob.BlobServiceClient; o starter "
                                + "deriva o BlobContainerClient por dentro, e um BlobContainerClient da aplicação não é "
                                + "mais usado"));
    }

    @Test
    void toStringDasPropertiesNaoExpoeSegredos() {
        var s3 = new StorageProperties.S3(null, null, false, "AKIAEXEMPLO", "segredo-s3",
                StorageProperties.S3.Checksum.CRC32, true);
        var azure = new StorageProperties.Azure("AccountName=a;AccountKey=segredo-azure", null, null);
        var sftp = new StorageProperties.Sftp("h", 22, "u", "segredo-sftp", null, "/r", null, false,
                Duration.ofSeconds(30), 8);

        assertThat(s3 + " " + azure + " " + sftp).doesNotContain("segredo").contains("AKIAEXEMPLO", "****");
    }

    @Test
    void azureVariosContainersDerivamDoServiceClient() {
        runner.withPropertyValues("storage.provider=azure")
                .withUserConfiguration(AzureClientsExample.Emulador.class, AzureMultiContainer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(BlobContainerClient.class);
                    ObjectStorage artifacts = context.getBean("artifactsStorage", ObjectStorage.class);
                    assertThat(artifacts.presignGet("r.csv", Duration.ofMinutes(5)).toString())
                            .startsWith("http://127.0.0.1:10000/devstoreaccount1/artifacts/r.csv?");
                });
    }

    /** Sem {@code storage.bucket}: um {@code ObjectStorage} por container, todos da mesma conta. */
    @Configuration(proxyBeanMethods = false)
    static class AzureMultiContainer {
        @Bean
        ObjectStorage reportsStorage(BlobServiceClient service) {
            return AzureBlobObjectStorage.withSharedKey(service.getBlobContainerClient("reports"));
        }

        @Bean
        ObjectStorage artifactsStorage(BlobServiceClient service) {
            return AzureBlobObjectStorage.withSharedKey(service.getBlobContainerClient("artifacts"));
        }
    }

    @Test
    void gcsUsaStorageEMultipartDaAplicacao() {
        HttpStorageOptions options = GcsClientsExample.options(NoCredentials.getInstance(), "projeto");

        runner.withPropertyValues("storage.provider=gcs", "storage.bucket=reports")
                .withBean(Storage.class, () -> GcsClientsExample.storage(options))
                .withBean(MultipartUploadClient.class, () -> GcsClientsExample.multipart(options))
                .run(context -> assertThat(context).hasSingleBean(GcsObjectStorage.class));
    }

    @Test
    void gcsSemBeansFalhaComAMensagemDoProvedor() {
        runner.withPropertyValues("storage.provider=gcs", "storage.bucket=reports")
                .run(context -> assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "storage.provider=gcs: declare um bean com.google.cloud.storage.Storage"));
    }

    @Test
    void ociConsultaNamespaceQuandoNaoConfigurado() {
        var client = mock(com.oracle.bmc.objectstorage.ObjectStorage.class);
        when(client.getNamespace(any())).thenReturn(GetNamespaceResponse.builder().value("ns").build());

        runner.withPropertyValues("storage.provider=oci", "storage.bucket=reports")
                .withBean(com.oracle.bmc.objectstorage.ObjectStorage.class, () -> client)
                .run(context -> {
                    assertThat(context).hasSingleBean(OciObjectStorage.class);
                    verify(client).getNamespace(any());
                });
    }

    @Test
    void ociSemClienteFalhaComAMensagemDoProvedor() {
        runner.withPropertyValues("storage.provider=oci", "storage.bucket=reports", "storage.oci.namespace=ns")
                .run(context -> assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "storage.provider=oci: declare um bean com.oracle.bmc.objectstorage.ObjectStorage"));
    }

    @Test
    void ociUsaOClienteMontadoPeloExemplo() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] der = generator.generateKeyPair().getPrivate().getEncoded();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + java.util.Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(der) + "\n-----END PRIVATE KEY-----\n";
        var auth = com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider.builder().tenantId("t").userId("u")
                .fingerprint("aa:bb").region(com.oracle.bmc.Region.SA_SAOPAULO_1)
                .privateKeySupplier(() -> new java.io.ByteArrayInputStream(pem.getBytes())).build();
        var client = OciClientsExample.objectStorageClient(auth);

        runner.withPropertyValues("storage.provider=oci", "storage.bucket=reports", "storage.oci.namespace=ns")
                .withBean(com.oracle.bmc.objectstorage.ObjectStorage.class, () -> client)
                .run(context -> assertThat(context).hasSingleBean(OciObjectStorage.class));
    }

    @Test
    void ociUsaNamespaceDaConfiguracao() {
        var client = mock(com.oracle.bmc.objectstorage.ObjectStorage.class);

        runner.withPropertyValues("storage.provider=oci", "storage.bucket=reports", "storage.oci.namespace=ns")
                .withBean(com.oracle.bmc.objectstorage.ObjectStorage.class, () -> client)
                .run(context -> {
                    assertThat(context).hasSingleBean(OciObjectStorage.class);
                    verify(client, never()).getNamespace(any());
                });
    }

    @Test
    void filesystemCriaStorageNaSubpastaDoRoot() {
        runner.withPropertyValues("storage.provider=filesystem", "storage.bucket=reports",
                "storage.filesystem.root=" + tempDir).run(context -> {
            ObjectStorage storage = context.getBean(ObjectStorage.class);
            assertThat(storage).isInstanceOf(FileSystemObjectStorage.class);

            storage.put("a.txt", "conteudo".getBytes(StandardCharsets.UTF_8), com.example.storage.PutOptions.of("text/plain"));

            assertThat(Files.readString(tempDir.resolve("reports/a.txt"), StandardCharsets.UTF_8)).isEqualTo("conteudo");
        });
    }

    @Test
    void filesystemSemRootFalhaNaInicializacao() {
        runner.withPropertyValues("storage.provider=filesystem", "storage.bucket=reports").run(context ->
                assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "storage.filesystem.root é obrigatório quando storage.provider=filesystem"));
    }

    @Test
    void filesystemBucketsCriaUmaSubpastaPorEntrada() {
        runner.withPropertyValues("storage.provider=filesystem", "storage.filesystem.root=" + tempDir,
                "storage.buckets.reports=oobj-reports", "storage.buckets.artifacts=oobj-artifacts").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean("filesystemObjectStorage");
            assertThat(context.getBeansOfType(ObjectStorage.class))
                    .containsOnlyKeys("reportsObjectStorage", "artifactsObjectStorage");

            context.getBean("artifactsObjectStorage", ObjectStorage.class)
                    .put("a.txt", new byte[0], com.example.storage.PutOptions.of("text/plain"));

            assertThat(Files.exists(tempDir.resolve("oobj-artifacts/a.txt"))).isTrue();
        });
    }

    @Test
    void semAdapterFilesystemNoClasspathNaoCriaStorage() {
        runner.withPropertyValues("storage.provider=filesystem", "storage.bucket=reports",
                        "storage.filesystem.root=" + tempDir)
                .withClassLoader(new FilteredClassLoader(FileSystemObjectStorage.class))
                .run(context -> assertThat(context).doesNotHaveBean(ObjectStorage.class));
    }

    @Test
    void sftpSemConnectionFalhaComAMensagemDoProvedor() {
        runner.withPropertyValues("storage.provider=sftp", "storage.bucket=reports", "storage.sftp.root=/reports")
                .run(context -> assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "storage.provider=sftp: declare um bean com.example.storage.sftp.SftpConnection"));
    }

    @Test
    void sftpUsaConnectionDaAplicacao() {
        SftpConnection connection = mock(SftpConnection.class);
        runner.withPropertyValues("storage.provider=sftp", "storage.bucket=reports", "storage.sftp.root=/reports")
                .withBean(SftpConnection.class, () -> connection)
                .run(context -> assertThat(context).hasSingleBean(SftpObjectStorage.class));
    }

    @Test
    void sftpSemRootFalhaNaInicializacao() {
        runner.withPropertyValues("storage.provider=sftp", "storage.bucket=reports")
                .withBean(SftpConnection.class, () -> mock(SftpConnection.class))
                .run(context -> assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "storage.sftp.root é obrigatório quando storage.provider=sftp"));
    }

    @Test
    void semAdapterSftpNoClasspathNaoCriaStorage() {
        runner.withPropertyValues("storage.provider=sftp", "storage.bucket=reports", "storage.sftp.root=/reports")
                .withClassLoader(new FilteredClassLoader(SftpObjectStorage.class))
                .run(context -> assertThat(context).doesNotHaveBean(ObjectStorage.class));
    }
}
