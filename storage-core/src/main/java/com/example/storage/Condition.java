package com.example.storage;

import java.util.Objects;

/** Pré-condição de escrita, avaliada pelo storage de forma atômica. */
public sealed interface Condition {

    /** Sem pré-condição: sobrescreve se existir. */
    record None() implements Condition {
    }

    /** Só grava se o objeto ainda não existir. */
    record IfNotExists() implements Condition {
    }

    /** Só grava se a versão atual for {@code version} (obtida de put, head ou list). */
    record IfVersionMatches(String version) implements Condition {
        public IfVersionMatches {
            Objects.requireNonNull(version, "version");
        }
    }

    static Condition none() {
        return new None();
    }

    static Condition ifNotExists() {
        return new IfNotExists();
    }

    static Condition ifVersionMatches(String version) {
        return new IfVersionMatches(version);
    }
}
