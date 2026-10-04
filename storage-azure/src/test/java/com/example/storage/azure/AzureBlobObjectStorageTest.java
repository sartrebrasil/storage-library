package com.example.storage.azure;

import com.azure.core.exception.AzureException;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlockBlobCommitBlockListOptions;
import com.azure.storage.blob.options.BlockBlobStageBlockOptions;
import com.azure.storage.blob.specialized.BlockBlobClient;
import com.example.storage.AccessDeniedException;
import com.example.storage.MultipartSession;
import com.example.storage.ObjectMetadata;
import com.example.storage.StorageException;
import com.example.storage.UploadedPart;
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

class AzureBlobObjectStorageTest {

    private final BlockBlobClient blob = mock(BlockBlobClient.class);
    private MultipartSession session;

    @BeforeEach
    void setUp() {
        BlobContainerClient container = mock(BlobContainerClient.class);
        BlobClient blobClient = mock(BlobClient.class);
        when(container.getBlobClient("reports/r.csv")).thenReturn(blobClient);
        when(blobClient.getBlockBlobClient()).thenReturn(blob);

        ObjectMetadata metadata = new ObjectMetadata("text/csv", null, Map.of("tenant", "t1"))
                .withDownloadName("r.csv");
        session = AzureBlobObjectStorage.withSharedKey(container).initiateMultipart("reports/r.csv", metadata);
    }

    @Test
    void enviaSoOTrechoValidoDoBufferComMd5() throws Exception {
        byte[] buffer = {1, 2, 3, 4, 5, 6, 7, 8};

        UploadedPart part = session.uploadPart(1, buffer, 5);

        ArgumentCaptor<BlockBlobStageBlockOptions> captor = ArgumentCaptor.forClass(BlockBlobStageBlockOptions.class);
        verify(blob).stageBlockWithResponse(captor.capture(), any(), any(Context.class));
        BlockBlobStageBlockOptions options = captor.getValue();

        byte[] expected = Arrays.copyOf(buffer, 5);
        byte[] md5 = MessageDigest.getInstance("MD5").digest(expected);
        assertArrayEquals(expected, options.getData().toBytes());
        assertArrayEquals(md5, options.getContentMd5());
        assertEquals(options.getBase64BlockId(), part.etag());
        assertEquals(Base64.getEncoder().encodeToString(md5), part.checksum());
    }

    @Test
    void idsDeBlocoTemMesmoTamanhoEDiferemPorParte() {
        String id1 = session.uploadPart(1, new byte[1], 1).etag();
        String id10000 = session.uploadPart(10_000, new byte[1], 1).etag();

        assertEquals(id1.length(), id10000.length());
        assertNotEquals(id1, id10000);
    }

    @Test
    void parteVaziaNaoEnviaBlocoECommitCriaBlobVazio() {
        UploadedPart part = session.uploadPart(1, new byte[16], 0);
        session.complete(List.of(part));

        verify(blob, never()).stageBlockWithResponse(any(), any(), any());
        ArgumentCaptor<BlockBlobCommitBlockListOptions> captor =
                ArgumentCaptor.forClass(BlockBlobCommitBlockListOptions.class);
        verify(blob).commitBlockListWithResponse(captor.capture(), any(), any(Context.class));
        assertEquals(List.of(), captor.getValue().getBase64BlockIds());
    }

    @Test
    void commitOrdenaBlocosEAplicaMetadata() {
        session.complete(List.of(new UploadedPart(2, "b2", null), new UploadedPart(1, "b1", null)));

        ArgumentCaptor<BlockBlobCommitBlockListOptions> captor =
                ArgumentCaptor.forClass(BlockBlobCommitBlockListOptions.class);
        verify(blob).commitBlockListWithResponse(captor.capture(), any(), any(Context.class));
        BlockBlobCommitBlockListOptions options = captor.getValue();

        assertEquals(List.of("b1", "b2"), options.getBase64BlockIds());
        assertEquals("text/csv", options.getHeaders().getContentType());
        assertEquals("attachment; filename=\"r.csv\"", options.getHeaders().getContentDisposition());
        assertEquals(Map.of("tenant", "t1"), options.getMetadata());
    }

    @Test
    void abortNaoApagaOBlob() {
        session.abort();
        session.abort();

        verifyNoInteractions(blob);
    }

    @Test
    void falhaNoCommitViraStorageException() {
        when(blob.commitBlockListWithResponse(any(), any(), any())).thenThrow(new AzureException("boom"));

        assertThrows(StorageException.class, () -> session.complete(List.of(new UploadedPart(1, "b1", null))));
    }

    @Test
    void falhaNoEnvioMantemOTipoDaExcecao() {
        BlobStorageException forbidden = mock(BlobStorageException.class);
        when(forbidden.getStatusCode()).thenReturn(403);
        when(blob.stageBlockWithResponse(any(), any(), any(Context.class))).thenThrow(forbidden);

        assertThrows(AccessDeniedException.class, () -> session.uploadPart(1, new byte[1], 1));
    }
}
