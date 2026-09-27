package com.example.storage.gcs;

import com.example.storage.MultipartSession;
import com.example.storage.ObjectMetadata;
import com.example.storage.StorageException;
import com.example.storage.UploadedPart;
import com.google.cloud.storage.MultipartUploadClient;
import com.google.cloud.storage.RequestBody;
import com.google.cloud.storage.multipartupload.model.AbortMultipartUploadRequest;
import com.google.cloud.storage.multipartupload.model.CompleteMultipartUploadRequest;
import com.google.cloud.storage.multipartupload.model.CompletedPart;
import com.google.cloud.storage.multipartupload.model.CreateMultipartUploadRequest;
import com.google.cloud.storage.multipartupload.model.CreateMultipartUploadResponse;
import com.google.cloud.storage.multipartupload.model.UploadPartRequest;
import com.google.cloud.storage.multipartupload.model.UploadPartResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GcsObjectStorageTest {

    private final MultipartUploadClient client = mock(MultipartUploadClient.class);
    private MultipartSession session;

    @BeforeEach
    void setUp() {
        when(client.createMultipartUpload(any()))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("up-1").build());
        ObjectMetadata metadata = new ObjectMetadata("text/csv", null, Map.of("tenant", "t1"))
                .withDownloadName("r.csv");
        session = new GcsObjectStorage(mock(com.google.cloud.storage.Storage.class), client, "bucket").initiateMultipart("reports/r.csv", metadata);
    }

    @Test
    void iniciaUploadComMetadata() {
        ArgumentCaptor<CreateMultipartUploadRequest> captor =
                ArgumentCaptor.forClass(CreateMultipartUploadRequest.class);
        verify(client).createMultipartUpload(captor.capture());
        CreateMultipartUploadRequest request = captor.getValue();

        assertEquals("bucket", request.bucket());
        assertEquals("reports/r.csv", request.key());
        assertEquals("text/csv", request.contentType());
        assertEquals("attachment; filename=\"r.csv\"", request.contentDisposition());
        assertEquals(Map.of("tenant", "t1"), request.metadata());
        assertEquals("reports/r.csv", session.key());
    }

    @Test
    void enviaParteComUploadIdEDevolveEtag() {
        when(client.uploadPart(any(), any()))
                .thenReturn(UploadPartResponse.builder().eTag("e3").crc32c("c3").build());

        UploadedPart part = session.uploadPart(3, new byte[10], 4);

        ArgumentCaptor<UploadPartRequest> captor = ArgumentCaptor.forClass(UploadPartRequest.class);
        verify(client).uploadPart(captor.capture(), any(RequestBody.class));
        assertEquals("up-1", captor.getValue().uploadId());
        assertEquals(3, captor.getValue().partNumber());
        assertEquals(new UploadedPart(3, "e3", "c3"), part);
    }

    @Test
    void concluiComPartesOrdenadas() {
        session.complete(List.of(new UploadedPart(2, "e2", null), new UploadedPart(1, "e1", null)));

        ArgumentCaptor<CompleteMultipartUploadRequest> captor =
                ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        verify(client).completeMultipartUpload(captor.capture());
        assertEquals("up-1", captor.getValue().uploadId());
        assertEquals(List.of(1, 2), captor.getValue().multipartUpload().parts().stream()
                .map(CompletedPart::partNumber).toList());
        assertEquals(List.of("e1", "e2"), captor.getValue().multipartUpload().parts().stream()
                .map(CompletedPart::eTag).toList());
    }

    @Test
    void abortIgnora404() {
        when(client.abortMultipartUpload(any()))
                .thenThrow(new com.google.cloud.storage.StorageException(404, "gone"));

        assertDoesNotThrow(session::abort);

        ArgumentCaptor<AbortMultipartUploadRequest> captor = ArgumentCaptor.forClass(AbortMultipartUploadRequest.class);
        verify(client).abortMultipartUpload(captor.capture());
        assertEquals("up-1", captor.getValue().uploadId());
    }

    @Test
    void abortPropagaOutrosErros() {
        when(client.abortMultipartUpload(any()))
                .thenThrow(new com.google.cloud.storage.StorageException(500, "boom"));

        assertThrows(StorageException.class, session::abort);
    }

    @Test
    void falhaNoEnvioViraStorageException() {
        when(client.uploadPart(any(), any()))
                .thenThrow(new com.google.cloud.storage.StorageException(503, "unavailable"));

        StorageException e = assertThrows(StorageException.class, () -> session.uploadPart(1, new byte[1], 1));
        assertInstanceOf(com.google.cloud.storage.StorageException.class, e.getCause());
    }
}
