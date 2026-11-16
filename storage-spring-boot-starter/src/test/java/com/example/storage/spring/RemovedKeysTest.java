package com.example.storage.spring;

import com.example.storage.ObjectStorage;
import com.example.storage.memory.InMemoryObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** As chaves de conexão que saíram de {@code storage.*} (design de 2026-11-15) derrubam o startup, sempre. */
class RemovedKeysTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class));

    @Test
    void chaveRemovidaDerrubaOStartupComProviderVazio() {
        runner.withPropertyValues("storage.s3.region=us-east-1")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining(
                        "storage.s3.region foi removida: configure os beans S3Client e S3Presigner na aplicação "
                                + "(veja \"Migrando\" no README)"));
    }

    @Test
    void chaveRemovidaEmVariavelDeAmbiente() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("test-systemEnvironment",
                                Map.of("STORAGE_S3_REGION", "us-east-1"))))
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("storage.s3.region foi removida"));
    }

    @Test
    void todasAsChavesRemovidasNaMesmaMensagem() {
        runner.withPropertyValues("storage.s3.endpoint=http://localhost:9000", "storage.sftp.host=h",
                        "storage.azure.connection-string=UseDevelopmentStorage=true", "storage.gcs.project-id=p",
                        "storage.oci.profile=DEFAULT")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("Configuração inválida de storage.*:")
                        .hasStackTraceContaining("storage.s3.endpoint foi removida")
                        .hasStackTraceContaining("storage.sftp.host foi removida: configure o bean SftpConnection")
                        .hasStackTraceContaining("storage.azure.connection-string foi removida: configure o bean "
                                + "BlobServiceClient")
                        .hasStackTraceContaining("storage.gcs.project-id foi removida: configure os beans Storage e "
                                + "MultipartUploadClient")
                        .hasStackTraceContaining("storage.oci.profile foi removida: configure o bean "
                                + "com.oracle.bmc.objectstorage.ObjectStorage"));
    }

    @Test
    void chavesQueFicamNaoFalham() {
        runner.withPropertyValues("storage.s3.checksum=none", "storage.oci.namespace=ns", "storage.sftp.root=/r",
                        "storage.filesystem.root=/tmp", "storage.azure.sas=shared-key")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void chaveRemovidaComObjectStorageDaAplicacaoFalha() {
        runner.withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .withPropertyValues("storage.provider=s3", "storage.bucket=reports",
                        "storage.s3.endpoint=http://localhost:9000")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("storage.s3.endpoint foi removida"));
    }
}
