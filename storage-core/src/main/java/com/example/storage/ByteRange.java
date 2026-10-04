package com.example.storage;

import java.util.Objects;

/**
 * Faixa de bytes para leitura parcial.
 *
 * <p>Três formas: fechada ({@link #of}), até o fim ({@link #from}) e sufixo, os últimos N
 * bytes ({@link #suffix}). {@link #resolve} converte qualquer uma delas na faixa fechada
 * que existe num objeto de tamanho conhecido.</p>
 *
 * <p>{@code from(0)} é igual a {@link #all()}: {@code Range: bytes=0-} lê o objeto inteiro,
 * sem resposta parcial, o que o HTTP permite.</p>
 *
 * @param offset primeiro byte; {@code -1} marca um sufixo
 * @param length quantidade de bytes; {@code -1} lê até o fim do objeto
 */
public record ByteRange(long offset, long length) {

    private static final long SUFFIX = -1;
    private static final ByteRange ALL = new ByteRange(0, -1);

    public ByteRange {
        if (offset < SUFFIX) {
            throw new IllegalArgumentException("offset deve ser >= 0");
        }
        if (length < -1 || length == 0) {
            throw new IllegalArgumentException("length deve ser > 0 ou -1 (até o fim)");
        }
        if (offset == SUFFIX && length == -1) {
            throw new IllegalArgumentException("sufixo precisa de length > 0");
        }
        if (offset > 0 && length > Long.MAX_VALUE - offset) {
            // Fim além de Long.MAX_VALUE (ex.: "bytes=5-9223372036854775807"): corta no maior fim
            // representável, para offset + length não estourar em lastByte() e resolve().
            length = Long.MAX_VALUE - offset;
        }
    }

    public static ByteRange all() {
        return ALL;
    }

    public static ByteRange from(long offset) {
        return new ByteRange(requireOffset(offset), -1);
    }

    public static ByteRange of(long offset, long length) {
        return new ByteRange(requireOffset(offset), length);
    }

    /** Os últimos {@code length} bytes; o objeto inteiro se ele for menor. */
    public static ByteRange suffix(long length) {
        if (length <= 0) {
            throw new IllegalArgumentException("length do sufixo deve ser > 0");
        }
        return new ByteRange(SUFFIX, length);
    }

    /**
     * Lê o valor de um cabeçalho HTTP {@code Range} com uma única faixa:
     * {@code bytes=a-b}, {@code bytes=a-} ou {@code bytes=-n}.
     *
     * @throws IllegalArgumentException cabeçalho malformado, unidade diferente de {@code bytes}
     *                                  ou várias faixas (não suportadas)
     */
    public static ByteRange parseHttp(String header) {
        Objects.requireNonNull(header, "header");
        String value = header.strip();
        int equals = value.indexOf('=');
        if (equals < 0 || !value.substring(0, equals).strip().equalsIgnoreCase("bytes")) {
            throw new IllegalArgumentException("Range deve usar a unidade bytes: " + header);
        }
        String spec = value.substring(equals + 1).strip();
        if (spec.indexOf(',') >= 0) {
            throw new IllegalArgumentException("Range com várias faixas não é suportado: " + header);
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            throw new IllegalArgumentException("Range malformado: " + header);
        }
        String first = spec.substring(0, dash).strip();
        String last = spec.substring(dash + 1).strip();
        if (first.isEmpty()) {
            return suffix(number(last, header));
        }
        long start = number(first, header);
        if (last.isEmpty()) {
            return from(start);
        }
        long end = number(last, header);
        if (end < start) {
            throw new IllegalArgumentException("Range com fim antes do início: " + header);
        }
        return of(start, end - start + 1);
    }

    /**
     * Como {@link #parseHttp}, mas o que ele rejeitaria vira {@link #all()}: cabeçalho ausente, malformado,
     * outra unidade ou várias faixas. É o que a RFC 9110 §14.2 manda um servidor fazer com um {@code Range}
     * que não entende: ignorar e responder o objeto inteiro. Faixa fora do objeto não é malformada, e
     * continua falhando na leitura com {@link RangeNotSatisfiableException}.
     */
    public static ByteRange parseHttpOrAll(String header) {
        if (header == null) {
            return ALL;
        }
        try {
            return parseHttp(header);
        } catch (IllegalArgumentException e) {
            return ALL;
        }
    }

    public boolean isAll() {
        return offset == 0 && length == -1;
    }

    public boolean toEnd() {
        return length == -1;
    }

    public boolean isSuffix() {
        return offset == SUFFIX;
    }

    /** Último byte (inclusive) de uma faixa fechada, para {@code Range: bytes=offset-last}. */
    public long lastByte() {
        return offset + length - 1;
    }

    /** Valor do cabeçalho HTTP {@code Range}; não se aplica a {@link #all()}. */
    public String httpValue() {
        if (isAll()) {
            throw new IllegalStateException("ByteRange.all() não tem cabeçalho Range");
        }
        if (isSuffix()) {
            return "bytes=-" + length;
        }
        return "bytes=" + offset + "-" + (toEnd() ? "" : lastByte());
    }

    /**
     * A faixa fechada que esta faixa cobre num objeto de {@code size} bytes. {@link #all()}
     * continua {@code all()}; as demais viram {@link #of}, cortadas no fim do objeto.
     *
     * @throws RangeNotSatisfiableException a faixa começa no fim do objeto ou depois dele
     *                                      (num objeto vazio, qualquer faixa)
     */
    public ByteRange resolve(long size) {
        if (isAll()) {
            return this;
        }
        if (size <= 0 || (!isSuffix() && offset >= size)) {
            throw new RangeNotSatisfiableException(httpValue() + " fora de um objeto de " + size + " bytes", null,
                    Math.max(size, 0));
        }
        if (isSuffix()) {
            long start = Math.max(0, size - length);
            return of(start, size - start);
        }
        long end = toEnd() ? size : Math.min(size, offset + length);
        return of(offset, end - offset);
    }

    private static long requireOffset(long offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset deve ser >= 0");
        }
        return offset;
    }

    private static long number(String digits, String header) {
        if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("Range malformado: " + header);
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Range malformado: " + header, e);
        }
    }
}
