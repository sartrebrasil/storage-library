package com.example.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class StorageStreamsTest {

    static class SdkFailure extends RuntimeException {
    }

    @Test
    void traduzExcecaoDoSdkLancadaDuranteOConsumo() {
        Stream<Integer> pages = Stream.of(1, 2, 3).map(i -> {
            if (i == 3) {
                throw new SdkFailure();
            }
            return i;
        });

        Stream<Integer> translated = StorageStreams.translating(pages, SdkFailure.class,
                e -> new ObjectNotFoundException("traduzida", e));

        StorageException e = assertThrows(ObjectNotFoundException.class, translated::toList);
        assertInstanceOf(SdkFailure.class, e.getCause());
    }

    @Test
    void mantemOrdemEDeixaOutrasExcecoesPassarem() {
        assertEquals(List.of(1, 2, 3), StorageStreams.translating(Stream.of(1, 2, 3), SdkFailure.class,
                e -> new StorageException("x", e)).toList());

        Stream<Integer> failing = Stream.of(1).map(i -> {
            throw new IllegalStateException();
        });
        assertThrows(IllegalStateException.class,
                () -> StorageStreams.translating(failing, SdkFailure.class, e -> new StorageException("x", e)).toList());
    }

    @Test
    void boundedNaoPassaDoLimiteNemComSkip() throws IOException {
        InputStream bounded = StorageStreams.bounded(new ByteArrayInputStream("0123456789".getBytes()), 4);

        assertEquals(2, bounded.skip(2));
        assertEquals(2, bounded.available());
        assertEquals(2, bounded.skip(10), "skip para no limite");
        assertEquals(-1, bounded.read());
        assertFalse(bounded.markSupported());
    }

    @Test
    void boundedLeSoAFaixa() throws IOException {
        InputStream bounded = StorageStreams.bounded(new ByteArrayInputStream("0123456789".getBytes()), 4);

        assertArrayEquals("0123".getBytes(), bounded.readAllBytes());
    }
}
