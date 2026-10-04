package com.example.storage.gcs;

import com.example.storage.AccessDeniedException;
import com.example.storage.ByteRange;
import com.example.storage.ListEntry;
import com.example.storage.ObjectContent;
import com.example.storage.ObjectInfo;
import com.example.storage.ObjectMetadata;
import com.example.storage.ObjectNotFoundException;
import com.example.storage.ObjectSummary;
import com.example.storage.PreconditionFailedException;
import com.example.storage.PutOptions;
import com.example.storage.RangeNotSatisfiableException;
import com.google.api.gax.paging.Page;
import com.google.cloud.BatchResult;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.CopyWriter;
import com.google.cloud.storage.MultipartUploadClient;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageBatch;
import com.google.cloud.storage.StorageBatchResult;
import com.google.cloud.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class GcsOperationsTest {

    private static final OffsetDateTime UPDATED = OffsetDateTime.of(2026, 9, 27, 12, 0, 0, 0, ZoneOffset.UTC);

    private final Storage client = mock(Storage.class);
    private final GcsObjectStorage storage = new GcsObjectStorage(client, mock(MultipartUploadClient.class), "bucket");

    private static Blob blob(String name, long generation) {
        Blob blob = mock(Blob.class);
        when(blob.getName()).thenReturn(name);
        when(blob.getSize()).thenReturn(5L);
        when(blob.getGeneration()).thenReturn(generation);
        when(blob.getUpdateTimeOffsetDateTime()).thenReturn(UPDATED);
        when(blob.getContentType()).thenReturn("text/plain");
        when(blob.getMetadata()).thenReturn(Map.of("tenant", "t1"));
        return blob;
    }

    @Test
    void putComMetadataDevolveGenerationEAplicaCondicao() throws Exception {
        Blob created = blob("k", 42);
        when(client.createFrom(any(BlobInfo.class), any(InputStream.class), anyInt(), any(Storage.BlobWriteOption[].class)))
                .thenReturn(created);
        var options = PutOptions.of(new ObjectMetadata("text/plain", null, Map.of("tenant", "t1"))).ifVersionMatches("7");

        String version = storage.put("k", new byte[5], options);

        ArgumentCaptor<BlobInfo> info = ArgumentCaptor.forClass(BlobInfo.class);
        ArgumentCaptor<Storage.BlobWriteOption> condition = ArgumentCaptor.forClass(Storage.BlobWriteOption.class);
        ArgumentCaptor<Integer> bufferSize = ArgumentCaptor.forClass(Integer.class);
        verify(client).createFrom(info.capture(), any(InputStream.class), bufferSize.capture(), condition.capture());
        assertEquals("42", version);
        // Sem isto o SDK aloca 15 MiB por put, qualquer que seja o tamanho (ele sobe para o mínimo de 256 KiB).
        assertEquals(5, bufferSize.getValue());
        assertEquals("text/plain", info.getValue().getContentType());
        assertEquals(Map.of("tenant", "t1"), info.getValue().getMetadata());
        assertEquals(Storage.BlobWriteOption.generationMatch(7), condition.getValue());
    }

    @Test
    void putComStreamDeTamanhoDiferenteDoInformadoFalhaSemGravar() throws Exception {
        // Como o SDK: lê o stream até o fim antes de concluir o upload.
        when(client.createFrom(any(BlobInfo.class), any(InputStream.class), anyInt(), any(Storage.BlobWriteOption[].class)))
                .thenAnswer(inv -> {
                    inv.getArgument(1, InputStream.class).readAllBytes();
                    return blob("k", 1);
                });

        assertThrows(com.example.storage.StorageException.class,
                () -> storage.put("k", new ByteArrayInputStream(new byte[3]), 5, PutOptions.of("text/plain")));
        assertThrows(com.example.storage.StorageException.class,
                () -> storage.put("k", new ByteArrayInputStream(new byte[8]), 5, PutOptions.of("text/plain")));
    }

    @Test
    void putIfNotExistsUsaDoesNotExistEMapeia412() throws Exception {
        when(client.createFrom(any(BlobInfo.class), any(InputStream.class), anyInt(), any(Storage.BlobWriteOption[].class)))
                .thenThrow(new StorageException(412, "conditionNotMet"));

        assertThrows(PreconditionFailedException.class,
                () -> storage.put("k", new byte[1], PutOptions.of("text/plain").ifNotExists()));

        ArgumentCaptor<Storage.BlobWriteOption> condition = ArgumentCaptor.forClass(Storage.BlobWriteOption.class);
        verify(client).createFrom(any(BlobInfo.class), any(InputStream.class), anyInt(), condition.capture());
        assertEquals(Storage.BlobWriteOption.doesNotExist(), condition.getValue());
    }

    @Test
    void headMapeiaPropriedadesOuDevolveVazio() {
        Blob found = blob("k", 9);
        when(client.get(BlobId.of("bucket", "k"))).thenReturn(found);

        ObjectInfo info = storage.head("k").orElseThrow();

        assertEquals(new ObjectInfo("k", 5, "9", UPDATED.toInstant(),
                new ObjectMetadata("text/plain", null, Map.of("tenant", "t1"))), info);
        assertTrue(storage.head("outro").isEmpty());
    }

    @Test
    void openFixaGenerationEAplicaFaixa() throws Exception {
        Blob found = blob("k", 9);
        when(found.getSize()).thenReturn(100L);
        ReadChannel reader = mock(ReadChannel.class);
        ReadChannel limited = mock(ReadChannel.class);
        when(client.get(BlobId.of("bucket", "k"))).thenReturn(found);
        when(client.reader(BlobId.of("bucket", "k", 9L))).thenReturn(reader);
        when(reader.limit(15)).thenReturn(limited);

        storage.open("k", ByteRange.of(10, 5)).close();

        verify(reader).seek(10);
        verify(reader).limit(15);
        verify(limited).close();
    }

    @Test
    void readResolveSufixoPeloTamanhoDoBlob() throws Exception {
        Blob found = blob("k", 9);
        when(found.getSize()).thenReturn(100L);
        ReadChannel reader = mock(ReadChannel.class);
        when(client.get(BlobId.of("bucket", "k"))).thenReturn(found);
        when(client.reader(BlobId.of("bucket", "k", 9L))).thenReturn(reader);
        when(reader.limit(100)).thenReturn(reader);

        try (ObjectContent content = storage.read("k", ByteRange.suffix(5))) {
            assertEquals(5, content.contentLength());
            assertEquals(100, content.totalSize());
            assertEquals("bytes 95-99/100", content.contentRange().orElseThrow());
        }
        verify(reader).seek(95);
        verify(reader).limit(100);
    }

    @Test
    void faixaForaDoBlobFalhaAntesDeAbrirOCanal() {
        Blob found = blob("k", 9);   // 5 bytes
        when(client.get(BlobId.of("bucket", "k"))).thenReturn(found);

        assertThrows(RangeNotSatisfiableException.class, () -> storage.open("k", ByteRange.from(5)));
        verify(client, never()).reader(any(BlobId.class));
    }

    @Test
    void checkAccessFalhaComBucketInexistente() {
        when(client.list(eq("bucket"), any(Storage.BlobListOption[].class))).thenThrow(new StorageException(404, "not found"));

        com.example.storage.StorageException e = assertThrows(com.example.storage.StorageException.class, storage::checkAccess);
        assertFalse(e instanceof ObjectNotFoundException);
        assertTrue(e.getMessage().contains("não existe"), e.getMessage());
    }

    @Test
    void openDeInexistenteLancaNotFound() {
        assertThrows(ObjectNotFoundException.class, () -> storage.open("k"));
        verify(client, never()).reader(any(BlobId.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listMapeiaGenerationComoVersao() {
        Page<Blob> page = mock(Page.class);
        List<Blob> blobs = List.of(blob("p/a", 1), blob("p/b", 2));
        when(page.iterateAll()).thenReturn(blobs);
        when(client.list(eq("bucket"), any(Storage.BlobListOption[].class))).thenReturn(page);

        List<ObjectSummary> items = storage.list("p/").toList();

        assertEquals(List.of(new ObjectSummary("p/a", 5, "1", UPDATED.toInstant()),
                new ObjectSummary("p/b", 5, "2", UPDATED.toInstant())), items);
        ArgumentCaptor<Storage.BlobListOption> option = ArgumentCaptor.forClass(Storage.BlobListOption.class);
        verify(client).list(eq("bucket"), option.capture());
        assertEquals(Storage.BlobListOption.prefix("p/"), option.getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listDirectoryUsaCurrentDirectoryEPercorrePaginas() {
        Blob folder = mock(Blob.class);
        when(folder.getName()).thenReturn("p/sub/");
        when(folder.isDirectory()).thenReturn(true);
        Blob folder2 = mock(Blob.class);
        when(folder2.getName()).thenReturn("p/sub2/");
        when(folder2.isDirectory()).thenReturn(true);
        Page<Blob> first = mock(Page.class);
        Page<Blob> second = mock(Page.class);
        Blob file = blob("p/a", 1);
        when(first.getValues()).thenReturn(List.of(folder, file));
        when(first.hasNextPage()).thenReturn(true);
        when(first.getNextPage()).thenReturn(second);
        when(second.getValues()).thenReturn(List.of(folder2));
        when(client.list(eq("bucket"), any(Storage.BlobListOption[].class))).thenReturn(first);

        List<String> keys = storage.listDirectory("p/").map(ListEntry::key).toList();

        assertEquals(List.of("p/a", "p/sub/", "p/sub2/"), keys);
        verify(client).list("bucket", Storage.BlobListOption.prefix("p/"), Storage.BlobListOption.currentDirectory());
    }

    @Test
    @SuppressWarnings("unchecked")
    void falhaEmPaginaSeguinteViraStorageExceptionDaLibrary() {
        Page<Blob> first = mock(Page.class);
        Blob file = blob("p/a", 1);
        when(first.getValues()).thenReturn(List.of(file));
        when(first.hasNextPage()).thenReturn(true);
        when(first.getNextPage()).thenThrow(new StorageException(503, "unavailable"));
        when(client.list(eq("bucket"), any(Storage.BlobListOption[].class))).thenReturn(first);

        var entries = storage.listDirectory("p/");

        assertThrows(com.example.storage.StorageException.class, entries::toList);
    }

    @Test
    void deleteAllEnviaLotesDe100ERegistraFalhaDoLote() {
        List<String> keys = IntStream.range(0, 150).mapToObj(i -> "k" + i).toList();
        FakeBatches batches = new FakeBatches(client);
        batches.failSubmit(2, new StorageException(503, "unavailable"));

        var result = storage.deleteAll(keys);

        assertEquals(List.of(100, 50), batches.sizes);
        assertEquals(50, result.failures().size());
        assertTrue(result.failures().containsKey("k100"));
        assertFalse(result.failures().containsKey("k99"));
    }

    @Test
    void deleteAllRegistraFalhaDeCadaItemEIgnoraInexistente() {
        FakeBatches batches = new FakeBatches(client);
        batches.itemError("negado", new StorageException(403, "forbidden"));
        batches.itemMissing("sumiu");

        var result = storage.deleteAll(List.of("ok", "negado", "sumiu"));

        assertEquals(Set.of("negado"), result.failures().keySet());
        assertInstanceOf(AccessDeniedException.class, result.failures().get("negado"));
    }

    /** Lotes de {@link Storage#batch()}: cada delete devolve sucesso, inexistente ou erro no submit. */
    private static final class FakeBatches {

        final List<Integer> sizes = new ArrayList<>();
        private final Map<String, StorageException> itemErrors = new HashMap<>();
        private final Set<String> missing = new HashSet<>();
        private final Map<Integer, StorageException> submitErrors = new HashMap<>();

        @SuppressWarnings("unchecked")
        FakeBatches(Storage client) {
            when(client.batch()).thenAnswer(newBatch -> {
                StorageBatch batch = mock(StorageBatch.class);
                Map<String, BatchResult.Callback<Boolean, StorageException>> callbacks = new LinkedHashMap<>();
                when(batch.delete(any(BlobId.class))).thenAnswer(delete -> {
                    String key = delete.<BlobId>getArgument(0).getName();
                    StorageBatchResult<Boolean> result = mock(StorageBatchResult.class);
                    doAnswer(notify -> callbacks.put(key, notify.getArgument(0))).when(result).notify(any());
                    return result;
                });
                doAnswer(submit -> {
                    sizes.add(callbacks.size());
                    StorageException failure = submitErrors.get(sizes.size());
                    if (failure != null) {
                        throw failure;
                    }
                    callbacks.forEach((key, callback) -> {
                        if (itemErrors.containsKey(key)) {
                            callback.error(itemErrors.get(key));
                        } else {
                            callback.success(!missing.contains(key));
                        }
                    });
                    return null;
                }).when(batch).submit();
                return batch;
            });
        }

        void itemError(String key, StorageException error) {
            itemErrors.put(key, error);
        }

        void itemMissing(String key) {
            missing.add(key);
        }

        void failSubmit(int batchNumber, StorageException error) {
            submitErrors.put(batchNumber, error);
        }
    }

    @Test
    void copyEsperaResultadoEMapeia404() {
        CopyWriter writer = mock(CopyWriter.class);
        when(client.copy(any())).thenReturn(writer).thenThrow(new StorageException(404, "notFound"));

        storage.copy("a", "b");
        verify(writer).getResult();

        assertThrows(ObjectNotFoundException.class, () -> storage.copy("x", "y"));
    }
}
