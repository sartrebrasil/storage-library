package com.example.storage;

/**
 * Faixa de bytes para leitura parcial.
 *
 * @param length quantidade de bytes; {@code -1} lê até o fim do objeto
 */
public record ByteRange(long offset, long length) {

    private static final ByteRange ALL = new ByteRange(0, -1);

    public ByteRange {
        if (offset < 0) {
            throw new IllegalArgumentException("offset deve ser >= 0");
        }
        if (length < -1 || length == 0) {
            throw new IllegalArgumentException("length deve ser > 0 ou -1 (até o fim)");
        }
    }

    public static ByteRange all() {
        return ALL;
    }

    public static ByteRange from(long offset) {
        return new ByteRange(offset, -1);
    }

    public static ByteRange of(long offset, long length) {
        return new ByteRange(offset, length);
    }

    public boolean isAll() {
        return offset == 0 && length == -1;
    }

    public boolean toEnd() {
        return length == -1;
    }

    /** Último byte (inclusive), para cabeçalhos {@code Range: bytes=offset-last}. */
    public long lastByte() {
        return offset + length - 1;
    }
}
