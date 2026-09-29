package com.example.storage.spring;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.example.storage.ObjectStorage;
import com.example.storage.azure.AzureBlobObjectStorage;
import com.example.storage.filesystem.FileSystemObjectStorage;
import com.example.storage.gcs.GcsObjectStorage;
import com.example.storage.memory.InMemoryObjectStorage;
import com.example.storage.oci.OciObjectStorage;
import com.example.storage.s3.S3ObjectStorage;
import com.example.storage.sftp.SftpObjectStorage;
import com.google.cloud.NoCredentials;
import com.google.cloud.storage.HttpStorageOptions;
import com.oracle.bmc.objectstorage.responses.GetNamespaceResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
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

    private static final String[] MINIO = {
            "storage.provider=s3", "storage.bucket=reports",
            "storage.s3.region=us-east-1", "storage.s3.endpoint=http://localhost:9000", "storage.s3.path-style=true",
            "storage.s3.access-key=minioadmin", "storage.s3.secret-key=minioadmin"};

    @Test
    void semProviderNaoCriaStorage() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ObjectStorage.class));
    }

    @Test
    void s3CriaClienteEPresignerComAMesmaConfiguracao() {
        runner.withPropertyValues(MINIO).run(context -> {
            assertThat(context).hasSingleBean(S3Client.class).hasSingleBean(S3Presigner.class);
            ObjectStorage storage = context.getBean(ObjectStorage.class);
            assertThat(storage).isInstanceOf(S3ObjectStorage.class);
            // Endpoint e path-style chegaram ao presigner (assinatura local, sem rede)
            URI url = storage.presignGet("r.csv", Duration.ofMinutes(5));
            assertThat(url.toString()).startsWith("http://localhost:9000/reports/r.csv?");
        });
    }

    @Test
    void providerSemBucketFalhaNaInicializacao() {
        runner.withPropertyValues("storage.provider=s3", "storage.s3.region=us-east-1").run(context ->
                assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "storage.bucket é obrigatório quando storage.provider está definido"));
    }

    @Test
    void bucketsCriaUmStoragePorEntradaComQualifier() {
        runner.withPropertyValues(MINIO).withPropertyValues("storage.bucket=",
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
        runner.withPropertyValues(MINIO).withPropertyValues("storage.buckets.reports=oobj-reports").run(context ->
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
    void s3UsaUmSoProviderDeCredenciaisNoClienteENoPresigner() {
        runner.withPropertyValues(MINIO).run(context -> {
            AwsCredentialsProvider provider = context.getBean(AwsCredentialsProvider.class);
            assertThat(provider).isInstanceOf(StaticCredentialsProvider.class);
            assertThat(context.getBean(S3Client.class).serviceClientConfiguration().credentialsProvider())
                    .isSameAs(provider);
        });
    }

    @Test
    void s3SemAccessKeyUsaCadeiaPadraoComRenovacaoEmFundo() {
        runner.withPropertyValues("storage.provider=s3", "storage.bucket=reports", "storage.s3.region=us-east-1")
                .run(context -> assertThat(context.getBean(AwsCredentialsProvider.class))
                        .isInstanceOf(DefaultCredentialsProvider.class)
                        .hasFieldOrPropertyWithValue("asyncCredentialUpdateEnabled", true));
    }

    @Test
    void s3RenovacaoEmFundoPodeSerDesligada() {
        runner.withPropertyValues("storage.provider=s3", "storage.bucket=reports", "storage.s3.region=us-east-1",
                        "storage.s3.async-credential-update=false")
                .run(context -> assertThat(context.getBean(AwsCredentialsProvider.class))
                        .hasFieldOrPropertyWithValue("asyncCredentialUpdateEnabled", false));
    }

    @Test
    void s3ComChavesVaziasUsaCadeiaPadrao() {
        // o que chega de "access-key: ${REPORT_CREDENTIALS_ACCESS_KEY:}" sem a variável
        runner.withPropertyValues("storage.provider=s3", "storage.bucket=reports", "storage.s3.region=",
                        "storage.s3.access-key=", "storage.s3.secret-key=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AwsCredentialsProvider.class)).isInstanceOf(DefaultCredentialsProvider.class);
                    assertThat(context.getBean(StorageProperties.class).s3().region()).isNull();
                });
    }

    @Test
    void s3ComSoUmaDasChavesFalhaComMensagemClara() {
        runner.withPropertyValues("storage.provider=s3", "storage.bucket=reports", "storage.s3.region=us-east-1",
                        "storage.s3.access-key=minioadmin")
                .run(context -> assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "Defina storage.s3.access-key e storage.s3.secret-key juntas, ou nenhuma das duas"));
    }

    @Test
    void s3ReaproveitaProviderDaAplicacao() {
        AwsCredentialsProvider own = StaticCredentialsProvider.create(AwsBasicCredentials.create("app", "app"));
        runner.withPropertyValues(MINIO).withBean(AwsCredentialsProvider.class, () -> own).run(context ->
                assertThat(context.getBean(S3Client.class).serviceClientConfiguration().credentialsProvider())
                        .isSameAs(own));
    }

    @Test
    void s3ChecksumAceitaNoneERejeitaValorDesconhecido() {
        runner.withPropertyValues(MINIO).withPropertyValues("storage.s3.checksum=none")
                .run(context -> assertThat(context).hasSingleBean(S3ObjectStorage.class));
        runner.withPropertyValues(MINIO).withPropertyValues("storage.s3.checksum=md5")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void providerAceitaMaiusculas() {
        runner.withPropertyValues(MINIO).withPropertyValues("storage.provider=S3")
                .run(context -> assertThat(context).hasSingleBean(S3ObjectStorage.class));
    }

    @Test
    void semAdapterNoClasspathNaoCriaStorage() {
        runner.withPropertyValues(MINIO).withClassLoader(new FilteredClassLoader(S3ObjectStorage.class))
                .run(context -> assertThat(context).doesNotHaveBean(ObjectStorage.class));
    }

    @Test
    void storageDaAplicacaoDesligaAAutoConfiguracao() {
        runner.withPropertyValues(MINIO).withBean(ObjectStorage.class, InMemoryObjectStorage::new).run(context -> {
            assertThat(context).hasSingleBean(ObjectStorage.class);
            assertThat(context.getBean(ObjectStorage.class)).isInstanceOf(InMemoryObjectStorage.class);
        });
    }

    @Test
    void azureComConnectionStringUsaSasPorChaveDaConta() {
        runner.withPropertyValues("storage.provider=azure", "storage.bucket=reports",
                "storage.azure.connection-string=UseDevelopmentStorage=true").run(context -> {
            ObjectStorage storage = context.getBean(ObjectStorage.class);
            assertThat(storage).isInstanceOf(AzureBlobObjectStorage.class);
            // SAS por chave da conta é gerado localmente; user delegation exigiria rede.
            URI url = storage.presignGet("r.csv", Duration.ofMinutes(5));
            assertThat(url.toString()).startsWith("http://127.0.0.1:10000/devstoreaccount1/reports/r.csv?")
                    .contains("sig=");
        });
    }

    @Test
    void azureComEndpointUsaDefaultAzureCredential() {
        runner.withPropertyValues("storage.provider=azure", "storage.bucket=reports",
                        "storage.azure.endpoint=https://conta.blob.core.windows.net")
                .run(context -> assertThat(context).hasSingleBean(AzureBlobObjectStorage.class));
    }

    @Test
    void azureVariosContainersDerivamDoServiceClient() {
        runner.withPropertyValues("storage.provider=azure",
                        "storage.azure.connection-string=UseDevelopmentStorage=true")
                .withUserConfiguration(AzureMultiContainer.class)
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
    void azureSemCredencialFalhaComMensagemClara() {
        runner.withPropertyValues("storage.provider=azure", "storage.bucket=reports").run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage("Defina storage.azure.connection-string ou storage.azure.endpoint"));
    }

    @Test
    void gcsReaproveitaOpcoesDaAplicacao() {
        HttpStorageOptions options = HttpStorageOptions.newBuilder()
                .setProjectId("projeto").setCredentials(NoCredentials.getInstance()).build();

        runner.withPropertyValues("storage.provider=gcs", "storage.bucket=reports")
                .withBean(HttpStorageOptions.class, () -> options)
                .run(context -> assertThat(context).hasSingleBean(GcsObjectStorage.class));
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
    void sftpSemHostFalhaNaInicializacao() {
        runner.withPropertyValues("storage.provider=sftp", "storage.bucket=reports",
                        "storage.sftp.root=/reports", "storage.sftp.username=u", "storage.sftp.password=p")
                .run(context -> assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "storage.sftp.host é obrigatório quando storage.provider=sftp"));
    }

    @Test
    void sftpSemUsernameFalhaNaInicializacao() {
        runner.withPropertyValues("storage.provider=sftp", "storage.bucket=reports", "storage.sftp.root=/reports",
                        "storage.sftp.host=sftp.example.com", "storage.sftp.password=p")
                .run(context -> assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "storage.sftp.username é obrigatório quando storage.provider=sftp"));
    }

    @Test
    void sftpSemPasswordNemChaveFalhaNaInicializacao() {
        runner.withPropertyValues("storage.provider=sftp", "storage.bucket=reports", "storage.sftp.root=/reports",
                        "storage.sftp.host=sftp.example.com", "storage.sftp.username=u",
                        "storage.sftp.insecure-trust-all-hosts=true")
                .run(context -> assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "Defina storage.sftp.password ou storage.sftp.private-key-path"));
    }

    @Test
    void sftpSemRootFalhaNaInicializacaoAntesDeConectar() {
        // host/username/password presentes e insecure ligado: se conectasse à rede pra falhar
        // depois, esse teste ficaria lento/instável. requireSftpRoot roda antes do connect().
        runner.withPropertyValues("storage.provider=sftp", "storage.bucket=reports",
                        "storage.sftp.host=sftp.example.com", "storage.sftp.username=u", "storage.sftp.password=p",
                        "storage.sftp.insecure-trust-all-hosts=true")
                .run(context -> assertThat(context).hasFailed().getFailure().hasRootCauseMessage(
                        "storage.sftp.root é obrigatório quando storage.provider=sftp"));
    }

    @Test
    void semAdapterSftpNoClasspathNaoCriaStorage() {
        runner.withPropertyValues("storage.provider=sftp", "storage.bucket=reports", "storage.sftp.root=/reports",
                        "storage.sftp.host=sftp.example.com", "storage.sftp.username=u", "storage.sftp.password=p",
                        "storage.sftp.insecure-trust-all-hosts=true")
                .withClassLoader(new FilteredClassLoader(SftpObjectStorage.class))
                .run(context -> assertThat(context).doesNotHaveBean(ObjectStorage.class));
    }
}
