package com.example.storage.oci;

import com.example.storage.MultipartSession;
import com.example.storage.UploadedPart;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.model.CommitMultipartUploadDetails;
import com.oracle.bmc.objectstorage.model.CommitMultipartUploadPartDetails;
import com.oracle.bmc.objectstorage.model.MultipartUploadPartSummary;
import com.oracle.bmc.objectstorage.requests.AbortMultipartUploadRequest;
import com.oracle.bmc.objectstorage.requests.CommitMultipartUploadRequest;
import com.oracle.bmc.objectstorage.requests.ListMultipartUploadPartsRequest;
import com.oracle.bmc.objectstorage.requests.UploadPartRequest;
import com.oracle.bmc.objectstorage.responses.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

final class OciMultipartSession implements MultipartSession {

    private static final int NOT_FOUND = 404;

    private final com.oracle.bmc.objectstorage.ObjectStorage client;
    private final String namespace;
    private final String bucket;
    private final String key;
    private final String uploadId;

    OciMultipartSession(com.oracle.bmc.objectstorage.ObjectStorage client,
                        String namespace, String bucket, String key, String uploadId) {
        this.client = client;
        this.namespace = namespace;
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
        String md5 = md5Base64(data, length);
        UploadPartRequest request = UploadPartRequest.builder()
                .namespaceName(namespace)
                .bucketName(bucket)
                .objectName(key)
                .uploadId(uploadId)
                .uploadPartNum(partNumber)
                .contentLength((long) length)
                .contentMD5(md5)   // o servidor valida a integridade da parte
                // ByteArrayInputStream suporta mark/reset, então os retries do SDK
                // reenviam os mesmos bytes sem copiar o buffer.
                .uploadPartBody(new ByteArrayInputStream(data, 0, length))
                .build();
        try {
            UploadPartResponse response = client.uploadPart(request);
            return new UploadedPart(partNumber, response.getETag(), md5);
        } catch (BmcException e) {
            throw OciObjectStorage.translate(e, "Falha ao enviar parte " + partNumber + " de " + key);
        }
    }

    @Override
    public void complete(List<UploadedPart> parts) {
        List<CommitMultipartUploadPartDetails> toCommit = parts.stream()
                .sorted(Comparator.comparingInt(UploadedPart::partNumber))
                .map(p -> CommitMultipartUploadPartDetails.builder()
                        .partNum(p.partNumber())
                        .etag(p.etag())
                        .build())
                .toList();
        try {
            client.commitMultipartUpload(CommitMultipartUploadRequest.builder()
                    .namespaceName(namespace)
                    .bucketName(bucket)
                    .objectName(key)
                    .uploadId(uploadId)
                    .commitMultipartUploadDetails(CommitMultipartUploadDetails.builder()
                            .partsToCommit(toCommit)
                            .build())
                    .build());
        } catch (BmcException e) {
            throw OciObjectStorage.translate(e, "Falha ao concluir upload de " + key);
        }
    }

    @Override
    public void abort() {
        try {
            client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .namespaceName(namespace)
                    .bucketName(bucket)
                    .objectName(key)
                    .uploadId(uploadId)
                    .build());
        } catch (BmcException e) {
            if (e.getStatusCode() != NOT_FOUND) {   // 404: já abortado/concluído (idempotente)
                throw OciObjectStorage.translate(e, "Falha ao abortar upload de " + key);
            }
        }
    }

    @Override
    public List<UploadedPart> listParts() {
        ListMultipartUploadPartsRequest request = ListMultipartUploadPartsRequest.builder()
                .namespaceName(namespace)
                .bucketName(bucket)
                .objectName(key)
                .uploadId(uploadId)
                .build();
        List<UploadedPart> result = new ArrayList<>();
        try {
            for (MultipartUploadPartSummary p : client.getPaginators().listMultipartUploadPartsRecordIterator(request)) {
                result.add(new UploadedPart(p.getPartNumber(), p.getEtag(), p.getMd5()));
            }
            return result;
        } catch (BmcException e) {
            if (e.getStatusCode() == NOT_FOUND) {
                return List.of();   // concluído/abortado
            }
            throw OciObjectStorage.translate(e, "Falha ao listar partes do upload de " + key);
        }
    }

    private static String md5Base64(byte[] data, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(data, 0, length);
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 indisponível na JVM", e);
        }
    }
}
