package com.example.storage;

import java.time.Instant;

/**
 * Item de {@link ObjectStorage#list}. Não traz content-type nem metadata: nem todos os
 * provedores os devolvem na listagem. Use {@link ObjectStorage#head} quando precisar.
 */
public record ObjectSummary(String key, long size, String version, Instant lastModified) implements ListEntry {
}
