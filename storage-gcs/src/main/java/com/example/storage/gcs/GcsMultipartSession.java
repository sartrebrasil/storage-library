package com.example.storage.gcs;

import com.example.storage.MultipartSession;
import com.example.storage.StorageException;
import com.example.storage.UploadedPart;
import com.google.cloud.BaseServiceException;
import com.google.cloud.storage.MultipartUploadClient;
import com.google.cloud.storage.RequestBody;
import com.google.cloud.storage.multipartupload.model.AbortMultipartUploadRequest;
import com.google.cloud.storage.multipartupload.model.CompleteMultipartUploadRequest;
import com.google.cloud.storage.multipartupload.model.CompletedMultipartUpload;
import com.google.cloud.storage.multipartupload.model.CompletedPart;
import com.google.cloud.storage.multipartupload.model.ListPartsRequest;
import com.google.cloud.storage.multipartupload.model.ListPartsResponse;
import com.google.cloud.storage.multipartupload.model.UploadPartRequest;
import com.google.cloud.storage.multipartupload.model.UploadPartResponse;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class GcsMultipartSession implements MultipartSession {

    private static final int NOT_FOUND = 404;

    private final MultipartUploadClient client;
    private final String bucket;
    private final String key;
    private final String uploadId;

    GcsMultipartSession(MultipartUploadClient client, String bucket, String key, String uploadId) {
        this.client = client;
        this.bucket = bucket;
        this.key = key;
        this.uploadId = uploadId;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public String uploadId() {
        return uploadId;
    }

    @Override
    public UploadedPart uploadPart(int partNumber, byte[] data, int length) {
        UploadPartRequest request = UploadPartRequest.builder()
                .bucket(bucket)
                .key(key)
                .uploadId(uploadId)
                .partNumber(partNumber)
                .build();

        // RequestBody sobre ByteBuffer é "rewindable": o SDK reenvia os mesmos
        // bytes nos retries sem copiar o buffer. Sem crc32c explícito, o SDK
        // calcula e envia o x-goog-hash para o servidor validar a parte.
        RequestBody body = RequestBody.of(ByteBuffer.wrap(data, 0, length));

        try {
            UploadPartResponse response = client.uploadPart(request, body);
            return new UploadedPart(partNumber, response.eTag(), response.crc32c());
        } catch (BaseServiceException e) {
            throw new StorageException("Falha ao enviar parte " + partNumber + " de " + key, e);
        }
    }

    @Override
    public void complete(List<UploadedPart> parts) {
        List<CompletedPart> completed = parts.stream()
                .sorted(Comparator.comparingInt(UploadedPart::partNumber))
                .map(p -> CompletedPart.builder().partNumber(p.partNumber()).eTag(p.etag()).build())
                .toList();
        try {
            client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build())
                    .build());
        } catch (BaseServiceException e) {
            throw new StorageException("Falha ao concluir upload de " + key, e);
        }
    }

    @Override
    public void abort() {
        try {
            client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .build());
        } catch (BaseServiceException e) {
            if (e.getCode() != NOT_FOUND) {   // 404: já abortado/concluído (idempotente)
                throw new StorageException("Falha ao abortar upload de " + key, e);
            }
        }
    }

    @Override
    public List<UploadedPart> listParts() {
        List<UploadedPart> result = new ArrayList<>();
        Integer marker = null;
        try {
            ListPartsResponse page;
            do {
                page = client.listParts(ListPartsRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .uploadId(uploadId)
                        .partNumberMarker(marker)
                        .build());
                page.parts().forEach(p -> result.add(new UploadedPart(p.partNumber(), p.eTag(), null)));
                marker = page.nextPartNumberMarker();
            } while (page.truncated());
            return result;
        } catch (BaseServiceException e) {
            if (e.getCode() == NOT_FOUND) {
                return List.of();   // concluído/abortado
            }
            throw new StorageException("Falha ao listar partes do upload de " + key, e);
        }
    }
}
