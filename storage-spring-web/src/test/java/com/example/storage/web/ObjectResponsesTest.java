package com.example.storage.web;

import com.example.storage.ByteRange;
import com.example.storage.ObjectInfo;
import com.example.storage.ObjectNotFoundException;
import com.example.storage.ObjectStorage;
import com.example.storage.PutOptions;
import com.example.storage.RangeNotSatisfiableException;
import com.example.storage.memory.InMemoryObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObjectResponsesTest {

    private static final byte[] DATA = "0123456789".getBytes(StandardCharsets.US_ASCII);

    private final InMemoryObjectStorage storage = new InMemoryObjectStorage();
    private final StreamLimiter limiter = new StreamLimiter(1);
    private final Attachment attachment = Attachment.of("report-1.csv", "text/csv")
            .withHeader("X-Report-Checksum-Sha256", "abc");

    private ObjectInfo stored() {
        storage.put("reports/r-a1.csv", DATA, PutOptions.of("text/csv"));
        return storage.head("reports/r-a1.csv").orElseThrow();
    }

    private static byte[] body(ResponseEntity<Resource> response) throws IOException {
        try (InputStream in = response.getBody().getInputStream()) {
            return in.readAllBytes();
        }
    }

    @Test
    void semRangeResponde200ComOsCabecalhos() throws IOException {
        ResponseEntity<Resource> response = ObjectResponses.attachment(storage, stored(), null, attachment);

        HttpHeaders headers = response.getHeaders();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("attachment; filename=\"report-1.csv\"", headers.getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals("text/csv", headers.getFirst(HttpHeaders.CONTENT_TYPE));
        assertEquals(10, headers.getContentLength());
        assertEquals("bytes", headers.getFirst(HttpHeaders.ACCEPT_RANGES));
        assertNull(headers.getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals("abc", headers.getFirst("X-Report-Checksum-Sha256"));
        assertEquals(10, response.getBody().contentLength());
        assertEquals("report-1.csv", response.getBody().getFilename());
        assertArrayEquals(DATA, body(response));
    }

    @Test
    void comRangeResponde206ComContentRange() throws IOException {
        ResponseEntity<Resource> response = ObjectResponses.attachment(storage, stored(), "bytes=2-4", attachment);

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 2-4/10", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals(3, response.getHeaders().getContentLength());
        assertArrayEquals("234".getBytes(StandardCharsets.US_ASCII), body(response));
    }

    @Test
    void rangeMalformadoServeOObjetoInteiro() throws IOException {
        ResponseEntity<Resource> response = ObjectResponses.attachment(storage, stored(), "bytes=0-1,5-6", attachment);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(DATA, body(response));
    }

    @Test
    void nomeComAspasEAcentosEhCodificado() {
        ResponseEntity<Resource> comAspas = ObjectResponses.attachment(storage, stored(), null,
                Attachment.of("a\"b.csv", "text/csv"));
        assertEquals("attachment; filename=\"a\\\"b.csv\"",
                comAspas.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));

        ResponseEntity<Resource> comAcento = ObjectResponses.attachment(storage, stored(), null,
                Attachment.of("relatório.csv", "text/csv"));
        String disposition = comAcento.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.contains("filename*=UTF-8''relat%C3%B3rio.csv"), disposition);
    }

    @Test
    void fecharOCorpoSoltaAVaga() throws IOException {
        StreamLimiter.Permit permit = limiter.tryAcquire().orElseThrow();

        ResponseEntity<Resource> response = ObjectResponses.attachment(storage, stored(), null, attachment, permit);
        assertEquals(0, limiter.available(), "a vaga fica com a resposta até o corpo fechar");

        body(response);
        assertEquals(1, limiter.available());
    }

    @Test
    void falhaNaLeituraSoltaAVaga() {
        StreamLimiter.Permit permit = limiter.tryAcquire().orElseThrow();
        ObjectInfo missing = new ObjectInfo("nao-existe", 10, "v", null, null);

        assertThrows(ObjectNotFoundException.class,
                () -> ObjectResponses.attachment(storage, missing, null, attachment, permit));
        assertEquals(1, limiter.available());
    }

    @Test
    void faixaForaDoObjetoLanca416ComOTamanhoESoltaAVaga() {
        StreamLimiter.Permit permit = limiter.tryAcquire().orElseThrow();

        RangeNotSatisfiableException e = assertThrows(RangeNotSatisfiableException.class,
                () -> ObjectResponses.attachment(storage, stored(), "bytes=50-", attachment, permit));

        assertEquals("bytes */10", ObjectResponses.unsatisfiedContentRange(e).orElseThrow());
        assertEquals(1, limiter.available());
    }

    @Test
    void adapterQueNaoSabeOTamanhoRecebeODoHead() {
        ObjectStorage semTamanho = mock(ObjectStorage.class);
        when(semTamanho.read(any(), any(ByteRange.class))).thenThrow(new RangeNotSatisfiableException("416", null));
        ObjectInfo head = new ObjectInfo("k", 42, "v", null, null);

        RangeNotSatisfiableException e = assertThrows(RangeNotSatisfiableException.class,
                () -> ObjectResponses.attachment(semTamanho, head, "bytes=50-", attachment));

        assertEquals("bytes */42", ObjectResponses.unsatisfiedContentRange(e).orElseThrow());
    }

    @Test
    void semTamanhoNaoHaContentRangeDe416() {
        assertTrue(ObjectResponses.unsatisfiedContentRange(new RangeNotSatisfiableException("416", null)).isEmpty());
    }
}
