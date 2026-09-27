package com.example.storage.oci;

import com.example.storage.ByteRange;
import com.example.storage.CommonPrefix;
import com.example.storage.ListEntry;
import com.example.storage.ObjectInfo;
import com.example.storage.ObjectMetadata;
import com.example.storage.ObjectNotFoundException;
import com.example.storage.ObjectSummary;
import com.example.storage.PreconditionFailedException;
import com.example.storage.PresignedRequest;
import com.example.storage.PutOptions;
import com.example.storage.StorageException;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStoragePaginators;
import com.oracle.bmc.objectstorage.model.CreatePreauthenticatedRequestDetails;
import com.oracle.bmc.objectstorage.model.ListObjects;
import com.oracle.bmc.objectstorage.model.PreauthenticatedRequest;
import com.oracle.bmc.objectstorage.model.WorkRequest;
import com.oracle.bmc.objectstorage.requests.CopyObjectRequest;
import com.oracle.bmc.objectstorage.requests.CreatePreauthenticatedRequestRequest;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.objectstorage.requests.ListObjectsRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.CopyObjectResponse;
import com.oracle.bmc.objectstorage.responses.CreatePreauthenticatedRequestResponse;
import com.oracle.bmc.objectstorage.responses.GetObjectResponse;
import com.oracle.bmc.objectstorage.responses.GetWorkRequestResponse;
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

    private static HeadObjectResponse headResponse() {
        return HeadObjectResponse.builder().contentLength(5L).eTag("e1").contentType("text/plain")
                .lastModified(Date.from(NOW)).opcMeta(Map.of("opc-meta-tenant", "t1")).build();
    }

    @Test
    void putPrefixaMetadataEAplicaCondicao() {
        when(client.putObject(any())).thenReturn(PutObjectResponse.builder().eTag("e2").build());
        var options = PutOptions.of(new ObjectMetadata("text/plain", null, Map.of("tenant", "t1"))).ifNotExists();

        String version = storage.put("k", new byte[5], options);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(captor.capture());
        assertEquals("e2", version);
        assertEquals(5L, captor.getValue().getContentLength());
        assertEquals(Map.of("opc-meta-tenant", "t1"), captor.getValue().getOpcMeta());
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
    void headDiferenciaObjetoDeBucketInexistente() {
        when(client.headObject(any()))
                .thenThrow(new BmcException(404, "ObjectNotFound", "no object", "req"))
                .thenThrow(new BmcException(404, "BucketNotFound", "no bucket", "req"));

        assertTrue(storage.head("k").isEmpty());
        StorageException e = assertThrows(StorageException.class, () -> storage.head("k"));
        assertFalse(e instanceof ObjectNotFoundException);
    }

    @Test
    void openEnviaFaixaInclusiva() throws Exception {
        when(client.getObject(any())).thenReturn(GetObjectResponse.builder()
                .inputStream(new ByteArrayInputStream(new byte[5])).build());

        storage.open("k", ByteRange.of(10, 5)).close();

        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObject(captor.capture());
        assertEquals("bytes=10-14", captor.getValue().getRange().toString());
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
}
