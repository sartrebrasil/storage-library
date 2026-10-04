package com.example.storage.spring;

import com.example.storage.ObjectStorage;
import com.example.storage.PutOptions;
import com.example.storage.sftp.SftpObjectStorage;
import com.example.storage.sftp.SftpConnection;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Único cenário do provider sftp que toca a rede de fato: conecta e autentica contra um
 * servidor OpenSSH real (imagem {@code atmoz/sftp}). Pulado sem Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class SftpStorageAutoConfigurationTest {

    // atmoz/sftp sem tags de versão: fixado por digest para o build não mudar sozinho.
    @Container
    private static final GenericContainer<?> SFTP = new GenericContainer<>("atmoz/sftp@sha256:0960390462a4441dbb63698d7c185b76a41ffcee7b78ff4adf275f3e66f9c475")
            .withCommand("user:pass:::upload")
            .withExposedPorts(22)
            .waitingFor(Wait.forListeningPort());

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class));

    @Test
    void conectaAutenticaEGravaViaBeanCriadoPeloStarter() {
        runner.withPropertyValues("storage.provider=sftp", "storage.bucket=reports",
                "storage.sftp.root=/upload", "storage.sftp.host=" + SFTP.getHost(),
                "storage.sftp.port=" + SFTP.getMappedPort(22), "storage.sftp.username=user",
                "storage.sftp.password=pass", "storage.sftp.insecure-trust-all-hosts=true").run(context -> {
            assertThat(context).hasSingleBean(SftpConnection.class);
            ObjectStorage storage = context.getBean(ObjectStorage.class);
            assertThat(storage).isInstanceOf(SftpObjectStorage.class);

            storage.put("a.txt", "conteudo".getBytes(StandardCharsets.UTF_8), PutOptions.of("text/plain"));

            assertThat(new String(storage.open("a.txt").readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("conteudo");
        });
    }
}
