package com.example.storage.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Como a resposta de download se apresenta: o nome com que o navegador salva, o {@code Content-Type} e
 * cabeçalhos extras do domínio (um checksum, uma contagem de registros).
 */
public record Attachment(String fileName, String contentType, Map<String, String> headers) {

    public Attachment {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(contentType, "contentType");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static Attachment of(String fileName, String contentType) {
        return new Attachment(fileName, contentType, Map.of());
    }

    public Attachment withHeader(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new Attachment(fileName, contentType, copy);
    }
}
