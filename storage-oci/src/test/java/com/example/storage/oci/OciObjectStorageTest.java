package com.example.storage.oci;

import com.example.storage.AccessDeniedException;
import com.example.storage.MultipartSession;
import com.example.storage.ObjectMetadata;
import com.example.storage.StorageException;
import com.example.storage.UploadedPart;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.model.CommitMultipartUploadPartDetails;
import com.oracle.bmc.objectstorage.model.MultipartUpload;
import com.oracle.bmc.objectstorage.internal.http.ObjectMetadataInterceptor;
import com.oracle.bmc.objectstorage.requests.AbortMultipartUploadRequest;
import com.oracle.bmc.objectstorage.requests.CommitMultipartUploadRequest;
import com.oracle.bmc.objectstorage.requests.CreateMultipartUploadRequest;
import com.oracle.bmc.objectstorage.requests.UploadPartRequest;
import com.oracle.bmc.objectstorage.responses.CreateMultipartUploadResponse;
import com.oracle.bmc.objectstorage.responses.UploadPartResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OciObjectStorageTest {

    private final com.oracle.bmc.objectstorage.ObjectStorage client =
            mock(com.oracle.bmc.objectstorage.ObjectStorage.class);
    private MultipartSession session;

    @BeforeEach
    void setUp() {
        when(client.createMultipartUpload(any())).thenReturn(CreateMultipartUploadResponse.builder()
                .multipartUpload(MultipartUpload.builder().uploadId("up-1").build())
                .build());
        ObjectMetadata metadata = new ObjectMetadata("text/csv", null,
                Map.of("tenant", "t1", "origem", "job")).withDownloadName("r.csv");
        session = new OciObjectStorage(client, "ns", "bucket").initiateMultipart("reports/r.csv", metadata);
    }

    @Test
    void iniciaUploadComMetadataSemPrefixoParaOSdkPrefixar() {
        ArgumentCaptor<CreateMultipartUploadRequest> captor = ArgumentCaptor.forClass(CreateMultipartUploadRequest.class);
        verify(client).createMultipartUpload(captor.capture());
        CreateMultipartUploadRequest request = captor.getValue();

        assertEquals("ns", request.getNamespaceName());
        assertEquals("bucket", request.getBucketName());
        assertEquals("reports/r.csv", request.getCreateMultipartUploadDetails().getObject());
        assertEquals("text/csv", request.getCreateMultipartUploadDetails().getContentType());
        assertEquals("attachment; filename=\"r.csv\"",
                request.getCreateMultipartUploadDetails().getContentDisposition());
        assertEquals(Map.of("tenant", "t1", "origem", "job"), request.getCreateMultipartUploadDetails().getMetadata());
        assertEquals(Map.of("opc-meta-tenant", "t1", "opc-meta-origem", "job"),
                ObjectMetadataInterceptor.intercept(request).getCreateMultipartUploadDetails().getMetadata());
    }

    @Test
    void enviaSoOTrechoValidoDoBufferComMd5() throws Exception {
        when(client.uploadPart(any())).thenReturn(UploadPartResponse.builder().eTag("e2").build());
        byte[] buffer = {1, 2, 3, 4, 5, 6, 7, 8};

        UploadedPart part = session.uploadPart(2, buffer, 5);

        ArgumentCaptor<UploadPartRequest> captor = ArgumentCaptor.forClass(UploadPartRequest.class);
        verify(client).uploadPart(captor.capture());
        UploadPartRequest request = captor.getValue();

        byte[] expected = Arrays.copyOf(buffer, 5);
        String md5 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(expected));
        assertEquals("up-1", request.getUploadId());
        assertEquals(2, request.getUploadPartNum());
        assertEquals(5L, request.getContentLength());
        assertEquals(md5, request.getContentMD5());
        assertArrayEquals(expected, request.getUploadPartBody().readAllBytes());
        assertEquals(new UploadedPart(2, "e2", md5), part);
    }

    @Test
    void commitComPartesOrdenadas() {
        session.complete(List.of(new UploadedPart(2, "e2", null), new UploadedPart(1, "e1", null)));

        ArgumentCaptor<CommitMultipartUploadRequest> captor = ArgumentCaptor.forClass(CommitMultipartUploadRequest.class);
        verify(client).commitMultipartUpload(captor.capture());
        List<CommitMultipartUploadPartDetails> parts =
                captor.getValue().getCommitMultipartUploadDetails().getPartsToCommit();

        assertEquals("up-1", captor.getValue().getUploadId());
        assertEquals(List.of(1, 2), parts.stream().map(CommitMultipartUploadPartDetails::getPartNum).toList());
        assertEquals(List.of("e1", "e2"), parts.stream().map(CommitMultipartUploadPartDetails::getEtag).toList());
    }

    @Test
    void abortIgnora404() {
        when(client.abortMultipartUpload(any())).thenThrow(new BmcException(404, "NoSuchUpload", "gone", "req"));

        assertDoesNotThrow(session::abort);

        ArgumentCaptor<AbortMultipartUploadRequest> captor = ArgumentCaptor.forClass(AbortMultipartUploadRequest.class);
        verify(client).abortMultipartUpload(captor.capture());
        assertEquals("up-1", captor.getValue().getUploadId());
    }

    @Test
    void abortPropagaOutrosErros() {
        when(client.abortMultipartUpload(any())).thenThrow(new BmcException(500, "Internal", "boom", "req"));

        assertThrows(StorageException.class, session::abort);
    }

    @Test
    void falhaNoEnvioViraStorageException() {
        when(client.uploadPart(any())).thenThrow(new BmcException(503, "Unavailable", "down", "req"));

        StorageException e = assertThrows(StorageException.class, () -> session.uploadPart(1, new byte[1], 1));
        assertInstanceOf(BmcException.class, e.getCause());
    }

    @Test
    void falhaNoEnvioMantemOTipoDaExcecao() {
        when(client.uploadPart(any())).thenThrow(new BmcException(403, "NotAuthorized", "denied", "req"));

        assertThrows(AccessDeniedException.class, () -> session.uploadPart(1, new byte[1], 1));
    }
}
