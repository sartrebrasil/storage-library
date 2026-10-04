package com.example.storage;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * Conteúdo aberto por {@link ObjectStorage#read}, com o que uma resposta HTTP precisa para
 * repassá-lo: tamanho do corpo, faixa servida e tamanho total do objeto. O chamador deve
 * fechar.
 *
 * @param range     {@link ByteRange#all()} ou a faixa fechada efetivamente servida
 * @param totalSize tamanho do objeto inteiro
 */
public record ObjectContent(InputStream stream, ByteRange range, long totalSize) implements Closeable {

    public ObjectContent {
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(range, "range");
        if (!range.isAll() && (range.isSuffix() || range.toEnd())) {
            throw new IllegalArgumentException("range deve estar resolvida (ByteRange.resolve): " + range);
        }
    }

    /**
     * Monta o conteúdo a partir de uma resposta HTTP de leitura.
     *
     * @param contentLength o {@code Content-Length} da resposta
     * @param contentRange  o {@code Content-Range} da resposta ({@code bytes a-b/total}), ou
     *                      {@code null} quando o objeto veio inteiro
     */
    public static ObjectContent fromHttp(InputStream stream, long contentLength, String contentRange) {
        if (contentRange == null) {
            return new ObjectContent(stream, ByteRange.all(), contentLength);
        }
        String value = contentRange.strip();
        int space = value.indexOf(' ');
        int dash = value.indexOf('-', space);
        int slash = value.indexOf('/', dash);
        long first;
        long last;
        long total;
        try {
            if (space < 0 || dash < 0 || slash < 0) {
                throw new NumberFormatException();
            }
            first = Long.parseLong(value.substring(space + 1, dash).strip());
            last = Long.parseLong(value.substring(dash + 1, slash).strip());
            total = Long.parseLong(value.substring(slash + 1).strip());   // "*" (tamanho desconhecido) também
        } catch (NumberFormatException e) {
            closeQuietly(stream);
            throw new IllegalArgumentException("Content-Range malformado: " + contentRange, e);
        }
        if (last < first || total == 0) {
            // MinIO responde 206 com "bytes 0--1/0" a uma faixa num objeto vazio, em vez de 416
            closeQuietly(stream);
            throw new RangeNotSatisfiableException("Faixa fora do objeto: " + contentRange, null, total);
        }
        return new ObjectContent(stream, ByteRange.of(first, last - first + 1), total);
    }

    /** Tamanho do corpo: a faixa servida, ou o objeto inteiro. */
    public long contentLength() {
        return range.isAll() ? totalSize : range.length();
    }

    /** {@code true} quando a leitura pediu uma faixa: a resposta HTTP é {@code 206}. */
    public boolean isPartial() {
        return !range.isAll();
    }

    /** Valor do cabeçalho {@code Content-Range}, presente só em leituras parciais. */
    public Optional<String> contentRange() {
        return isPartial()
                ? Optional.of("bytes " + range.offset() + "-" + range.lastByte() + "/" + totalSize)
                : Optional.empty();
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // a falha que importa é a da faixa
        }
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }
}
