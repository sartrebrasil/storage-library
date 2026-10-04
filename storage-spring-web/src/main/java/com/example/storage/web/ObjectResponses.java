package com.example.storage.web;

import com.example.storage.ByteRange;
import com.example.storage.ObjectContent;
import com.example.storage.ObjectInfo;
import com.example.storage.ObjectStorage;
import com.example.storage.RangeNotSatisfiableException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Resposta de download por Spring MVC, lida direto do storage: {@code 200} ou {@code 206} conforme o
 * {@code Range}, com {@code Content-Disposition}, {@code Accept-Ranges}, {@code Content-Range},
 * {@code Content-Length}, {@code ETag} e {@code Last-Modified}. O corpo é o stream do storage, sem cópia
 * em memória.
 *
 * <pre>{@code
 * StreamLimiter.Permit permit = limiter.tryAcquire().orElseThrow(TooManyDownloads::new);
 * return ObjectResponses.attachmentForRequest(storage, head, requestHeaders,
 *         Attachment.of("report-1.csv.gz", "application/gzip"), permit);
 * }</pre>
 *
 * <p>Com os cabeçalhos da requisição, um {@code If-Range} que não corresponde mais ao objeto (versão ou data
 * diferente) serve o objeto inteiro em vez da faixa: é como o navegador retoma um download sem emendar
 * bytes de duas versões.</p>
 */
public final class ObjectResponses {

    private ObjectResponses() {
    }

    /**
     * Como {@link #attachment(ObjectStorage, ObjectInfo, String, Attachment, StreamLimiter.Permit)}, lendo
     * {@code Range} e {@code If-Range} de {@code request}.
     */
    public static ResponseEntity<Resource> attachmentForRequest(ObjectStorage storage, ObjectInfo head,
                                                                HttpHeaders request, Attachment attachment,
                                                                StreamLimiter.Permit permit) {
        String range = request.getFirst(HttpHeaders.RANGE);
        String ifRange = request.getFirst(HttpHeaders.IF_RANGE);
        return attachment(storage, head, ifRange == null || matches(ifRange, head) ? range : null, attachment, permit);
    }

    /** Como {@link #attachment(ObjectStorage, ObjectInfo, String, Attachment, StreamLimiter.Permit)}, sem limite. */
    public static ResponseEntity<Resource> attachment(ObjectStorage storage, ObjectInfo head, String rangeHeader,
                                                      Attachment attachment) {
        return attachment(storage, head, rangeHeader, attachment, null);
    }

    /**
     * Lê {@code head.key()} na faixa pedida e monta a resposta. {@code Range} malformado ou com várias faixas
     * serve o objeto inteiro (RFC 9110 §14.2).
     *
     * <p>{@code permit} passa a ser desta resposta: é solto quando o corpo é fechado (o Spring fecha ao fim da
     * escrita, inclusive se o cliente desconectar) ou já aqui, se a leitura falhar.</p>
     *
     * @param head o {@code head} que quem chama já fez; o tamanho dele completa o {@code 416}
     * @throws RangeNotSatisfiableException faixa fora do objeto, sempre com {@link RangeNotSatisfiableException#totalSize()}
     */
    public static ResponseEntity<Resource> attachment(ObjectStorage storage, ObjectInfo head, String rangeHeader,
                                                      Attachment attachment, StreamLimiter.Permit permit) {
        MediaType contentType;
        try {
            contentType = MediaType.parseMediaType(attachment.contentType());   // antes de abrir o stream
        } catch (RuntimeException e) {
            release(permit);
            throw e;
        }
        ObjectContent content;
        try {
            content = storage.read(head.key(), ByteRange.parseHttpOrAll(rangeHeader));
        } catch (RangeNotSatisfiableException e) {
            release(permit);
            throw e.totalSize().isPresent() ? e : e.withTotalSize(head.size());
        } catch (RuntimeException e) {
            release(permit);
            throw e;
        }

        HttpHeaders headers = new HttpHeaders();
        try {
            headers.setContentDisposition(contentDisposition(attachment.fileName()));
            headers.setContentType(contentType);
            headers.setContentLength(content.contentLength());
            headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
            content.contentRange().ifPresent(value -> headers.set(HttpHeaders.CONTENT_RANGE, value));
            if (head.version() != null) {
                headers.setETag(etag(head.version()));
            }
            if (head.lastModified() != null) {
                headers.setLastModified(head.lastModified());
            }
            attachment.headers().forEach(headers::set);
        } catch (RuntimeException e) {
            closeQuietly(content);
            release(permit);
            throw e;
        }

        InputStream body = permit == null ? content.stream() : new ReleasingInputStream(content.stream(), permit);
        // A classe exata InputStreamResource, não uma subclasse: só para ela o Spring MVC não reaplica o Range da
        // requisição (o que transformaria um Range malformado em 416 e um multi-faixa em 206 multipart) nem tenta
        // calcular o tamanho lendo o stream, então o Content-Length acima vale.
        Resource resource = new InputStreamResource(body);
        return new ResponseEntity<>(resource, headers, content.isPartial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
    }

    /** {@code bytes *&#47;<total>}, o {@code Content-Range} de um {@code 416}; vazio se o tamanho é desconhecido. */
    public static Optional<String> unsatisfiedContentRange(RangeNotSatisfiableException e) {
        return e.totalSize().isPresent() ? Optional.of("bytes */" + e.totalSize().getAsLong()) : Optional.empty();
    }

    /** A versão do storage como ETag forte: S3 e Azure já a devolvem entre aspas; GCS, OCI e os demais, não. */
    private static String etag(String version) {
        return version.startsWith("\"") ? version : "\"" + version + "\"";
    }

    /**
     * {@code If-Range} com ETag compara de forma forte ({@code W/} nunca corresponde); com data, exige a mesma
     * data de modificação, em segundos. Um valor que não dá para ler não corresponde.
     */
    private static boolean matches(String ifRange, ObjectInfo head) {
        String value = ifRange.strip();
        if (value.startsWith("\"") || value.startsWith("W/")) {
            return head.version() != null && value.equals(etag(head.version()));
        }
        if (head.lastModified() == null) {
            return false;
        }
        try {
            Instant date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return date.getEpochSecond() == head.lastModified().getEpochSecond();
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static void closeQuietly(ObjectContent content) {
        try {
            content.close();
        } catch (IOException ignored) {
            // a falha que importa é a original
        }
    }

    /** Nome ASCII sai só em {@code filename="…"}; com acento, também em {@code filename*} (RFC 6266). */
    private static ContentDisposition contentDisposition(String fileName) {
        ContentDisposition.Builder builder = ContentDisposition.attachment();
        boolean ascii = fileName.chars().allMatch(c -> c >= 0x20 && c < 0x7F);
        return (ascii ? builder.filename(fileName) : builder.filename(fileName, StandardCharsets.UTF_8)).build();
    }

    private static void release(StreamLimiter.Permit permit) {
        if (permit != null) {
            permit.close();
        }
    }

    private static final class ReleasingInputStream extends FilterInputStream {

        private final StreamLimiter.Permit permit;

        ReleasingInputStream(InputStream in, StreamLimiter.Permit permit) {
            super(in);
            this.permit = permit;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                permit.close();
            }
        }
    }
}
