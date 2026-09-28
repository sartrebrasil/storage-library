package com.example.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoreModelTest {

    @ParameterizedTest
    @ValueSource(strings = {"tenant-id", "Tenant", "1tenant", "tenant.id", ""})
    void metadataRejeitaChavesNaoPortaveis(String key) {
        assertThrows(IllegalArgumentException.class, () -> new ObjectMetadata("text/plain", null, Map.of(key, "v")));
    }

    @Test
    void metadataAceitaChavesPortaveisERejeitaValorNaoAscii() {
        assertDoesNotThrow(() -> new ObjectMetadata(null, null, Map.of("tenant_id", "t-1", "_x9", "")));
        assertThrows(IllegalArgumentException.class, () -> new ObjectMetadata(null, null, Map.of("nome", "João")));
        assertThrows(IllegalArgumentException.class, () -> new ObjectMetadata(null, null, Map.of("nome", "a\nb")));
    }

    @Test
    void statusHttpViraExcecaoEspecifica() {
        assertInstanceOf(ObjectNotFoundException.class, StorageException.fromHttpStatus(404, "m", null));
        assertInstanceOf(PreconditionFailedException.class, StorageException.fromHttpStatus(412, "m", null));
        assertInstanceOf(AccessDeniedException.class, StorageException.fromHttpStatus(403, "m", null));
        assertInstanceOf(AccessDeniedException.class, StorageException.fromHttpStatus(401, "m", null));
        assertEquals(StorageException.class, StorageException.fromHttpStatus(500, "m", null).getClass());
    }

    @Test
    void byteRangeValidaECalculaUltimoByte() {
        assertTrue(ByteRange.all().isAll());
        assertTrue(ByteRange.from(10).toEnd());
        assertEquals(14, ByteRange.of(10, 5).lastByte());
        assertThrows(IllegalArgumentException.class, () -> ByteRange.of(-1, 5));
        assertThrows(IllegalArgumentException.class, () -> ByteRange.of(0, 0));
        assertThrows(IllegalArgumentException.class, () -> ByteRange.suffix(0));
    }

    @Test
    void byteRangeLeCabecalhoHttp() {
        assertEquals(ByteRange.of(10, 5), ByteRange.parseHttp("bytes=10-14"));
        assertEquals(ByteRange.from(95), ByteRange.parseHttp(" Bytes = 95- "));
        assertEquals(ByteRange.suffix(500), ByteRange.parseHttp("bytes=-500"));
        assertEquals(ByteRange.all(), ByteRange.parseHttp("bytes=0-"));
        assertEquals("bytes=-500", ByteRange.suffix(500).httpValue());
        assertEquals("bytes=95-", ByteRange.from(95).httpValue());
        assertEquals("bytes=10-14", ByteRange.of(10, 5).httpValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"items=0-1", "bytes=0-1,5-6", "bytes=5-1", "bytes=-", "bytes=a-1", "bytes=+1-2", "bytes=-0", "bytes"})
    void byteRangeRejeitaCabecalhoInvalido(String header) {
        assertThrows(IllegalArgumentException.class, () -> ByteRange.parseHttp(header));
    }

    @Test
    void byteRangeResolveContraOTamanho() {
        assertEquals(ByteRange.all(), ByteRange.all().resolve(0));
        assertEquals(ByteRange.of(95, 5), ByteRange.of(95, 10).resolve(100));
        assertEquals(ByteRange.of(90, 10), ByteRange.from(90).resolve(100));
        assertEquals(ByteRange.of(97, 3), ByteRange.suffix(3).resolve(100));
        assertEquals(ByteRange.of(0, 100), ByteRange.suffix(1_000).resolve(100));
        assertThrows(RangeNotSatisfiableException.class, () -> ByteRange.from(100).resolve(100));
        assertThrows(RangeNotSatisfiableException.class, () -> ByteRange.of(0, 1).resolve(0));
        assertThrows(RangeNotSatisfiableException.class, () -> ByteRange.suffix(1).resolve(0));
    }

    @Test
    void objectContentLeRespostaHttp() {
        ObjectContent partial = ObjectContent.fromHttp(InputStream.nullInputStream(), 5, "bytes 10-14/100");
        assertEquals(ByteRange.of(10, 5), partial.range());
        assertEquals(100, partial.totalSize());
        assertEquals("bytes 10-14/100", partial.contentRange().orElseThrow());

        ObjectContent full = ObjectContent.fromHttp(InputStream.nullInputStream(), 100, null);
        assertFalse(full.isPartial());
        assertEquals(100, full.contentLength());

        assertThrows(RangeNotSatisfiableException.class,
                () -> ObjectContent.fromHttp(InputStream.nullInputStream(), 0, "bytes 0--1/0"));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectContent(InputStream.nullInputStream(), ByteRange.suffix(3), 100));
    }

    @Test
    void status416ViraRangeNotSatisfiable() {
        assertInstanceOf(RangeNotSatisfiableException.class, StorageException.fromHttpStatus(416, "x", null));
    }

    @Test
    void putOptionsCarregaCondicao() {
        PutOptions base = PutOptions.of("text/plain");

        assertEquals(Condition.none(), base.condition());
        assertEquals(Condition.ifNotExists(), base.ifNotExists().condition());
        assertEquals(Condition.ifVersionMatches("v1"), base.ifVersionMatches("v1").condition());
    }
}
