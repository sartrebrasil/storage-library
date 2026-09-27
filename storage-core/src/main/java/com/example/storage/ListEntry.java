package com.example.storage;

/** Item de {@link ObjectStorage#listDirectory}: um objeto ou uma "pasta". */
public sealed interface ListEntry permits ObjectSummary, CommonPrefix {

    /** Nome completo do objeto, ou o prefixo da pasta terminado em {@code /}. */
    String key();
}
