package com.example.storage.memory;

import com.example.storage.MultipartSession;
import com.example.storage.ObjectMetadata;
import com.example.storage.PutOptions;
import com.example.storage.StorageException;
import com.example.storage.UploadedPart;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryObjectStorageTest {

    private final InMemoryObjectStorage storage = new InMemoryObjectStorage();

    @Test
    void completeComParteNaoEnviadaFalhaComStorageException() {
        MultipartSession session = storage.initiateMultipart("k", ObjectMetadata.empty());

        assertThrows(StorageException.class, () -> session.complete(List.of(new UploadedPart(1, "etag-1", null))));
        assertTrue(storage.head("k").isEmpty());
    }

    @Test
    void sessaoAbortadaOuConcluidaNaoAceitaMaisNada() {
        MultipartSession aborted = storage.initiateMultipart("a", ObjectMetadata.empty());
        UploadedPart part = aborted.uploadPart(1, new byte[1], 1);
        aborted.abort();

        assertThrows(StorageException.class, () -> aborted.uploadPart(2, new byte[1], 1));
        assertThrows(StorageException.class, () -> aborted.complete(List.of(part)));
        assertTrue(storage.head("a").isEmpty());

        MultipartSession done = storage.initiateMultipart("b", ObjectMetadata.empty());
        done.complete(List.of(done.uploadPart(1, new byte[1], 1)));
        assertThrows(StorageException.class, () -> done.complete(List.of(new UploadedPart(1, "etag-1", null))));
    }

    @Test
    void falhaAoLerOConteudoViraStorageException() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("origem caiu");
            }
        };

        assertThrows(StorageException.class, () -> storage.put("k", broken, 1, PutOptions.of("text/plain")));
    }
}
