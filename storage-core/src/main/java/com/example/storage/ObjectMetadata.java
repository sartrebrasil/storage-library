package com.example.storage;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Propriedades gravadas junto com o objeto.
 *
 * <p>As chaves de {@code userMetadata} são restritas a {@code [a-z_][a-z0-9_]*} e os
 * valores a ASCII imprimível: é o que os quatro provedores aceitam sem transformar
 * (o Azure exige identificadores C#, o S3 converte para minúsculas, a OCI prefixa).
 * Validar aqui faz o erro aparecer na criação, não no upload.</p>
 */
public record ObjectMetadata(String contentType,
                             String contentDisposition,
                             Map<String, String> userMetadata) {

    private static final Pattern KEY = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final Pattern VALUE = Pattern.compile("[\\x20-\\x7E]*");

    public ObjectMetadata {
        userMetadata = userMetadata == null ? Map.of() : Map.copyOf(userMetadata);
        userMetadata.forEach((key, value) -> {
            if (!KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("Chave de metadata inválida: '" + key
                        + "' (use [a-z_][a-z0-9_]*)");
            }
            if (!VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("Valor de metadata não-ASCII na chave '" + key + "'");
            }
        });
    }

    public static ObjectMetadata of(String contentType) {
        return new ObjectMetadata(contentType, null, Map.of());
    }

    public static ObjectMetadata empty() {
        return new ObjectMetadata(null, null, Map.of());
    }

    public ObjectMetadata withDownloadName(String fileName) {
        return new ObjectMetadata(contentType,
                "attachment; filename=\"" + fileName + "\"", userMetadata);
    }

    public ObjectMetadata withUserMetadata(Map<String, String> metadata) {
        return new ObjectMetadata(contentType, contentDisposition, metadata);
    }
}
