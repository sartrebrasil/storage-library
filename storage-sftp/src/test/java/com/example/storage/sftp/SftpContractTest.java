package com.example.storage.sftp;

import com.example.storage.ObjectStorage;
import com.example.storage.testkit.ObjectStorageContract;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;

/** Contrato contra um servidor OpenSSH sftp-server real (imagem atmoz/sftp). Pulado sem Docker. */
@Testcontainers(disabledWithoutDocker = true)
class SftpContractTest extends ObjectStorageContract {

    @Container
    private static final GenericContainer<?> SFTP = new GenericContainer<>("atmoz/sftp:latest")
            .withCommand("user:pass:::upload")
            .withExposedPorts(22)
            .waitingFor(Wait.forListeningPort());

    private static SSHClient sshClient;
    private static SftpObjectStorage storage;

    @BeforeAll
    static void setUp() throws IOException {
        sshClient = new SSHClient();
        sshClient.addHostKeyVerifier(new PromiscuousVerifier());
        sshClient.connect(SFTP.getHost(), SFTP.getMappedPort(22));
        // Container em Docker Desktop/Windows é bem mais lento que um servidor real pra E/S de
        // disco; o timeout padrão de expansão de janela SSH estoura em partes de 5 MiB. A janela
        // do canal lê Connection.getTimeoutMs(); Connection é criada no construtor do SSHClient
        // e copia o timeout do Transport naquele momento, então ajustar o Transport depois não
        // alcança essa cópia — precisa setar na Connection direto.
        sshClient.getConnection().setTimeoutMs(120_000);
        sshClient.authPassword("user", "pass");
        storage = new SftpObjectStorage(sshClient, "/upload");
    }

    @AfterAll
    static void tearDown() throws IOException {
        sshClient.close();
    }

    @Override
    protected ObjectStorage storage() {
        return storage;
    }

    @Override
    protected boolean supportsHttpPresign() {
        return false;   // presign devolve sftp:// informativo — não acessível por HttpClient
    }

    @Override
    protected ObjectStorage storageWithMissingBucket() {
        return new SftpObjectStorage(sshClient, "/nao-existe");
    }

    @Override
    protected int listCount() {
        // sem paginação nativa para exercitar; cada put é vários round-trips SFTP então um
        // número menor já cobre a ordenação sem deixar o teste lento
        return 40;
    }
}
