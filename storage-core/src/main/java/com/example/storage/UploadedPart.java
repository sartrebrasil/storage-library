package com.example.storage;

/**
 * Resultado do envio de uma parte.
 *
 * @param etag     identificador devolvido pelo storage
 * @param checksum checksum opaco da parte (pode ser null se o backend não suportar)
 */
public record UploadedPart(int partNumber, String etag, String checksum) {
}
