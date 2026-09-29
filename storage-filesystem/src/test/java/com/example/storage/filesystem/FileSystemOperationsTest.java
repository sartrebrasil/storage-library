package com.example.storage.filesystem;

import com.example.storage.ObjectInfo;
import com.example.storage.ObjectStorage;
import com.example.storage.PutOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FileSystemOperationsTest {

    @TempDir
    Path root;

    private ObjectStorage storage;

    @BeforeEach
    void setUp() {
        storage = new FileSystemObjectStorage(root);
    }

    @Test
    void chaveComPathTraversalEhRejeitada() {
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("../fora.txt", "x".getBytes(StandardCharsets.UTF_8), PutOptions.of("text/plain")));
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("a/../../fora.txt", new byte[0], PutOptions.of("text/plain")));
    }

    @Test
    void chaveNoPrefixoReservadoEhRejeitada() {
        assertThrows(IllegalArgumentException.class,
                () -> storage.put(".uploads/x", new byte[0], PutOptions.of("text/plain")));
    }

    @Test
    void arquivoCriadoPorForaDaApiAindaEhLegivel() throws IOException {
        Path external = root.resolve("solto.txt");
        Files.writeString(external, "conteudo externo", StandardCharsets.UTF_8);

        ObjectInfo info = storage.head("solto.txt").orElseThrow();

        assertEquals(16, info.size());
        assertNotNull(info.version());
        assertNull(info.metadata().contentType());
        assertArrayEquals("conteudo externo".getBytes(StandardCharsets.UTF_8),
                storage.open("solto.txt").readAllBytes());
    }

    @Test
    void sidecarDeMetadataNaoAparaceNaListagem() throws IOException {
        storage.put("a.txt", "x".getBytes(StandardCharsets.UTF_8), PutOptions.of("text/plain"));

        List<String> keys = storage.list("").map(s -> s.key()).toList();

        assertEquals(List.of("a.txt"), keys);
        try (Stream<Path> files = Files.list(root)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().equals("a.txt.objmeta")));
        }
    }

    @Test
    void diretorioDeUploadsMultipartNaoAparaceNaListagem() throws Exception {
        Files.createDirectories(root.resolve(FileSystemObjectStorage.UPLOADS_DIR).resolve("algum-upload"));
        Files.writeString(root.resolve(FileSystemObjectStorage.UPLOADS_DIR).resolve("algum-upload").resolve("part-1"),
                "resto de upload abortado");
        storage.put("visivel.txt", new byte[0], PutOptions.of("text/plain"));

        List<String> keys = storage.list("").map(s -> s.key()).toList();

        assertEquals(List.of("visivel.txt"), keys);
    }

    @Test
    void presignGetDevolveUriDoArquivoReal() {
        storage.put("baixar.txt", "dados".getBytes(StandardCharsets.UTF_8), PutOptions.of("text/plain"));

        var url = storage.presignGet("baixar.txt", java.time.Duration.ofMinutes(5));

        assertEquals(root.resolve("baixar.txt").toUri(), url);
    }
}
