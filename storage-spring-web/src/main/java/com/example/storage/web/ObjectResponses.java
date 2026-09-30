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
import java.util.Optional;

/**
 * Resposta de download por Spring MVC, lida direto do storage: {@code 200} ou {@code 206} conforme o
 * {@code Range}, com {@code Content-Disposition}, {@code Accept-Ranges}, {@code Content-Range} e
 * {@code Content-Length}. O corpo é o stream do storage, sem cópia em memória.
 *
 * <pre>{@code
 * StreamLimiter.Permit permit = limiter.tryAcquire().orElseThrow(TooManyDownloads::new);
 * return ObjectResponses.attachment(storage, head, request.getHeader("Range"),
 *         Attachment.of("report-1.csv.gz", "application/gzip"), permit);
 * }</pre>
 */
public final class ObjectResponses {

    private ObjectResponses() {
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
        headers.setContentDisposition(contentDisposition(attachment.fileName()));
        headers.setContentType(MediaType.parseMediaType(attachment.contentType()));
        headers.setContentLength(content.contentLength());
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        content.contentRange().ifPresent(value -> headers.set(HttpHeaders.CONTENT_RANGE, value));
        attachment.headers().forEach(headers::set);

        InputStream body = permit == null ? content.stream() : new ReleasingInputStream(content.stream(), permit);
        Resource resource = new ObjectContentResource(body, content.contentLength(), attachment.fileName());
        return new ResponseEntity<>(resource, headers, content.isPartial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
    }

    /** {@code bytes *&#47;<total>}, o {@code Content-Range} de um {@code 416}; vazio se o tamanho é desconhecido. */
    public static Optional<String> unsatisfiedContentRange(RangeNotSatisfiableException e) {
        return e.totalSize().isPresent() ? Optional.of("bytes */" + e.totalSize().getAsLong()) : Optional.empty();
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

    /** Tamanho e nome conhecidos: o Spring não tenta ler o stream para descobrir o {@code Content-Length}. */
    private static final class ObjectContentResource extends InputStreamResource {

        private final long contentLength;
        private final String fileName;

        ObjectContentResource(InputStream stream, long contentLength, String fileName) {
            super(stream);
            this.contentLength = contentLength;
            this.fileName = fileName;
        }

        @Override
        public long contentLength() {
            return contentLength;
        }

        @Override
        public String getFilename() {
            return fileName;
        }
    }
}
