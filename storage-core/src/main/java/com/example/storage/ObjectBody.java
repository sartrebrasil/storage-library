package com.example.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

/**
 * Escreve o conteúdo de um objeto e devolve um valor ao chamador (uma contagem de registros, por exemplo).
 * Pode fechar o stream recebido: quem o entrega (como {@link MultipartOutputStream#upload}) não depende disso.
 */
@FunctionalInterface
public interface ObjectBody<T> {

    int GZIP_BUFFER_BYTES = 64 * 1024;

    T writeTo(OutputStream out) throws IOException;

    /**
     * O objeto gravado é o gzip do que {@code body} escreve. Digest e tamanho do upload contam os bytes
     * comprimidos. É o formato do arquivo ({@code .gz}), não {@code Content-Encoding}: não grave esse
     * cabeçalho, ou o navegador descomprime ao baixar.
     */
    static <T> ObjectBody<T> gzipped(ObjectBody<T> body) {
        Objects.requireNonNull(body, "body");
        return out -> {
            try (GZIPOutputStream gzip = new GZIPOutputStream(out, GZIP_BUFFER_BYTES)) {   // close escreve o trailer
                return body.writeTo(gzip);
            }
        };
    }
}
