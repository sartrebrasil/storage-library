package com.example.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoreModelTest {

    @ParameterizedTest
    @ValueSource(strings = {"tenant-id", "Tenant", "1tenant", "tenant.id", ""})
    void metadataRejeitaChavesNaoPortaveisNaEscrita(String key) {
        ObjectMetadata metadata = new ObjectMetadata("text/plain", null, Map.of(key, "v"));

        assertThrows(IllegalArgumentException.class, metadata::requireWritable);
        assertThrows(IllegalArgumentException.class, () -> PutOptions.of(metadata));
    }

    @Test
    void metadataAceitaChavesPortaveisERejeitaValorNaoAsciiNaEscrita() {
        assertDoesNotThrow(() -> PutOptions.of(new ObjectMetadata(null, null, Map.of("tenant_id", "t-1", "_x9", ""))));
        assertThrows(IllegalArgumentException.class,
                () -> PutOptions.of(new ObjectMetadata(null, null, Map.of("nome", "João"))));
        assertThrows(IllegalArgumentException.class,
                () -> PutOptions.of(new ObjectMetadata(null, null, Map.of("nome", "a\nb"))));
    }

    @Test
    void metadataLidaDoProvedorNaoEhValidada() {
        // Gravada por outras ferramentas (gsutil, s3cmd, Storage Explorer): head precisa devolvê-la como veio.
        Map<String, String> foreign = Map.of("goog-reserved-file-mtime", "1700000000", "Owner", "joão");

        ObjectMetadata metadata = assertDoesNotThrow(() -> new ObjectMetadata("text/plain", null, foreign));
        assertEquals(foreign, metadata.userMetadata());
        assertDoesNotThrow(() -> metadata.withDownloadName("r.csv"));
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
        // Fim perto de Long.MAX_VALUE: offset + length não pode estourar e deixar a faixa sem corte.
        assertEquals(ByteRange.of(5, 95), ByteRange.parseHttp("bytes=5-9223372036854775807").resolve(100));
        assertEquals(ByteRange.of(1, 99), ByteRange.of(1, Long.MAX_VALUE).resolve(100));
        assertEquals("bytes=1-9223372036854775806", ByteRange.of(1, Long.MAX_VALUE).httpValue());
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

    @Test
    void nomeDeDownloadViraContentDispositionAttachment() {
        assertEquals("attachment; filename=\"report-1.csv.gz\"", ObjectMetadata.attachmentDisposition("report-1.csv.gz"));
        assertEquals("attachment; filename=\"r.csv\"", ObjectMetadata.of("text/csv").withDownloadName("r.csv").contentDisposition());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a\"b.csv", "a\\b.csv", "relatório.csv", "a\nb.csv"})
    void nomeDeDownloadForaDoPortavelEhRejeitado(String name) {
        assertThrows(IllegalArgumentException.class, () -> ObjectMetadata.attachmentDisposition(name));
        assertThrows(IllegalArgumentException.class, () -> ObjectMetadata.empty().withDownloadName(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"items=0-1", "bytes=0-1,5-6", "bytes=5-1", "bytes"})
    void byteRangeTolerantIgnoraOQueNaoEntende(String header) {
        assertEquals(ByteRange.all(), ByteRange.parseHttpOrAll(header));
    }

    @Test
    void byteRangeTolerantLeOCabecalhoValido() {
        assertEquals(ByteRange.all(), ByteRange.parseHttpOrAll(null));
        assertEquals(ByteRange.parseHttp("bytes=10-19"), ByteRange.parseHttpOrAll("bytes=10-19"));
        assertEquals(ByteRange.suffix(5), ByteRange.parseHttpOrAll("bytes=-5"));
    }

    @Test
    void rangeNotSatisfiableCarregaOTamanhoQuandoConhecido() {
        RangeNotSatisfiableException semTamanho = new RangeNotSatisfiableException("fora", null);
        assertTrue(semTamanho.totalSize().isEmpty());

        RangeNotSatisfiableException comTamanho = semTamanho.withTotalSize(100);
        assertEquals(100, comTamanho.totalSize().orElseThrow());
        assertEquals("fora", comTamanho.getMessage());
        assertSame(semTamanho, comTamanho.getCause());
        assertThrows(IllegalArgumentException.class, () -> semTamanho.withTotalSize(-1));

        RangeNotSatisfiableException resolvida = assertThrows(RangeNotSatisfiableException.class,
                () -> ByteRange.from(100).resolve(100));
        assertEquals(100, resolvida.totalSize().orElseThrow(), "resolve conhece o tamanho");
    }

    @Test
    void presignTtlProporcionalAoTamanhoDentroDosLimites() {
        PresignTtl ttl = new PresignTtl(1_000, Duration.ofMinutes(1), Duration.ofMinutes(10));

        assertEquals(Duration.ofMinutes(1), ttl.forSize(0));
        assertEquals(Duration.ofMinutes(1), ttl.forSize(59_999));
        assertEquals(Duration.ofSeconds(120), ttl.forSize(120_999), "truncado em segundos");
        assertEquals(Duration.ofMinutes(10), ttl.forSize(10_000_000));
        assertThrows(IllegalArgumentException.class, () -> ttl.forSize(-1));
    }

    @Test
    void presignTtlValidaAConfiguracao() {
        Duration minuto = Duration.ofMinutes(1);
        assertThrows(IllegalArgumentException.class, () -> new PresignTtl(0, minuto, minuto));
        assertThrows(IllegalArgumentException.class, () -> new PresignTtl(1, Duration.ofMillis(500), minuto));
        assertThrows(IllegalArgumentException.class, () -> new PresignTtl(1, Duration.ofMinutes(2), minuto));
        assertThrows(NullPointerException.class, () -> new PresignTtl(1, null, minuto));
        assertDoesNotThrow(() -> new PresignTtl(1, minuto, minuto));
    }

    @Test
    void contentRangeMalformadoFechaOStream() {
        for (String header : new String[] {"bytes x-y/z", "bytes */100", "bytes 0-1/*"}) {
            boolean[] closed = {false};
            InputStream stream = new ByteArrayInputStream(new byte[0]) {
                @Override
                public void close() {
                    closed[0] = true;
                }
            };

            assertThrows(IllegalArgumentException.class, () -> ObjectContent.fromHttp(stream, 2, header), header);
            assertTrue(closed[0], header);
        }
    }

    @Test
    void deleteResultPreservaAOrdemDasFalhas() {
        Map<String, StorageException> failures = new java.util.LinkedHashMap<>();
        for (String key : List.of("z", "a", "m", "b")) {
            failures.put(key, new StorageException(key, null));
        }

        assertEquals(List.of("z", "a", "m", "b"), List.copyOf(new DeleteResult(failures).failures().keySet()));
    }

    @Test
    void sidecarIgnoradoQuandoNaoDescreveMaisOArquivo() throws java.io.IOException {
        java.util.Properties sidecar = new java.util.Properties();
        sidecar.load(new ByteArrayInputStream(SidecarFiles.encode(ObjectMetadata.of("text/plain"), "v9", 5, 100)));

        assertEquals("v9", SidecarFiles.versionOf(SidecarFiles.describing(sidecar, 5, 100), 100));
        assertEquals("text/plain", SidecarFiles.metadataFrom(SidecarFiles.describing(sidecar, 5, 100)).contentType());
        assertEquals("v100", SidecarFiles.versionOf(SidecarFiles.describing(sidecar, 6, 100), 100), "outro tamanho");
        assertEquals("v200", SidecarFiles.versionOf(SidecarFiles.describing(sidecar, 5, 200), 200), "outra data");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a/../b", "a//b", "a/", "/a", "a\\b", "x.", "x ", ".uploads/x", "a.objmeta", "d/.pending-1"})
    void chaveDeArquivoInvalidaEhRejeitada(String key) {
        assertThrows(IllegalArgumentException.class, () -> SidecarFiles.requireValidKey(key));
    }
}
