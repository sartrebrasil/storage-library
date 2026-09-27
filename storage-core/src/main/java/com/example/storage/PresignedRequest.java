package com.example.storage;

import java.net.URI;
import java.util.Map;

/**
 * Requisição pré-assinada para ser executada por quem não tem credenciais.
 *
 * @param headers cabeçalhos que o cliente precisa enviar exatamente como estão.
 *                Parte deles é assinada (S3, GCS) e a requisição falha sem eles; outros
 *                definem propriedades do objeto (content-type, metadata) ou o tipo de blob (Azure).
 */
public record PresignedRequest(String method, URI url, Map<String, String> headers) {

    public PresignedRequest {
        headers = Map.copyOf(headers);
    }
}
