package com.example.storage;

import com.example.storage.memory.InMemoryObjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class MultipartOutputStreamTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final InMemoryObjectStorage storage = new InMemoryObjectStorage();
    private final MultipartConfig config = new MultipartConfig(MultipartConfig.MIN_PART_SIZE, 2, executor);

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void escreveEmPartesEReconstroiOConteudo() throws IOException {
        byte[] data = new byte[12 * MultipartConfig.MIB + 123];
        new Random(42).nextBytes(data);

        try (MultipartOutputStream out = MultipartOutputStream.open(storage, "k", ObjectMetadata.of("x"), config)) {
            for (int off = 0; off < data.length; off += 7_777) {   // escritas de tamanho "torto"
                out.write(data, off, Math.min(7_777, data.length - off));
            }
            out.commit();
            assertEquals(3, out.partCount());
        }

        assertArrayEquals(data, storage.get("k").orElseThrow());
        assertEquals(0, storage.activeUploadCount());
    }

    @Test
    void relatorioVazioGeraObjetoVazio() throws IOException {
        try (MultipartOutputStream out = MultipartOutputStream.open(storage, "vazio", ObjectMetadata.of("x"), config)) {
            out.commit();
        }
        assertEquals(0, storage.get("vazio").orElseThrow().length);
    }

    @Test
    void erroNoProdutorAbortaENaoPublica() {
        assertThrows(IllegalStateException.class, () -> {
            try (MultipartOutputStream out = MultipartOutputStream.open(storage, "k", ObjectMetadata.of("x"), config)) {
                out.write(new byte[11 * MultipartConfig.MIB]);
                throw new IllegalStateException("falha lendo o banco");
            }
        });
        assertTrue(storage.get("k").isEmpty());
        assertEquals(0, storage.activeUploadCount());
    }

    @Test
    void falhaNoUploadDeParteAbortaNoCommit() {
        MultipartSession failing = new MultipartSession() {
            boolean aborted;
            public String key() { return "k"; }
            public UploadedPart uploadPart(int n, byte[] d, int l) { throw new StorageException("503", null); }
            public void complete(List<UploadedPart> parts) { fail("não deveria concluir"); }
            public void abort() { aborted = true; }
        };
        MultipartOutputStream out = new MultipartOutputStream(failing, config);
        assertThrows(IOException.class, () -> {
            out.write(new byte[6 * MultipartConfig.MIB]);
            out.commit();
        });
    }
}
