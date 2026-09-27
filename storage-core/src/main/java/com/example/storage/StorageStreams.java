package com.example.storage;

import java.util.Comparator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Utilitários para adapters que expõem listagens paginadas como {@link Stream}. */
public final class StorageStreams {

    /** Ordem de {@link ObjectStorage#listDirectory}: objetos e pastas juntos, por nome. */
    public static final Comparator<ListEntry> BY_KEY = Comparator.comparing(ListEntry::key);

    private StorageStreams() {
    }

    /**
     * Traduz exceções do SDK lançadas durante o consumo do stream. Páginas seguintes são
     * buscadas sob demanda, então uma falha pode acontecer bem depois da chamada a {@code list}.
     */
    public static <T, E extends RuntimeException> Stream<T> translating(
            Stream<T> stream, Class<E> sdkException, Function<E, StorageException> translator) {
        Spliterator<T> source = stream.spliterator();
        Spliterator<T> translated = new Spliterator<>() {
            @Override
            public boolean tryAdvance(Consumer<? super T> action) {
                try {
                    return source.tryAdvance(action);
                } catch (RuntimeException e) {
                    throw translate(e, sdkException, translator);
                }
            }

            @Override
            public Spliterator<T> trySplit() {
                return null;   // sequencial: a ordem entre páginas precisa ser preservada
            }

            @Override
            public long estimateSize() {
                return source.estimateSize();
            }

            @Override
            public int characteristics() {
                return source.characteristics() & ~(SIZED | SUBSIZED);
            }
        };
        return StreamSupport.stream(translated, false).onClose(stream::close);
    }

    private static <E extends RuntimeException> RuntimeException translate(
            RuntimeException e, Class<E> sdkException, Function<E, StorageException> translator) {
        return sdkException.isInstance(e) ? translator.apply(sdkException.cast(e)) : e;
    }
}
