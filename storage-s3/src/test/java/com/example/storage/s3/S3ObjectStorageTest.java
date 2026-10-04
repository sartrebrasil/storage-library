package com.example.storage.s3;

import com.example.storage.AccessDeniedException;
import com.example.storage.MultipartSession;
import com.example.storage.ObjectMetadata;
import com.example.storage.ObjectNotFoundException;
import com.example.storage.PutOptions;
import com.example.storage.StorageException;
import com.example.storage.UploadedPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.UploadPartCopyRequest;
import software.amazon.awssdk.services.s3.model.UploadPartCopyResponse;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3ObjectStorageTest {

    private final S3Client s3 = mock(S3Client.class);
    private MultipartSession session;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // complete/abort usam as sobrecargas com Consumer<Builder>, que são métodos
        // default delegando para a versão com request: deixa o mock executá-los.
        doCallRealMethod().when(s3).completeMultipartUpload(any(Consumer.class));
        doCallRealMethod().when(s3).abortMultipartUpload(any(Consumer.class));

        when(s3.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("up-1").build());
        ObjectMetadata metadata = new ObjectMetadata("text/csv", null, Map.of("tenant", "t1"))
                .withDownloadName("r.csv");
        session = new S3ObjectStorage(s3, mock(S3Presigner.class), "bucket").initiateMultipart("reports/r.csv", metadata);
    }

    @Test
    void iniciaUploadComMetadataEChecksum() {
        ArgumentCaptor<CreateMultipartUploadRequest> captor =
                ArgumentCaptor.forClass(CreateMultipartUploadRequest.class);
        verify(s3).createMultipartUpload(captor.capture());
        CreateMultipartUploadRequest request = captor.getValue();

        assertEquals("bucket", request.bucket());
        assertEquals("reports/r.csv", request.key());
        assertEquals("text/csv", request.contentType());
        assertEquals("attachment; filename=\"r.csv\"", request.contentDisposition());
        assertEquals(Map.of("tenant", "t1"), request.metadata());
        assertEquals(ChecksumAlgorithm.CRC32, request.checksumAlgorithm());
        assertEquals("reports/r.csv", session.key());
    }

    @Test
    void enviaSoOTrechoValidoDoBufferEPermiteRetry() throws IOException {
        when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenReturn(UploadPartResponse.builder().eTag("e2").checksumCRC32("c2").build());
        byte[] buffer = {1, 2, 3, 4, 5, 6, 7, 8};

        UploadedPart part = session.uploadPart(2, buffer, 5);

        ArgumentCaptor<UploadPartRequest> request = ArgumentCaptor.forClass(UploadPartRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).uploadPart(request.capture(), body.capture());

        assertEquals("up-1", request.getValue().uploadId());
        assertEquals(2, request.getValue().partNumber());
        assertEquals(5L, request.getValue().contentLength());
        assertEquals(ChecksumAlgorithm.CRC32, request.getValue().checksumAlgorithm());
        assertEquals(new UploadedPart(2, "e2", "c2"), part);

        // Cada tentativa do SDK abre um stream novo com os mesmos bytes.
        byte[] expected = Arrays.copyOf(buffer, 5);
        for (int attempt = 0; attempt < 2; attempt++) {
            try (InputStream in = body.getValue().contentStreamProvider().newStream()) {
                assertArrayEquals(expected, in.readAllBytes());
            }
        }
    }

    @Test
    void checksumNoneNaoPedeChecksumEmNenhumaEscrita() {
        S3ObjectStorage storage = new S3ObjectStorage(s3, mock(S3Presigner.class), "bucket", S3ObjectStorage.Checksum.NONE);
        when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenReturn(UploadPartResponse.builder().eTag("e1").checksumCRC32("ignorado").build());
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("v1").build());

        MultipartSession noChecksum = storage.initiateMultipart("k", ObjectMetadata.of("text/csv"));
        UploadedPart part = noChecksum.uploadPart(1, new byte[3], 3);
        storage.put("p", new byte[3], PutOptions.of("text/plain"));

        ArgumentCaptor<CreateMultipartUploadRequest> create = ArgumentCaptor.forClass(CreateMultipartUploadRequest.class);
        verify(s3, times(2)).createMultipartUpload(create.capture());
        assertNull(create.getAllValues().get(1).checksumAlgorithm());
        ArgumentCaptor<UploadPartRequest> upload = ArgumentCaptor.forClass(UploadPartRequest.class);
        verify(s3).uploadPart(upload.capture(), any(RequestBody.class));
        assertNull(upload.getValue().checksumAlgorithm());
        assertNull(part.checksum(), "sem checksum pedido, a conclusão não pode enviar um");
        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(put.capture(), any(RequestBody.class));
        assertNull(put.getValue().checksumAlgorithm());
    }

    @Test
    void concluiComPartesOrdenadas() {
        session.complete(List.of(new UploadedPart(2, "e2", "c2"), new UploadedPart(1, "e1", "c1")));

        ArgumentCaptor<CompleteMultipartUploadRequest> captor =
                ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        verify(s3).completeMultipartUpload(captor.capture());
        List<CompletedPart> parts = captor.getValue().multipartUpload().parts();

        assertEquals("up-1", captor.getValue().uploadId());
        assertEquals(List.of(1, 2), parts.stream().map(CompletedPart::partNumber).toList());
        assertEquals(List.of("e1", "e2"), parts.stream().map(CompletedPart::eTag).toList());
        assertEquals(List.of("c1", "c2"), parts.stream().map(CompletedPart::checksumCRC32).toList());
    }

    @Test
    void checksumSha256LidoDaParteEEnviadoNaConclusao() {
        S3ObjectStorage storage = new S3ObjectStorage(s3, mock(S3Presigner.class), "bucket", S3ObjectStorage.Checksum.SHA256);
        when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenReturn(UploadPartResponse.builder().eTag("e1").checksumCRC32("errado").checksumSHA256("s1").build());

        MultipartSession sha256 = storage.initiateMultipart("k", ObjectMetadata.of("text/csv"));
        UploadedPart part = sha256.uploadPart(1, new byte[3], 3);
        sha256.complete(List.of(part));

        assertEquals("s1", part.checksum());
        ArgumentCaptor<CompleteMultipartUploadRequest> captor =
                ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        verify(s3).completeMultipartUpload(captor.capture());
        CompletedPart completed = captor.getValue().multipartUpload().parts().get(0);
        assertEquals("s1", completed.checksumSHA256());
        assertNull(completed.checksumCRC32());
    }

    @Test
    void abortIgnoraUploadInexistente() {
        when(s3.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                .thenThrow(NoSuchUploadException.builder().message("gone").build());

        assertDoesNotThrow(session::abort);

        ArgumentCaptor<AbortMultipartUploadRequest> captor = ArgumentCaptor.forClass(AbortMultipartUploadRequest.class);
        verify(s3).abortMultipartUpload(captor.capture());
        assertEquals("up-1", captor.getValue().uploadId());
    }

    @Test
    void abortPropagaOutrosErros() {
        when(s3.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(500).message("boom").build());

        assertThrows(StorageException.class, session::abort);
    }

    @Test
    void falhaNoEnvioViraStorageException() {
        when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(503).message("slow down").build());

        StorageException e = assertThrows(StorageException.class, () -> session.uploadPart(1, new byte[1], 1));
        assertInstanceOf(S3Exception.class, e.getCause());
    }

    @Test
    void falhasDaSessaoMantemOTipoDaExcecao() {
        when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(403).message("denied").build());
        when(s3.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("NoSuchUpload").build());

        assertThrows(AccessDeniedException.class, () -> session.uploadPart(1, new byte[1], 1));
        assertThrows(ObjectNotFoundException.class, () -> session.complete(List.of(new UploadedPart(1, "e1", null))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void headDevolveMetadataGravadaPorOutrasFerramentas() {
        doCallRealMethod().when(s3).headObject(any(Consumer.class));
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(1L).eTag("e").metadata(Map.of("s3cmd-attrs", "uid:0/gid:0")).build());

        var info = new S3ObjectStorage(s3, mock(S3Presigner.class), "bucket").head("k").orElseThrow();

        assertEquals(Map.of("s3cmd-attrs", "uid:0/gid:0"), info.metadata().userMetadata());
    }

    @Test
    @SuppressWarnings("unchecked")
    void copiaAcimaDoLimiteUsaPartesComMetadataDaOrigem() {
        long gib = 1024L * 1024 * 1024;
        doCallRealMethod().when(s3).headObject(any(Consumer.class));
        doCallRealMethod().when(s3).createMultipartUpload(any(Consumer.class));
        doCallRealMethod().when(s3).uploadPartCopy(any(Consumer.class));
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(gib + gib / 5).eTag("src").contentType("text/csv").metadata(Map.of("tenant", "t1"))
                .build());
        when(s3.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("copy-1").build());
        when(s3.uploadPartCopy(any(UploadPartCopyRequest.class))).thenAnswer(inv -> UploadPartCopyResponse.builder()
                .copyPartResult(r -> r.eTag("p" + inv.getArgument(0, UploadPartCopyRequest.class).partNumber()))
                .build());
        // Limite de cópia simples em 1 byte para forçar o caminho em partes (512 MiB cada).
        S3ObjectStorage storage = new S3ObjectStorage(s3, mock(S3Presigner.class), "bucket", 1);

        storage.copy("src.csv", "dst.csv");

        ArgumentCaptor<UploadPartCopyRequest> parts = ArgumentCaptor.forClass(UploadPartCopyRequest.class);
        verify(s3, times(3)).uploadPartCopy(parts.capture());
        assertEquals(List.of("bytes=0-536870911", "bytes=536870912-1073741823", "bytes=1073741824-1288490187"),
                parts.getAllValues().stream().map(UploadPartCopyRequest::copySourceRange).toList());
        ArgumentCaptor<CreateMultipartUploadRequest> create = ArgumentCaptor.forClass(CreateMultipartUploadRequest.class);
        verify(s3, atLeastOnce()).createMultipartUpload(create.capture());
        CreateMultipartUploadRequest target = create.getAllValues().getLast();
        assertEquals("dst.csv", target.key());
        assertEquals("text/csv", target.contentType());
        assertEquals(Map.of("tenant", "t1"), target.metadata());
        ArgumentCaptor<CompleteMultipartUploadRequest> complete =
                ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        verify(s3).completeMultipartUpload(complete.capture());
        assertEquals(List.of("p1", "p2", "p3"),
                complete.getValue().multipartUpload().parts().stream().map(CompletedPart::eTag).toList());
        verify(s3, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void todoChecksumResolveNoSdkPeloNome() {
        for (S3ObjectStorage.Checksum checksum : S3ObjectStorage.Checksum.values()) {
            if (checksum == S3ObjectStorage.Checksum.NONE) {
                assertNull(checksum.algorithm());
            } else {
                assertNotEquals(software.amazon.awssdk.services.s3.model.ChecksumAlgorithm.UNKNOWN_TO_SDK_VERSION,
                        checksum.algorithm(), checksum::name);
            }
        }
    }
}
