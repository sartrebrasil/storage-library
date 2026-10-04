package com.example.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Utilitários para adapters: listagens paginadas como {@link Stream} e leitura limitada a uma faixa. */
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

    /**
     * Lê no máximo {@code limit} bytes de {@code in}, sem carregar a faixa em memória. {@code skip} e
     * {@code available} também respeitam o limite; {@code mark}/{@code reset} não são suportados.
     */
    public static InputStream bounded(InputStream in, long limit) {
        return new FilterInputStream(in) {
            private long remaining = limit;

            @Override
            public int read() throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int b = in.read();
                if (b >= 0) {
                    remaining--;
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int n = in.read(b, off, (int) Math.min(len, remaining));
                if (n > 0) {
                    remaining -= n;
                }
                return n;
            }

            @Override
            public long skip(long n) throws IOException {
                long skipped = in.skip(Math.min(n, remaining));
                remaining -= skipped;
                return skipped;
            }

            @Override
            public int available() throws IOException {
                return (int) Math.min(in.available(), remaining);
            }

            @Override
            public boolean markSupported() {
                return false;
            }

            @Override
            public synchronized void mark(int readlimit) {
            }

            @Override
            public synchronized void reset() throws IOException {
                throw new IOException("mark/reset não suportado");
            }
        };
    }
}
