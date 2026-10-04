package com.example.storage.azure;

import com.azure.core.util.Context;
import com.azure.core.util.polling.SyncPoller;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobCopyInfo;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.specialized.BlockBlobClient;
import com.example.storage.ObjectMetadata;
import com.example.storage.ObjectNotFoundException;
import com.example.storage.PreconditionFailedException;
import com.example.storage.PutOptions;
import com.example.storage.StorageException;
import com.example.storage.UploadedPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AzureBlobOperationsTest {

    private final BlobContainerClient container = mock(BlobContainerClient.class);
    private final BlobClient source = mock(BlobClient.class);
    private final BlobClient target = mock(BlobClient.class);
    private final BlockBlobClient targetBlock = mock(BlockBlobClient.class);
    @SuppressWarnings("unchecked")
    private final SyncPoller<BlobCopyInfo, Void> poller = mock(SyncPoller.class);
    private final AzureBlobObjectStorage storage = AzureBlobObjectStorage.withSharedKey(container);

    @BeforeEach
    void setUp() {
        when(container.getBlobClient("origem")).thenReturn(source);
        when(container.getBlobClient("destino")).thenReturn(target);
        when(source.getBlobUrl()).thenReturn("https://conta.blob.core.windows.net/c/origem");
        when(target.beginCopy(anyString(), any(Duration.class))).thenReturn(poller);
        when(target.getBlockBlobClient()).thenReturn(targetBlock);
        BlobProperties copying = mock(BlobProperties.class);
        when(copying.getCopyId()).thenReturn("copy-1");
        when(target.getProperties()).thenReturn(copying);
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private static BlobStorageException blobError(int status, BlobErrorCode code) {
        BlobStorageException e = mock(BlobStorageException.class);
        when(e.getStatusCode()).thenReturn(status);
        when(e.getErrorCode()).thenReturn(code);
        return e;
    }

    @Test
    void copiaQueEstouraOTimeoutViraStorageExceptionEAbortaACopia() {
        when(poller.waitForCompletion(any())).thenThrow(new RuntimeException(new TimeoutException("timeout")));

        StorageException e = assertThrows(StorageException.class, () -> storage.copy("origem", "destino"));

        assertEquals(StorageException.class, e.getClass());
        verify(target).abortCopyFromUrl("copy-1");
    }

    @Test
    void copiaInterrompidaPreservaOFlagDeInterrupcaoEAbortaACopia() {
        when(poller.waitForCompletion(any())).thenThrow(new RuntimeException(new InterruptedException()));

        assertThrows(StorageException.class, () -> storage.copy("origem", "destino"));

        assertTrue(Thread.currentThread().isInterrupted());
        verify(target).abortCopyFromUrl("copy-1");
    }

    @Test
    void falhaDeUmPollDaCopiaEhTraduzida() {
        // Fora do when(): o construtor de ExecutionException chama toString() do mock.
        RuntimeException pollFailure = new RuntimeException(
                new ExecutionException(blobError(404, BlobErrorCode.BLOB_NOT_FOUND)));
        when(poller.waitForCompletion(any())).thenThrow(pollFailure);

        assertThrows(ObjectNotFoundException.class, () -> storage.copy("origem", "destino"));
    }

    @Test
    void putCondicionalEmContainerInexistenteNaoViraPrecondicao() {
        BlobStorageException missing = blobError(404, BlobErrorCode.CONTAINER_NOT_FOUND);
        when(targetBlock.uploadWithResponse(any(), any(), any(Context.class))).thenThrow(missing);

        StorageException e = assertThrows(StorageException.class, () -> storage.put("destino", new byte[1],
                PutOptions.of("text/plain").ifVersionMatches("\"e1\"")));

        assertFalse(e instanceof PreconditionFailedException, e.toString());
    }

    @Test
    void commitComBlocosDescartadosExplicaACausa() {
        BlobStorageException invalid = blobError(400, BlobErrorCode.INVALID_BLOCK_LIST);
        when(targetBlock.commitBlockListWithResponse(any(), any(), any(Context.class))).thenThrow(invalid);
        var session = storage.initiateMultipart("destino", ObjectMetadata.empty());

        StorageException e = assertThrows(StorageException.class,
                () -> session.complete(List.of(new UploadedPart(1, "b1", null))));

        assertTrue(e.getMessage().contains("outro upload"), e.getMessage());
    }
}
