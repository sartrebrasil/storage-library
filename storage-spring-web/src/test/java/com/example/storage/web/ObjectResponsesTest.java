package com.example.storage.web;

import com.example.storage.ByteRange;
import com.example.storage.ObjectInfo;
import com.example.storage.ObjectNotFoundException;
import com.example.storage.ObjectStorage;
import com.example.storage.PutOptions;
import com.example.storage.RangeNotSatisfiableException;
import com.example.storage.memory.InMemoryObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    /**
     * O Spring MVC reaplica o {@code Range} da requisição a todo {@code Resource} devolvido com 200, exceto a
     * classe exata {@link InputStreamResource} (AbstractMessageConverterMethodProcessor#isResourceType): uma
     * subclasse faria o Range malformado, multi-faixa ou {@code bytes=0-} virar 416 ou 206 multipart, com o
     * {@code Content-Length} do objeto inteiro.
     */
    @Test
    void corpoEhInputStreamResourceParaOSpringNaoReprocessarORange() {
        for (String range : new String[] {null, "bytes=abc", "bytes=0-1,5-6", "bytes=0-", "bytes=2-4"}) {
            ResponseEntity<Resource> response = ObjectResponses.attachment(storage, stored(), range, attachment);

            assertSame(InputStreamResource.class, response.getBody().getClass(), range);
        }
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
    void contentTypeInvalidoFalhaAntesDeLerESoltaAVaga() {
        ObjectStorage spy = mock(ObjectStorage.class);
        StreamLimiter.Permit permit = limiter.tryAcquire().orElseThrow();

        assertThrows(InvalidMediaTypeException.class, () -> ObjectResponses.attachment(spy, stored(), null,
                Attachment.of("a.csv", "isto não é um tipo"), permit));

        verify(spy, never()).read(any(), any());
        assertEquals(1, limiter.available());
    }

    @Test
    void respostaTrazETagELastModifiedDoHead() {
        ObjectInfo head = stored();

        HttpHeaders headers = ObjectResponses.attachment(storage, head, null, attachment).getHeaders();

        assertEquals("\"" + head.version() + "\"", headers.getETag());
        assertEquals(head.lastModified().toEpochMilli() / 1000 * 1000, headers.getLastModified());
    }

    @Test
    void ifRangeComAVersaoAtualServeAFaixa() {
        ObjectInfo head = stored();
        HttpHeaders request = new HttpHeaders();
        request.set(HttpHeaders.RANGE, "bytes=2-4");
        request.set(HttpHeaders.IF_RANGE, "\"" + head.version() + "\"");

        ResponseEntity<Resource> response = ObjectResponses.attachmentForRequest(storage, head, request, attachment, null);

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
    }

    @Test
    void ifRangeDeOutraVersaoServeOObjetoInteiro() throws IOException {
        // O navegador retoma um download com If-Range: se o objeto mudou, emendar a faixa corromperia o arquivo.
        ObjectInfo head = stored();
        for (String stale : new String[] {"\"versao-antiga\"", "W/\"" + head.version() + "\"",
                "Wed, 21 Oct 2015 07:28:00 GMT", "lixo"}) {
            HttpHeaders request = new HttpHeaders();
            request.set(HttpHeaders.RANGE, "bytes=2-4");
            request.set(HttpHeaders.IF_RANGE, stale);

            ResponseEntity<Resource> response = ObjectResponses.attachmentForRequest(storage, head, request, attachment, null);

            assertEquals(HttpStatus.OK, response.getStatusCode(), stale);
            assertArrayEquals(DATA, body(response));
        }
    }

    @Test
    void ifRangeComADataDeModificacaoServeAFaixa() {
        ObjectInfo head = stored();
        HttpHeaders request = new HttpHeaders();
        request.set(HttpHeaders.RANGE, "bytes=2-4");
        request.setDate(HttpHeaders.IF_RANGE, head.lastModified().toEpochMilli());

        ResponseEntity<Resource> response = ObjectResponses.attachmentForRequest(storage, head, request, attachment, null);

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
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
