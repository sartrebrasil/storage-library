package com.example.storage.testkit;

import com.example.storage.ByteRange;
import com.example.storage.CommonPrefix;
import com.example.storage.ListEntry;
import com.example.storage.MultipartConfig;
import com.example.storage.MultipartOutputStream;
import com.example.storage.ObjectInfo;
import com.example.storage.ObjectMetadata;
import com.example.storage.ObjectNotFoundException;
import com.example.storage.ObjectStorage;
import com.example.storage.ObjectSummary;
import com.example.storage.PreconditionFailedException;
import com.example.storage.PresignedRequest;
import com.example.storage.PutOptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Comportamento que toda implementação de {@link ObjectStorage} deve ter. Estenda
 * esta classe apontando {@link #storage()} para um backend real ou emulado.
 * Cada instância de teste usa um prefixo único, então o bucket pode ser compartilhado.
 */
public abstract class ObjectStorageContract {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final String prefix = "contract/" + UUID.randomUUID() + "/";

    protected abstract ObjectStorage storage();

    /** {@code false} quando as URLs pré-assinadas não são acessíveis por HTTP (ex.: em memória). */
    protected boolean supportsHttpPresign() {
        return true;
    }

    /** Mais de 1000 objetos exercita a paginação de S3, GCS e OCI. */
    protected int listCount() {
        return 1_010;
    }

    private String key(String name) {
        return prefix + name;
    }

    private static ObjectMetadata sampleMetadata() {
        return new ObjectMetadata("text/plain", "attachment; filename=\"a.txt\"", Map.of("tenant", "t1"));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] read(String key, ByteRange range) {
        try (InputStream in = storage().open(key, range)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void putDevolveVersaoEHeadTrazAsPropriedades() {
        String key = key("props.txt");

        String version = storage().put(key, bytes("hello"), PutOptions.of(sampleMetadata()));

        ObjectInfo info = storage().head(key).orElseThrow();
        assertEquals(key, info.key());
        assertEquals(5, info.size());
        assertEquals(version, info.version());
        assertNotNull(info.lastModified());
        assertEquals("text/plain", info.metadata().contentType());
        assertEquals("attachment; filename=\"a.txt\"", info.metadata().contentDisposition());
        assertEquals(Map.of("tenant", "t1"), info.metadata().userMetadata());
    }

    @Test
    void headDeObjetoInexistenteEhVazio() {
        assertTrue(storage().head(key("nao-existe")).isEmpty());
    }

    @Test
    void openLeTudoOuUmaFaixa() {
        String key = key("range.bin");
        byte[] data = new byte[100];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        storage().put(key, data, PutOptions.of("application/octet-stream"));

        assertArrayEquals(data, read(key, ByteRange.all()));
        assertArrayEquals(Arrays.copyOfRange(data, 10, 15), read(key, ByteRange.of(10, 5)));
        assertArrayEquals(Arrays.copyOfRange(data, 95, 100), read(key, ByteRange.from(95)));
    }

    @Test
    void openDeObjetoInexistenteLancaNotFound() {
        assertThrows(ObjectNotFoundException.class, () -> storage().open(key("nao-existe")).close());
    }

    @Test
    void objetoVazio() {
        String key = key("vazio");

        storage().put(key, new byte[0], PutOptions.of("text/plain"));

        assertEquals(0, storage().head(key).orElseThrow().size());
        assertArrayEquals(new byte[0], read(key, ByteRange.all()));
    }

    @Test
    void deleteEhIdempotente() {
        String key = key("apagar");
        storage().put(key, bytes("x"), PutOptions.of("text/plain"));

        storage().delete(key);

        assertTrue(storage().head(key).isEmpty());
        assertDoesNotThrow(() -> storage().delete(key));
    }

    @Test
    void listFiltraPorPrefixoEmOrdem() {
        for (String name : List.of("list/b", "list/a", "list/c", "outro/x")) {
            storage().put(key(name), bytes(name), PutOptions.of("text/plain"));
        }

        List<ObjectSummary> items = storage().list(key("list/")).toList();

        assertEquals(List.of(key("list/a"), key("list/b"), key("list/c")),
                items.stream().map(ObjectSummary::key).toList());
        assertEquals(6, items.get(0).size());
        assertNotNull(items.get(0).version());
        assertNotNull(items.get(0).lastModified());
    }

    @Test
    void listPercorreTodasAsPaginas() throws Exception {
        int count = listCount();
        try (ExecutorService executor = Executors.newFixedThreadPool(16)) {
            var futures = IntStream.range(0, count)
                    .mapToObj(i -> executor.submit(() ->
                            storage().put(key("many/%05d".formatted(i)), new byte[1], PutOptions.of("text/plain"))))
                    .toList();
            for (var future : futures) {
                future.get();
            }
        }

        List<String> keys = storage().list(key("many/")).map(ObjectSummary::key).toList();

        assertEquals(count, keys.size());
        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(null);
        assertEquals(sorted, keys);
    }

    @Test
    void listDirectoryDevolveObjetosDoNivelEPastas() {
        for (String name : List.of("dir/b.txt", "dir/a.txt", "dir/sub1/x", "dir/sub1/y", "dir/sub2/fundo/z",
                "dir/sub1.txt", "dirx/q")) {
            storage().put(key(name), bytes(name), PutOptions.of("text/plain"));
        }

        List<ListEntry> entries = storage().listDirectory(key("dir/")).toList();

        // '.' < '/' em ASCII: "sub1.txt" vem antes da pasta "sub1/"
        assertEquals(List.of(key("dir/a.txt"), key("dir/b.txt"), key("dir/sub1.txt"), key("dir/sub1/"), key("dir/sub2/")),
                entries.stream().map(ListEntry::key).toList());
        assertInstanceOf(ObjectSummary.class, entries.get(0));
        assertEquals(9, ((ObjectSummary) entries.get(0)).size());
        assertInstanceOf(CommonPrefix.class, entries.get(3));
        assertInstanceOf(CommonPrefix.class, entries.get(4));
    }

    @Test
    void listDirectoryPercorreTodasAsPaginasSemRepetirPastas() throws Exception {
        int count = listCount();
        try (ExecutorService executor = Executors.newFixedThreadPool(16)) {
            var futures = IntStream.range(0, count)
                    .mapToObj(i -> executor.submit(() ->
                            storage().put(key("tree/%05d/f".formatted(i)), new byte[1], PutOptions.of("text/plain"))))
                    .toList();
            for (var future : futures) {
                future.get();
            }
        }

        List<String> folders = storage().listDirectory(key("tree/")).map(ListEntry::key).toList();

        assertEquals(IntStream.range(0, count).mapToObj(i -> key("tree/%05d/".formatted(i))).toList(), folders);
    }

    @Test
    void putIfNotExistsSoGravaUmaVez() {
        String key = key("unico");
        storage().put(key, bytes("primeiro"), PutOptions.of("text/plain").ifNotExists());

        assertThrows(PreconditionFailedException.class,
                () -> storage().put(key, bytes("segundo"), PutOptions.of("text/plain").ifNotExists()));
        assertArrayEquals(bytes("primeiro"), read(key, ByteRange.all()));
    }

    @Test
    void putIfVersionMatchesDetectaEscritaConcorrente() {
        String key = key("cas");
        String v1 = storage().put(key, bytes("v1"), PutOptions.of("text/plain"));

        String v2 = storage().put(key, bytes("v2"), PutOptions.of("text/plain").ifVersionMatches(v1));

        assertNotEquals(v1, v2);
        assertThrows(PreconditionFailedException.class,
                () -> storage().put(key, bytes("v3"), PutOptions.of("text/plain").ifVersionMatches(v1)));
        assertEquals(v2, storage().head(key).orElseThrow().version());
    }

    @Test
    void putIfVersionMatchesEmObjetoInexistenteFalha() {
        String key = key("cas-inexistente");
        String version = storage().put(key, bytes("x"), PutOptions.of("text/plain"));
        storage().delete(key);

        assertThrows(PreconditionFailedException.class,
                () -> storage().put(key, bytes("y"), PutOptions.of("text/plain").ifVersionMatches(version)));
    }

    @Test
    void copyPreservaConteudoEMetadata() {
        String source = key("origem.txt");
        String target = key("destino.txt");
        storage().put(source, bytes("conteudo"), PutOptions.of(sampleMetadata()));

        storage().copy(source, target);

        assertArrayEquals(bytes("conteudo"), read(target, ByteRange.all()));
        assertEquals(sampleMetadata(), storage().head(target).orElseThrow().metadata());
        assertTrue(storage().head(source).isPresent());
    }

    @Test
    void copyDeInexistenteLancaNotFound() {
        assertThrows(ObjectNotFoundException.class, () -> storage().copy(key("nao-existe"), key("destino")));
    }

    @Test
    void deleteAllRemoveEIgnoraInexistentes() {
        storage().put(key("lote/a"), bytes("a"), PutOptions.of("text/plain"));
        storage().put(key("lote/b"), bytes("b"), PutOptions.of("text/plain"));

        var result = storage().deleteAll(List.of(key("lote/a"), key("lote/b"), key("lote/nao-existe")));

        assertTrue(result.isSuccess(), () -> result.failures().toString());
        assertEquals(0, storage().list(key("lote/")).count());
    }

    @Test
    void multipartMontaOObjetoCompleto() throws IOException {
        String key = key("multipart.bin");
        byte[] data = new byte[2 * MultipartConfig.MIN_PART_SIZE + 1234];
        new Random(7).nextBytes(data);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
             MultipartOutputStream out = MultipartOutputStream.open(storage(), key, ObjectMetadata.of("application/octet-stream"),
                     new MultipartConfig(MultipartConfig.MIN_PART_SIZE, 2, executor))) {
            out.write(data);
            out.commit();
        }

        assertEquals(data.length, storage().head(key).orElseThrow().size());
        assertArrayEquals(data, read(key, ByteRange.all()));
    }

    @Test
    void presignGetPermiteBaixarSemCredencial() throws Exception {
        assumeTrue(supportsHttpPresign(), "storage sem acesso HTTP");
        String key = key("download.txt");
        storage().put(key, bytes("baixe-me"), PutOptions.of("text/plain"));

        URI url = storage().presignGet(key, Duration.ofMinutes(5));
        HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(url).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode(), () -> new String(response.body(), StandardCharsets.UTF_8));
        assertArrayEquals(bytes("baixe-me"), response.body());
    }

    @Test
    void presignPutPermiteEnviarSemCredencial() throws Exception {
        assumeTrue(supportsHttpPresign(), "storage sem acesso HTTP");
        String key = key("upload.txt");

        PresignedRequest presigned = storage().presignPut(key, Duration.ofMinutes(5), PutOptions.of(sampleMetadata()));
        HttpRequest.Builder request = HttpRequest.newBuilder(presigned.url())
                .method(presigned.method(), HttpRequest.BodyPublishers.ofByteArray(bytes("enviado")));
        presigned.headers().forEach(request::header);
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());

        assertTrue(response.statusCode() / 100 == 2, () -> response.statusCode() + ": " + response.body());
        assertArrayEquals(bytes("enviado"), read(key, ByteRange.all()));
        ObjectInfo info = storage().head(key).orElseThrow();
        assertEquals("text/plain", info.metadata().contentType());
        assertEquals(Map.of("tenant", "t1"), info.metadata().userMetadata());
    }
}
