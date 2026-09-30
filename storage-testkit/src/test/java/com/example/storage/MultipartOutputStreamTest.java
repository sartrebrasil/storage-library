package com.example.storage;

import com.example.storage.memory.InMemoryObjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
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
            public String uploadId() { return "u-1"; }
            public UploadedPart uploadPart(int n, byte[] d, int l) { throw new StorageException("503", null); }
            public void complete(List<UploadedPart> parts) { fail("não deveria concluir"); }
            public void abort() { aborted = true; }
        };
        MultipartOutputStream out = new MultipartOutputStream(failing, config);
        StorageException error = assertThrows(StorageException.class, () -> {
            out.write(new byte[6 * MultipartConfig.MIB]);
            out.commit();
        });
        assertEquals("503", error.getMessage(), "a falha da parte sobe sem embrulho");
    }

    @Test
    void modoSequencialEnviaNaThreadQueEscreveComUmBufferSo() throws IOException {
        Set<byte[]> buffers = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Thread> threads = Collections.newSetFromMap(new IdentityHashMap<>());
        MultipartSession real = storage.initiateMultipart("seq", ObjectMetadata.of("x"));
        MultipartSession recording = new MultipartSession() {
            public String key() { return real.key(); }
            public String uploadId() { return real.uploadId(); }
            public UploadedPart uploadPart(int n, byte[] d, int l) {
                buffers.add(d);
                threads.add(Thread.currentThread());
                return real.uploadPart(n, d, l);
            }
            public void complete(List<UploadedPart> parts) { real.complete(parts); }
            public void abort() { real.abort(); }
        };
        byte[] data = new byte[3 * MultipartConfig.MIN_PART_SIZE + 10];
        new Random(3).nextBytes(data);

        try (MultipartOutputStream out = new MultipartOutputStream(recording,
                MultipartConfig.sequential(MultipartConfig.MIN_PART_SIZE))) {
            out.write(data);
            out.commit();
            assertEquals(4, out.partCount());
        }

        assertArrayEquals(data, storage.get("seq").orElseThrow());
        assertEquals(1, buffers.size());
        assertEquals(Set.of(Thread.currentThread()), threads);
    }

    @Test
    void modoSequencialFalhaNaPropriaEscritaEAbortaNoClose() {
        boolean[] aborted = {false};
        MultipartSession failing = new MultipartSession() {
            public String key() { return "k"; }
            public String uploadId() { return "u-1"; }
            public UploadedPart uploadPart(int n, byte[] d, int l) { throw new StorageException("503", null); }
            public void complete(List<UploadedPart> parts) { fail("não deveria concluir"); }
            public void abort() { aborted[0] = true; }
        };
        StorageException error = assertThrows(StorageException.class, () -> {
            try (MultipartOutputStream out = new MultipartOutputStream(failing,
                    MultipartConfig.sequential(MultipartConfig.MIN_PART_SIZE))) {
                out.write(new byte[MultipartConfig.MIN_PART_SIZE]);
            }
        });
        assertEquals("503", error.getMessage());
        assertTrue(aborted[0]);
    }

    @Test
    void falhaTipadaNaConclusaoSobeComOTipoOriginalEAborta() {
        boolean[] aborted = {false};
        MultipartSession denied = new MultipartSession() {
            public String key() { return "k"; }
            public String uploadId() { return "u-1"; }
            public UploadedPart uploadPart(int n, byte[] d, int l) { return new UploadedPart(n, "e" + n, null); }
            public void complete(List<UploadedPart> parts) { throw new AccessDeniedException("403", null); }
            public void abort() { aborted[0] = true; }
        };

        assertThrows(AccessDeniedException.class, () -> {
            try (MultipartOutputStream out = new MultipartOutputStream(denied, config)) {
                out.write(new byte[10]);
                out.commit();
            }
        });
        assertTrue(aborted[0]);
    }

    @Test
    void falhaDeInterrupcaoContinuaIOException() {
        MultipartOutputStream out = MultipartOutputStream.open(storage, "fim", ObjectMetadata.of("x"), config);
        out.abort();
        assertThrows(IOException.class, () -> out.write(1), "stream finalizado é erro de I/O local");
    }

    @Test
    void nonClosingPermiteGzipFecharAntesDoCommit() throws IOException {
        byte[] text = "linha;valor\n".repeat(1_000).getBytes(StandardCharsets.UTF_8);

        try (MultipartOutputStream out = MultipartOutputStream.open(storage, "r.csv.gz", ObjectMetadata.of("x"), config)) {
            try (GZIPOutputStream gzip = new GZIPOutputStream(out.nonClosing())) {
                gzip.write(text);
            }
            out.commit();
        }

        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(storage.get("r.csv.gz").orElseThrow()))) {
            assertArrayEquals(text, in.readAllBytes());
        }
    }

    @Test
    void escritaAcimaDoLimiteAbortaELancaObjectTooLarge() throws IOException {
        MultipartConfig limited = config.withMaxObjectBytes(10);

        try (MultipartOutputStream out = MultipartOutputStream.open(storage, "grande", ObjectMetadata.of("x"), limited)) {
            out.write(new byte[10]);   // exatamente no limite: aceito
            ObjectTooLargeException error = assertThrows(ObjectTooLargeException.class, () -> out.write(1));
            assertEquals(10, error.limit());
            assertThrows(IOException.class, out::commit);   // já abortado
        }

        assertTrue(storage.get("grande").isEmpty());
        assertEquals(0, storage.activeUploadCount());
    }

    @Test
    void expoeUploadIdDaSessao() {
        try (MultipartOutputStream out = MultipartOutputStream.open(storage, "id", ObjectMetadata.of("x"), config)) {
            assertNotNull(out.uploadId());
        }
    }

    @Test
    void configSequencialDispensaExecutorEParaleloExige() {
        assertDoesNotThrow(() -> MultipartConfig.sequential(MultipartConfig.MIN_PART_SIZE));
        assertThrows(NullPointerException.class, () -> new MultipartConfig(MultipartConfig.MIN_PART_SIZE, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new MultipartConfig(MultipartConfig.MIN_PART_SIZE, -1, null));
        assertThrows(IllegalArgumentException.class, () -> config.withMaxObjectBytes(-2));
    }

    @Test
    void uploadCommitaDevolveOValorECalculaODigestDosBytesEnviados() throws Exception {
        byte[] text = "linha;valor\n".repeat(1_000).getBytes(StandardCharsets.UTF_8);

        MultipartOutputStream.Result<Integer> result = MultipartOutputStream.upload(storage, "r.csv.gz",
                ObjectMetadata.of("x"), config.withDigest("SHA-256"), ObjectBody.gzipped(out -> {
                    out.write(text);
                    out.close();   // o corpo pode fechar o stream recebido
                    return 1_000;
                }));

        byte[] stored = storage.get("r.csv.gz").orElseThrow();
        assertEquals(1_000, result.value());
        assertEquals(stored.length, result.bytesWritten());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stored)), result.digestHex());
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(stored))) {
            assertArrayEquals(text, in.readAllBytes());
        }
    }

    @Test
    void uploadAbortaQuandoOBodyFalha() {
        assertThrows(IllegalStateException.class, () -> MultipartOutputStream.upload(storage, "k",
                ObjectMetadata.of("x"), config, out -> {
                    out.write(new byte[10]);
                    out.close();   // fechar não publica
                    throw new IllegalStateException("falha lendo o banco");
                }));
        assertTrue(storage.get("k").isEmpty());
        assertEquals(0, storage.activeUploadCount());
    }

    @Test
    void digestExigeConfiguracaoECommit() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> config.withDigest("NAO-EXISTE"));
        try (MultipartOutputStream out = MultipartOutputStream.open(storage, "d", ObjectMetadata.of("x"), config)) {
            out.commit();
            assertThrows(IllegalStateException.class, out::digestHex, "sem withDigest");
        }
        try (MultipartOutputStream out = MultipartOutputStream.open(storage, "d", ObjectMetadata.of("x"),
                config.withDigest("SHA-256"))) {
            assertThrows(IllegalStateException.class, out::digestHex, "antes do commit");
        }
    }

    @Test
    void falhaDeParteAbortaNaHoraEBloqueiaNovasEscritas() {
        boolean[] aborted = {false};
        MultipartSession failing = new MultipartSession() {
            public String key() { return "k"; }
            public String uploadId() { return "u-1"; }
            public UploadedPart uploadPart(int n, byte[] d, int l) { throw new StorageException("503", null); }
            public void complete(List<UploadedPart> parts) { fail("não deveria concluir"); }
            public void abort() { aborted[0] = true; }
        };
        MultipartOutputStream out = new MultipartOutputStream(failing,
                MultipartConfig.sequential(MultipartConfig.MIN_PART_SIZE));

        assertThrows(StorageException.class, () -> out.write(new byte[MultipartConfig.MIN_PART_SIZE]));
        assertTrue(aborted[0], "aborta sem esperar o close");
        assertThrows(IOException.class, () -> out.write(1), "nenhuma parte depois da falha");
    }

    @Test
    void uploadGzipAbortaQuandoOCorpoFalha() {
        assertThrows(IllegalStateException.class, () -> MultipartOutputStream.upload(storage, "k.gz",
                ObjectMetadata.of("x"), config, ObjectBody.gzipped(out -> {
                    out.write(new byte[10]);
                    throw new IllegalStateException("falha lendo o banco");
                })));
        assertTrue(storage.get("k.gz").isEmpty());
        assertEquals(0, storage.activeUploadCount());
    }
}
