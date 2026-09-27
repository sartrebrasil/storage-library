package com.example.storage;

import org.junit.jupiter.api.Test;

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
}
