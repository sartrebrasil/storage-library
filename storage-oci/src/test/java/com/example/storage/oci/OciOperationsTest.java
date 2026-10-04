package com.example.storage.oci;

import com.example.storage.ByteRange;
import com.example.storage.CommonPrefix;
import com.example.storage.ListEntry;
import com.example.storage.ObjectContent;
import com.example.storage.ObjectInfo;
import com.example.storage.ObjectMetadata;
import com.example.storage.ObjectNotFoundException;
import com.example.storage.ObjectSummary;
import com.example.storage.PreconditionFailedException;
import com.example.storage.PresignedRequest;
import com.example.storage.PutOptions;
import com.example.storage.RangeNotSatisfiableException;
import com.example.storage.StorageException;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.model.Range;
import com.oracle.bmc.objectstorage.ObjectStoragePaginators;
import com.oracle.bmc.objectstorage.model.CreatePreauthenticatedRequestDetails;
import com.oracle.bmc.objectstorage.model.ListObjects;
import com.oracle.bmc.objectstorage.model.PreauthenticatedRequest;
import com.oracle.bmc.objectstorage.model.WorkRequest;
import com.oracle.bmc.objectstorage.internal.http.ObjectMetadataInterceptor;
import com.oracle.bmc.objectstorage.requests.CopyObjectRequest;
import com.oracle.bmc.objectstorage.requests.CreatePreauthenticatedRequestRequest;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.objectstorage.requests.ListObjectsRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.CopyObjectResponse;
import com.oracle.bmc.objectstorage.responses.CreatePreauthenticatedRequestResponse;
import com.oracle.bmc.objectstorage.responses.GetObjectResponse;
import com.oracle.bmc.objectstorage.responses.GetWorkRequestResponse;
import com.oracle.bmc.objectstorage.responses.HeadBucketResponse;
import com.oracle.bmc.objectstorage.responses.HeadObjectResponse;
import com.oracle.bmc.objectstorage.responses.ListObjectsResponse;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OciOperationsTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    private static final String ENDPOINT = "https://objectstorage.sa-saopaulo-1.oraclecloud.com";

    private final com.oracle.bmc.objectstorage.ObjectStorage client =
            mock(com.oracle.bmc.objectstorage.ObjectStorage.class);
    private final OciObjectStorage storage =
            new OciObjectStorage(client, "ns", "bucket", Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMillis(1));

    private void parReturns(String accessUri) {
        when(client.getEndpoint()).thenReturn(ENDPOINT);
        when(client.createPreauthenticatedRequest(any())).thenReturn(CreatePreauthenticatedRequestResponse.builder()
                .preauthenticatedRequest(PreauthenticatedRequest.builder().accessUri(accessUri).build())
                .build());
    }

    /** Como o SDK devolve: o interceptor já tirou o "opc-meta-" das chaves. */
    private static HeadObjectResponse headResponse() {
        return headResponse(Map.of("tenant", "t1"));
    }

    private static HeadObjectResponse headResponse(Map<String, String> opcMeta) {
        return HeadObjectResponse.builder().contentLength(5L).eTag("e1").contentType("text/plain")
                .lastModified(Date.from(NOW)).opcMeta(opcMeta).build();
    }

    @Test
    void putEnviaMetadataSemPrefixoParaOSdkPrefixarEAplicaCondicao() {
        when(client.putObject(any())).thenReturn(PutObjectResponse.builder().eTag("e2").build());
        var options = PutOptions.of(new ObjectMetadata("text/plain", null, Map.of("tenant", "t1"))).ifNotExists();

        String version = storage.put("k", new byte[5], options);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(captor.capture());
        assertEquals("e2", version);
        assertEquals(5L, captor.getValue().getContentLength());
        assertEquals(Map.of("tenant", "t1"), captor.getValue().getOpcMeta());
        // O que vai para o fio: o interceptor do SDK acrescenta o prefixo uma única vez.
        assertEquals(Map.of("opc-meta-tenant", "t1"),
                ObjectMetadataInterceptor.intercept(captor.getValue()).getOpcMeta());
        assertEquals("*", captor.getValue().getIfNoneMatch());
    }

    @Test
    void putIfVersionMatchesEmObjetoInexistenteViraPreconditionFailed() {
        when(client.putObject(any())).thenThrow(new BmcException(404, "ObjectNotFound", "gone", "req"));

        assertThrows(PreconditionFailedException.class,
                () -> storage.put("k", new byte[1], PutOptions.of("text/plain").ifVersionMatches("e1")));
    }

    @Test
    void headRemovePrefixoDaMetadata() {
        when(client.headObject(any())).thenReturn(headResponse());

        ObjectInfo info = storage.head("k").orElseThrow();

        assertEquals(new ObjectInfo("k", 5, "e1", NOW, new ObjectMetadata("text/plain", null, Map.of("tenant", "t1"))),
                info);
    }

    @Test
    void headTiraOPrefixoQueSobrouDeObjetosGravadosComPrefixoDuplo() {
        // Gravado por versões anteriores como "opc-meta-opc-meta-tenant"; o SDK tira só um prefixo.
        when(client.headObject(any())).thenReturn(headResponse(Map.of("opc-meta-tenant", "t1")));

        assertEquals(Map.of("tenant", "t1"), storage.head("k").orElseThrow().metadata().userMetadata());
    }

    @Test
    void headDiferenciaObjetoDeBucketInexistente() {
        // HEAD não tem corpo: o SDK monta o 404 com serviceCode "Unknown", seja objeto ou bucket que falta.
        when(client.headObject(any())).thenThrow(new BmcException(404, "Unknown", "", "req"));
        when(client.headBucket(any()))
                .thenReturn(HeadBucketResponse.builder().build())
                .thenThrow(new BmcException(404, "Unknown", "", "req"));

        assertTrue(storage.head("k").isEmpty());
        StorageException e = assertThrows(StorageException.class, () -> storage.head("k"));
        assertFalse(e instanceof ObjectNotFoundException);
    }

    @Test
    void putCondicionalEmBucketInexistenteNaoViraPrecondicao() {
        when(client.putObject(any())).thenThrow(new BmcException(404, "BucketNotFound", "no bucket", "req"));

        StorageException e = assertThrows(StorageException.class, () -> storage.put("k", new byte[1],
                PutOptions.of("text/plain").ifVersionMatches("e1")));

        assertFalse(e instanceof PreconditionFailedException, e.toString());
    }

    @Test
    void contentRangeDegeneradoFechaORespostaEViraStorageException() throws Exception {
        boolean[] closed = {false};
        ByteArrayInputStream body = new ByteArrayInputStream(new byte[0]) {
            @Override
            public void close() {
                closed[0] = true;
            }
        };
        when(client.getObject(any())).thenReturn(GetObjectResponse.builder().inputStream(body).contentLength(0L)
                .contentRange(Range.parse("bytes 5-4/10")).build());

        StorageException e = assertThrows(StorageException.class, () -> storage.read("k", ByteRange.of(5, 1)));

        assertEquals(StorageException.class, e.getClass());
        assertTrue(closed[0]);
    }

    @Test
    void openEnviaFaixaInclusiva() throws Exception {
        when(client.getObject(any())).thenReturn(GetObjectResponse.builder()
                .inputStream(new ByteArrayInputStream(new byte[5])).contentLength(5L)
                .contentRange(Range.parse("bytes 10-14/100")).build());

        storage.open("k", ByteRange.of(10, 5)).close();

        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObject(captor.capture());
        assertEquals("bytes=10-14", captor.getValue().getRange().toString());
    }

    @Test
    void readEnviaSufixoNativoEDevolveFaixaServida() throws Exception {
        when(client.getObject(any())).thenReturn(GetObjectResponse.builder()
                .inputStream(new ByteArrayInputStream(new byte[5])).contentLength(5L)
                .contentRange(Range.parse("bytes 95-99/100")).build());

        try (ObjectContent content = storage.read("k", ByteRange.suffix(5))) {
            assertEquals(5, content.contentLength());
            assertEquals(100, content.totalSize());
            assertEquals("bytes 95-99/100", content.contentRange().orElseThrow());
        }

        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObject(captor.capture());
        assertEquals("bytes=-5", captor.getValue().getRange().toString());
    }

    @Test
    void faixaForaDoObjetoViraRangeNotSatisfiable() {
        when(client.getObject(any())).thenThrow(new BmcException(416, "InvalidRange", "range", "req"));

        assertThrows(RangeNotSatisfiableException.class, () -> storage.open("k", ByteRange.from(500)));
    }

    @Test
    void checkAccessDistingueBucketInexistente() {
        when(client.headBucket(any())).thenThrow(new BmcException(404, null, "not found", "req"));

        StorageException e = assertThrows(StorageException.class, storage::checkAccess);
        assertFalse(e instanceof ObjectNotFoundException);
    }

    @Test
    void listUsaPaginatorComCamposNecessarios() {
        ObjectStoragePaginators paginators = mock(ObjectStoragePaginators.class);
        when(client.getPaginators()).thenReturn(paginators);
        when(paginators.listObjectsRecordIterator(any())).thenReturn(List.of(
                com.oracle.bmc.objectstorage.model.ObjectSummary.builder()
                        .name("p/a").size(3L).etag("e").timeModified(Date.from(NOW)).build()));

        List<ObjectSummary> items = storage.list("p/").toList();

        ArgumentCaptor<ListObjectsRequest> captor = ArgumentCaptor.forClass(ListObjectsRequest.class);
        verify(paginators).listObjectsRecordIterator(captor.capture());
        assertEquals("p/", captor.getValue().getPrefix());
        assertEquals("name,size,etag,timeModified", captor.getValue().getFields());
        assertEquals(List.of(new ObjectSummary("p/a", 3, "e", NOW)), items);
    }

    @Test
    void listDirectoryPaginaPorNextStartWithEJuntaPastasSemRepetir() {
        when(client.listObjects(any()))
                .thenReturn(listPage(List.of("p/a"), List.of("p/sub/"), "p/sub/x"))
                .thenReturn(listPage(List.of(), List.of("p/sub/", "p/sub2/"), null));

        List<ListEntry> entries = storage.listDirectory("p/").toList();

        assertEquals(List.of("p/a", "p/sub/", "p/sub2/"), entries.stream().map(ListEntry::key).toList());
        assertInstanceOf(CommonPrefix.class, entries.get(1));
        ArgumentCaptor<ListObjectsRequest> captor = ArgumentCaptor.forClass(ListObjectsRequest.class);
        verify(client, times(2)).listObjects(captor.capture());
        assertEquals("/", captor.getAllValues().get(0).getDelimiter());
        assertNull(captor.getAllValues().get(0).getStart());
        assertEquals("p/sub/x", captor.getAllValues().get(1).getStart());
    }

    @Test
    void falhaEmPaginaSeguinteViraStorageException() {
        when(client.listObjects(any()))
                .thenReturn(listPage(List.of("p/a"), List.of(), "p/b"))
                .thenThrow(new BmcException(503, "ServiceUnavailable", "down", "req"));

        var entries = storage.listDirectory("p/");

        assertThrows(StorageException.class, entries::toList);
    }

    @Test
    void copyComEndpointSemRegiaoFalhaAntesDeQualquerChamada() {
        when(client.getEndpoint()).thenReturn("https://proxy.interno.local");

        assertThrows(IllegalStateException.class, () -> storage.copy("a", "b"));

        verify(client, never()).headObject(any());
    }

    private static ListObjectsResponse listPage(List<String> names, List<String> prefixes, String next) {
        return ListObjectsResponse.builder().listObjects(ListObjects.builder()
                .objects(names.stream().map(n -> com.oracle.bmc.objectstorage.model.ObjectSummary.builder()
                        .name(n).size(1L).etag("e").timeModified(Date.from(NOW)).build()).toList())
                .prefixes(prefixes)
                .nextStartWith(next)
                .build()).build();
    }

    @Test
    void deleteIgnoraObjetoInexistente() {
        when(client.deleteObject(any())).thenThrow(new BmcException(404, "ObjectNotFound", "gone", "req"));

        assertDoesNotThrow(() -> storage.delete("k"));
    }

    @Test
    void copyUsaRegiaoDoEndpointEAguardaWorkRequest() {
        when(client.getEndpoint()).thenReturn(ENDPOINT);
        when(client.headObject(any())).thenReturn(headResponse());
        when(client.copyObject(any())).thenReturn(CopyObjectResponse.builder().opcWorkRequestId("wr-1").build());
        when(client.getWorkRequest(any()))
                .thenReturn(workRequest(WorkRequest.Status.InProgress))
                .thenReturn(workRequest(WorkRequest.Status.Completed));

        storage.copy("a", "b");

        ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(client).copyObject(captor.capture());
        assertEquals("sa-saopaulo-1", captor.getValue().getCopyObjectDetails().getDestinationRegion());
        assertEquals("b", captor.getValue().getCopyObjectDetails().getDestinationObjectName());
        verify(client, times(2)).getWorkRequest(any());
    }

    @Test
    void copyComWorkRequestFalhaLancaStorageException() {
        when(client.getEndpoint()).thenReturn(ENDPOINT);
        when(client.headObject(any())).thenReturn(headResponse());
        when(client.copyObject(any())).thenReturn(CopyObjectResponse.builder().opcWorkRequestId("wr-1").build());
        when(client.getWorkRequest(any())).thenReturn(workRequest(WorkRequest.Status.Failed));

        assertThrows(StorageException.class, () -> storage.copy("a", "b"));
    }

    @Test
    void copyDeInexistenteLancaNotFoundSemCriarWorkRequest() {
        when(client.getEndpoint()).thenReturn(ENDPOINT);
        when(client.headObject(any())).thenThrow(new BmcException(404, "ObjectNotFound", "gone", "req"));

        assertThrows(ObjectNotFoundException.class, () -> storage.copy("a", "b"));
        verify(client, never()).copyObject(any());
    }

    @Test
    void presignGetCriaParDeLeitura() {
        parReturns("/p/token/n/ns/b/bucket/o/r.csv");

        URI url = storage.presignGet("r.csv", Duration.ofHours(1));

        ArgumentCaptor<CreatePreauthenticatedRequestRequest> captor =
                ArgumentCaptor.forClass(CreatePreauthenticatedRequestRequest.class);
        verify(client).createPreauthenticatedRequest(captor.capture());
        CreatePreauthenticatedRequestDetails details = captor.getValue().getCreatePreauthenticatedRequestDetails();
        assertEquals(CreatePreauthenticatedRequestDetails.AccessType.ObjectRead, details.getAccessType());
        assertEquals(Date.from(NOW.plusSeconds(3600)), details.getTimeExpires());
        assertEquals(URI.create(ENDPOINT + "/p/token/n/ns/b/bucket/o/r.csv"), url);
    }

    @Test
    void presignPutCriaParDeEscritaComCabecalhos() {
        parReturns("/p/token/n/ns/b/bucket/o/r.csv");
        var options = PutOptions.of(new ObjectMetadata("text/csv", null, Map.of("tenant", "t1"))).ifVersionMatches("e9");

        PresignedRequest request = storage.presignPut("r.csv", Duration.ofMinutes(10), options);

        ArgumentCaptor<CreatePreauthenticatedRequestRequest> captor =
                ArgumentCaptor.forClass(CreatePreauthenticatedRequestRequest.class);
        verify(client).createPreauthenticatedRequest(captor.capture());
        assertEquals(CreatePreauthenticatedRequestDetails.AccessType.ObjectWrite,
                captor.getValue().getCreatePreauthenticatedRequestDetails().getAccessType());
        assertEquals("PUT", request.method());
        assertEquals(Map.of("Content-Type", "text/csv", "opc-meta-tenant", "t1", "if-match", "e9"), request.headers());
    }

    private static GetWorkRequestResponse workRequest(WorkRequest.Status status) {
        return GetWorkRequestResponse.builder().workRequest(WorkRequest.builder().status(status).build()).build();
    }

    @Test
    void presignGetComNomeDeDownloadNaoEhSuportado() {
        assertThrows(UnsupportedOperationException.class,
                () -> storage.presignGet("r.csv", Duration.ofHours(1), "report-1.csv"));
        assertThrows(IllegalArgumentException.class,
                () -> storage.presignGet("r.csv", Duration.ofHours(1), "a\"b.csv"), "valida antes");
        verify(client, never()).createPreauthenticatedRequest(any());
    }
}
