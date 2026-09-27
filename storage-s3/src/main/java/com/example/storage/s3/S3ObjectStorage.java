package com.example.storage.s3;

import com.example.storage.ByteRange;
import com.example.storage.CommonPrefix;
import com.example.storage.ListEntry;
import com.example.storage.StorageStreams;
import com.example.storage.Condition;
import com.example.storage.DeleteResult;
import com.example.storage.MultipartSession;
import com.example.storage.ObjectInfo;
import com.example.storage.ObjectMetadata;
import com.example.storage.ObjectStorage;
import com.example.storage.ObjectSummary;
import com.example.storage.PreconditionFailedException;
import com.example.storage.PresignedRequest;
import com.example.storage.PutOptions;
import com.example.storage.StorageException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Implementação sobre AWS S3 (SDK v2, cliente síncrono). Funciona também com MinIO,
 * LocalStack, Ceph etc. via {@code endpointOverride(...)} + path-style.
 *
 * <p>O {@link S3Presigner} precisa da mesma configuração do cliente (região, credenciais,
 * endpoint, path-style): {@code S3Presigner.builder().s3Client(s3)} não herda o endpoint.</p>
 *
 * <p>URLs pré-assinadas (SigV4) valem no máximo 7 dias, limitadas também pela expiração
 * das credenciais. Em {@link #presignPut}, content-type, metadata e pré-condição são
 * assinados: o upload falha se o cliente não enviar os cabeçalhos devolvidos.</p>
 */
public final class S3ObjectStorage implements ObjectStorage {

    /** Limite do CopyObject; acima disso a cópia é feita em partes. */
    static final long MAX_SINGLE_COPY = 5L * 1024 * 1024 * 1024;
    private static final long COPY_PART_SIZE = 512L * 1024 * 1024;
    private static final int DELETE_BATCH = 1_000;

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final long maxSingleCopy;

    public S3ObjectStorage(S3Client s3, S3Presigner presigner, String bucket) {
        this(s3, presigner, bucket, MAX_SINGLE_COPY);
    }

    S3ObjectStorage(S3Client s3, S3Presigner presigner, String bucket, long maxSingleCopy) {
        this.s3 = Objects.requireNonNull(s3, "s3");
        this.presigner = Objects.requireNonNull(presigner, "presigner");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        this.maxSingleCopy = maxSingleCopy;
    }

    @Override
    public String put(String key, InputStream data, long length, PutOptions options) {
        try {
            return s3.putObject(putRequest(key, options).contentLength(length)
                            .checksumAlgorithm(ChecksumAlgorithm.CRC32).build(),
                    RequestBody.fromInputStream(data, length)).eTag();
        } catch (SdkException e) {
            if (options.condition() instanceof Condition.IfVersionMatches && status(e) == 404) {
                throw new PreconditionFailedException("Pré-condição falhou: " + uri(key) + " não existe", e);
            }
            throw translate(e, "Falha ao gravar " + uri(key));
        }
    }

    @Override
    public MultipartSession initiateMultipart(String key, ObjectMetadata metadata) {
        CreateMultipartUploadRequest.Builder request = CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(metadata.contentType())
                .contentDisposition(metadata.contentDisposition())
                .metadata(metadata.userMetadata())
                // Integridade por parte. Remova se o backend compatível não suportar.
                .checksumAlgorithm(ChecksumAlgorithm.CRC32);
        // .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(...) se aplicável

        try {
            CreateMultipartUploadResponse response = s3.createMultipartUpload(request.build());
            return new S3MultipartSession(s3, bucket, key, response.uploadId());
        } catch (SdkException e) {
            throw translate(e, "Falha ao iniciar upload multipart de " + uri(key));
        }
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        try {
            HeadObjectResponse r = s3.headObject(b -> b.bucket(bucket).key(key));
            return Optional.of(new ObjectInfo(key, r.contentLength(), r.eTag(), r.lastModified(),
                    new ObjectMetadata(r.contentType(), r.contentDisposition(), r.metadata())));
        } catch (SdkException e) {
            if (status(e) == 404) {
                return Optional.empty();
            }
            throw translate(e, "Falha ao consultar " + uri(key));
        }
    }

    @Override
    public InputStream open(String key, ByteRange range) {
        GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(bucket).key(key);
        if (!range.isAll()) {
            request.range("bytes=" + range.offset() + "-" + (range.toEnd() ? "" : range.lastByte()));
        }
        try {
            return s3.getObject(request.build());
        } catch (SdkException e) {
            throw translate(e, "Falha ao ler " + uri(key));
        }
    }

    @Override
    public Stream<ObjectSummary> list(String prefix) {
        return listing(prefix, s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix)).contents().stream()
                .map(S3ObjectStorage::summary));
    }

    @Override
    public Stream<ListEntry> listDirectory(String prefix) {
        // Cada página traz objetos e pastas em listas separadas; o S3 pagina na ordem combinada,
        // então ordenar dentro da página mantém a ordem global.
        return listing(prefix, s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix).delimiter("/")).stream()
                .flatMap(page -> Stream.concat(
                                page.contents().stream().<ListEntry>map(S3ObjectStorage::summary),
                                page.commonPrefixes().stream().map(p -> new CommonPrefix(p.prefix())))
                        .sorted(StorageStreams.BY_KEY)));
    }

    private <T> Stream<T> listing(String prefix, Stream<T> pages) {
        return StorageStreams.translating(pages, SdkException.class, e -> translate(e, "Falha ao listar " + uri(prefix)));
    }

    private static ObjectSummary summary(S3Object o) {
        return new ObjectSummary(o.key(), o.size(), o.eTag(), o.lastModified());
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(key));   // S3 já é idempotente
        } catch (SdkException e) {
            throw translate(e, "Falha ao apagar " + uri(key));
        }
    }

    @Override
    public DeleteResult deleteAll(Collection<String> keys) {
        Map<String, StorageException> failures = new LinkedHashMap<>();
        List<String> all = List.copyOf(keys);
        for (int start = 0; start < all.size(); start += DELETE_BATCH) {
            List<String> batch = all.subList(start, Math.min(all.size(), start + DELETE_BATCH));
            List<ObjectIdentifier> ids = batch.stream().map(k -> ObjectIdentifier.builder().key(k).build()).toList();
            try {
                DeleteObjectsResponse response = s3.deleteObjects(b -> b.bucket(bucket)
                        .delete(d -> d.objects(ids).quiet(true)));
                for (S3Error error : response.errors()) {
                    failures.put(error.key(), new StorageException(
                            "Falha ao apagar " + uri(error.key()) + ": " + error.code() + " " + error.message(), null));
                }
            } catch (SdkException e) {
                StorageException failure = translate(e, "Falha ao apagar lote em s3://" + bucket);
                batch.forEach(k -> failures.put(k, failure));
            }
        }
        return new DeleteResult(failures);
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        ObjectInfo source = head(sourceKey).orElseThrow(() -> new com.example.storage.ObjectNotFoundException(
                "Origem da cópia não existe: " + uri(sourceKey), null));
        try {
            if (source.size() <= maxSingleCopy) {
                s3.copyObject(b -> b.sourceBucket(bucket).sourceKey(sourceKey)
                        .destinationBucket(bucket).destinationKey(targetKey));
            } else {
                multipartCopy(source, targetKey);
            }
        } catch (SdkException e) {
            throw translate(e, "Falha ao copiar " + uri(sourceKey) + " para " + uri(targetKey));
        }
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        try {
            return presigner.presignGetObject(b -> b.signatureDuration(ttl)
                    .getObjectRequest(r -> r.bucket(bucket).key(key))).url().toURI();
        } catch (SdkException | URISyntaxException e) {
            throw new StorageException("Falha ao gerar URL temporária de " + uri(key), e);
        }
    }

    @Override
    public PresignedRequest presignPut(String key, Duration ttl, PutOptions options) {
        try {
            PresignedPutObjectRequest presigned = presigner.presignPutObject(b -> b.signatureDuration(ttl)
                    .putObjectRequest(putRequest(key, options).build()));
            // "host" é definido pelo cliente HTTP a partir da URL; os demais precisam ser enviados.
            Map<String, String> headers = presigned.signedHeaders().entrySet().stream()
                    .filter(e -> !e.getKey().equalsIgnoreCase("host"))
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> String.join(",", e.getValue())));
            return new PresignedRequest("PUT", presigned.url().toURI(), headers);
        } catch (SdkException | URISyntaxException e) {
            throw new StorageException("Falha ao gerar upload temporário de " + uri(key), e);
        }
    }

    private PutObjectRequest.Builder putRequest(String key, PutOptions options) {
        ObjectMetadata metadata = options.metadata();
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(metadata.contentType())
                .contentDisposition(metadata.contentDisposition())
                .metadata(metadata.userMetadata());
        switch (options.condition()) {
            case Condition.None none -> { }
            case Condition.IfNotExists ifNotExists -> request.ifNoneMatch("*");
            case Condition.IfVersionMatches match -> request.ifMatch(match.version());
        }
        return request;
    }

    // ponytail: partes copiadas em sequência; paralelizar se cópias > 5 GiB forem frequentes.
    private void multipartCopy(ObjectInfo source, String targetKey) {
        ObjectMetadata metadata = source.metadata();
        String uploadId = s3.createMultipartUpload(b -> b.bucket(bucket).key(targetKey)
                .contentType(metadata.contentType())
                .contentDisposition(metadata.contentDisposition())
                .metadata(metadata.userMetadata())).uploadId();
        try {
            List<CompletedPart> parts = new ArrayList<>();
            int partNumber = 1;
            for (long offset = 0; offset < source.size(); offset += COPY_PART_SIZE, partNumber++) {
                String range = "bytes=" + offset + "-" + (Math.min(source.size(), offset + COPY_PART_SIZE) - 1);
                int number = partNumber;
                String etag = s3.uploadPartCopy(b -> b.sourceBucket(bucket).sourceKey(source.key())
                                .destinationBucket(bucket).destinationKey(targetKey)
                                .uploadId(uploadId).partNumber(number).copySourceRange(range))
                        .copyPartResult().eTag();
                parts.add(CompletedPart.builder().partNumber(number).eTag(etag).build());
            }
            s3.completeMultipartUpload(b -> b.bucket(bucket).key(targetKey).uploadId(uploadId)
                    .multipartUpload(m -> m.parts(parts)));
        } catch (SdkException e) {
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(targetKey).uploadId(uploadId));
            throw e;
        }
    }

    private String uri(String key) {
        return "s3://" + bucket + "/" + key;
    }

    private static int status(SdkException e) {
        return e instanceof S3Exception s3e ? s3e.statusCode() : -1;
    }

    static StorageException translate(SdkException e, String message) {
        if (e instanceof S3Exception s3e) {
            String code = s3e.awsErrorDetails() == null ? null : s3e.awsErrorDetails().errorCode();
            if ("NoSuchBucket".equals(code)) {
                return new StorageException(message + " (bucket não existe)", e);
            }
            // Duas escritas condicionais simultâneas no mesmo objeto
            if (s3e.statusCode() == 409 && "ConditionalRequestConflict".equals(code)) {
                return new PreconditionFailedException(message, e);
            }
            return StorageException.fromHttpStatus(s3e.statusCode(), message, e);
        }
        return new StorageException(message, e);
    }
}
