package com.example.storage.sftp;

import com.example.storage.MultipartSession;
import com.example.storage.ObjectInfo;
import com.example.storage.ObjectMetadata;
import com.example.storage.ObjectStorage;
import com.example.storage.PreconditionFailedException;
import com.example.storage.PutOptions;
import com.example.storage.StorageException;
import com.example.storage.UploadedPart;
import com.example.storage.testkit.ObjectStorageContract;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void copySobreSiMesmoPreservaConteudo() throws IOException {
        String key = "copia-propria/" + UUID.randomUUID();
        storage.put(key, "conteudo".getBytes(StandardCharsets.UTF_8), PutOptions.of("text/plain"));

        storage.copy(key, key);

        try (InputStream in = storage.open(key)) {
            assertEquals("conteudo", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals("text/plain", storage.head(key).orElseThrow().metadata().contentType());
    }

    @Test
    void versaoDoHeadServeParaEscritaCondicionalEmArquivoCriadoPorFora() throws IOException {
        String key = "externo/" + UUID.randomUUID() + ".txt";
        writeRaw("/upload/" + key, "externo");
        String version = storage.head(key).orElseThrow().version();

        assertDoesNotThrow(() -> storage.put(key, "novo".getBytes(StandardCharsets.UTF_8),
                PutOptions.of("text/plain").ifVersionMatches(version)));
    }

    @Test
    void sidecarQueNaoDescreveMaisOArquivoEhIgnorado() throws IOException {
        String key = "trocado/" + UUID.randomUUID() + ".txt";
        String antiga = storage.put(key, "v1".getBytes(StandardCharsets.UTF_8),
                PutOptions.of(new ObjectMetadata("text/plain", null, Map.of("tenant", "t1"))));
        writeRaw("/upload/" + key, "conteudo trocado por fora");

        ObjectInfo info = storage.head(key).orElseThrow();

        assertNotEquals(antiga, info.version());
        assertEquals(ObjectMetadata.empty(), info.metadata());
        assertThrows(PreconditionFailedException.class, () -> storage.put(key, new byte[1],
                PutOptions.of("text/plain").ifVersionMatches(antiga)));
    }

    @Test
    void falhaNoStreamDeEntradaNaoDeixaTemporario() throws IOException {
        String dir = "falha/" + UUID.randomUUID();
        InputStream broken = new InputStream() {
            private int sent;

            @Override
            public int read() throws IOException {
                if (sent++ < 10) {
                    return 'x';
                }
                throw new IOException("origem caiu");
            }
        };

        assertThrows(StorageException.class, () -> storage.put(dir + "/a.txt", broken, 100, PutOptions.of("text/plain")));

        try (SFTPClient sftp = sshClient.newSFTPClient()) {
            assertEquals(List.of(), sftp.ls("/upload/" + dir).stream().map(RemoteResourceInfo::getName).toList());
        }
    }

    @Test
    void partesEmParaleloCriamOMesmoDiretorioDeUploadSemFalhar() throws Exception {
        // Cada parte cria .uploads/<id>; antes, duas criando ao mesmo tempo faziam uma falhar no mkdir.
        for (int round = 0; round < 5; round++) {
            MultipartSession session = storage.initiateMultipart("paralelo/" + UUID.randomUUID(), ObjectMetadata.empty());
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<UploadedPart>> parts = IntStream.rangeClosed(1, 8)
                        .mapToObj(n -> executor.submit(() -> session.uploadPart(n, new byte[1], 1)))
                        .toList();
                for (Future<UploadedPart> part : parts) {
                    part.get();
                }
            } finally {
                session.abort();
            }
        }
    }

    private static void writeRaw(String path, String content) throws IOException {
        try (SFTPClient sftp = sshClient.newSFTPClient()) {
            SftpObjectStorage.mkdirs(sftp, SftpObjectStorage.parentOf(path));
            try (RemoteFile file = sftp.open(path, Set.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC));
                 OutputStream out = file.new RemoteFileOutputStream()) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    @Override
    protected int listCount() {
        // sem paginação nativa para exercitar; cada put é vários round-trips SFTP então um
        // número menor já cobre a ordenação sem deixar o teste lento
        return 40;
    }
}
