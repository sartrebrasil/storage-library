package com.example.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    }

    @Test
    void putOptionsCarregaCondicao() {
        PutOptions base = PutOptions.of("text/plain");

        assertEquals(Condition.none(), base.condition());
        assertEquals(Condition.ifNotExists(), base.ifNotExists().condition());
        assertEquals(Condition.ifVersionMatches("v1"), base.ifVersionMatches("v1").condition());
    }
}
