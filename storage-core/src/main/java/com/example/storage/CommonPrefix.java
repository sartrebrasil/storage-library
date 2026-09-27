package com.example.storage;

/**
 * "Pasta" em {@link ObjectStorage#listDirectory}: prefixo comum a um ou mais objetos,
 * terminado em {@code /} (ex.: {@code relatorios/2026/}). Não existe como objeto no storage.
 */
public record CommonPrefix(String key) implements ListEntry {
}
