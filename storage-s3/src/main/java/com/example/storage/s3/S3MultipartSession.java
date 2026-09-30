package com.example.storage.s3;

import com.example.storage.MultipartSession;
import com.example.storage.StorageException;
import com.example.storage.UploadedPart;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.GetObjectAttributesParts;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.ObjectAttributes;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class S3MultipartSession implements MultipartSession {

    private final S3Client s3;
    private final String bucket;
    private final String key;
    private final String uploadId;
    private final ChecksumAlgorithm checksum;   // null: sem checksum pedido

    S3MultipartSession(S3Client s3, String bucket, String key, String uploadId, ChecksumAlgorithm checksum) {
        this.s3 = s3;
        this.bucket = bucket;
        this.key = key;
        this.uploadId = uploadId;
        this.checksum = checksum;
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
                .contentLength((long) length)
                .checksumAlgorithm(checksum)
                .build();

        // fromContentProvider evita copiar o buffer e cria um stream novo a
        // cada tentativa, então os retries automáticos do SDK funcionam.
        RequestBody body = RequestBody.fromContentProvider(
                () -> new ByteArrayInputStream(data, 0, length), length, "application/octet-stream");

        try {
            UploadPartResponse response = s3.uploadPart(request, body);
            return new UploadedPart(partNumber, response.eTag(), checksum == null ? null : response.checksumCRC32());
        } catch (SdkException e) {
            throw new StorageException("Falha ao enviar parte " + partNumber + " de " + key, e);
        }
    }

    @Override
    public void complete(List<UploadedPart> parts) {
        List<CompletedPart> completed = parts.stream()
                .sorted(Comparator.comparingInt(UploadedPart::partNumber))
                .map(p -> CompletedPart.builder()
                        .partNumber(p.partNumber())
                        .eTag(p.etag())
                        .checksumCRC32(p.checksum())
                        .build())
                .toList();
        try {
            s3.completeMultipartUpload(b -> b
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(m -> m.parts(completed)));
        } catch (SdkException e) {
            throw new StorageException("Falha ao concluir upload de " + key, e);
        }
    }

    @Override
    public void abort() {
        try {
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId));
        } catch (NoSuchUploadException alreadyGone) {
            // idempotente: já abortado/concluído
        } catch (SdkException e) {
            throw new StorageException("Falha ao abortar upload de " + key, e);
        }
    }

    @Override
    public List<UploadedPart> listParts() {
        try {
            // o paginator é lazy: as páginas são buscadas no toList(), dentro do try
            return s3.listPartsPaginator(b -> b.bucket(bucket).key(key).uploadId(uploadId))
                    .parts().stream()
                    .map(p -> new UploadedPart(p.partNumber(), p.eTag(), p.checksumCRC32()))
                    .toList();
        } catch (NoSuchUploadException alreadyGone) {
            return List.of();   // concluído/abortado
        } catch (SdkException e) {
            throw new StorageException("Falha ao listar partes do upload de " + key, e);
        }
    }

    /** A S3 só devolve a lista de partes se o upload usou checksum; sem ele, a lista vem vazia. */
    @Override
    public List<UploadedPart> listCompletedParts() {
        List<UploadedPart> result = new ArrayList<>();
        Integer marker = null;
        try {
            GetObjectAttributesParts page;
            do {
                Integer current = marker;
                page = s3.getObjectAttributes(b -> b.bucket(bucket).key(key)
                        .objectAttributes(ObjectAttributes.OBJECT_PARTS)
                        .partNumberMarker(current)).objectParts();
                if (page == null) {
                    return List.of();   // objeto não veio de multipart
                }
                page.parts().forEach(p -> result.add(new UploadedPart(p.partNumber(), null, p.checksumCRC32())));
                marker = page.nextPartNumberMarker();
            } while (Boolean.TRUE.equals(page.isTruncated()));
            return result;
        } catch (SdkException e) {
            throw new StorageException("Falha ao listar partes concluídas de " + key, e);
        }
    }
}
