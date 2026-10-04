package com.example.storage.oci;

import com.example.storage.ByteRange;
import com.example.storage.CommonPrefix;
import com.example.storage.ListEntry;
import com.example.storage.StorageStreams;
import com.example.storage.Condition;
import com.example.storage.MultipartSession;
import com.example.storage.ObjectContent;
import com.example.storage.ObjectInfo;
import com.example.storage.ObjectMetadata;
import com.example.storage.ObjectNotFoundException;
import com.example.storage.ObjectStorage;
import com.example.storage.ObjectSummary;
import com.example.storage.PreconditionFailedException;
import com.example.storage.PresignedRequest;
import com.example.storage.PutOptions;
import com.example.storage.StorageException;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.model.Range;
import com.oracle.bmc.objectstorage.model.CopyObjectDetails;
import com.oracle.bmc.objectstorage.model.CreateMultipartUploadDetails;
import com.oracle.bmc.objectstorage.model.CreatePreauthenticatedRequestDetails;
import com.oracle.bmc.objectstorage.model.ListObjects;
import com.oracle.bmc.objectstorage.model.WorkRequest;
import com.oracle.bmc.objectstorage.requests.CopyObjectRequest;
import com.oracle.bmc.objectstorage.requests.CreateMultipartUploadRequest;
import com.oracle.bmc.objectstorage.requests.CreatePreauthenticatedRequestRequest;
import com.oracle.bmc.objectstorage.requests.DeleteObjectRequest;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.objectstorage.requests.GetWorkRequestRequest;
import com.oracle.bmc.objectstorage.requests.HeadBucketRequest;
import com.oracle.bmc.objectstorage.requests.HeadObjectRequest;
import com.oracle.bmc.objectstorage.requests.ListObjectsRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.GetObjectResponse;
import com.oracle.bmc.objectstorage.responses.HeadObjectResponse;

import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Implementação sobre OCI Object Storage (API nativa).
 *
 * <pre>{@code
 * var client = ObjectStorageClient.builder()
 *         .build(new ConfigFileAuthenticationDetailsProvider("DEFAULT"));
 * String namespace = client.getNamespace(GetNamespaceRequest.builder().build()).getValue();
 * }</pre>
 *
 * <p>URLs temporárias são Pre-Authenticated Requests (PAR): cada uma é um recurso criado no
 * bucket (uma chamada à API), revogável antes de expirar, e exige {@code PAR_MANAGE}. Em
 * {@link #presignPut}, content-type, metadata e pré-condição são cabeçalhos que o cliente
 * envia, sem imposição pelo PAR.</p>
 *
 * <p>{@link #copy} é assíncrona na OCI: a implementação acompanha a work request até
 * concluir. {@link #deleteAll} usa o padrão da interface (a OCI não apaga em lote).</p>
 */
public final class OciObjectStorage implements ObjectStorage {

    private static final String USER_METADATA_PREFIX = "opc-meta-";
    // ponytail: timeout fixo da cópia assíncrona; expor na configuração se houver cópias muito grandes.
    private static final Duration COPY_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration MAX_POLL_INTERVAL = Duration.ofSeconds(5);

    private final com.oracle.bmc.objectstorage.ObjectStorage client;
    private final String namespace;
    private final String bucket;
    private final Clock clock;
    private final Duration firstPollInterval;

    public OciObjectStorage(com.oracle.bmc.objectstorage.ObjectStorage client, String namespace, String bucket) {
        this(client, namespace, bucket, Clock.systemUTC(), Duration.ofMillis(500));
    }

    OciObjectStorage(com.oracle.bmc.objectstorage.ObjectStorage client, String namespace, String bucket,
                     Clock clock, Duration firstPollInterval) {
        this.client = Objects.requireNonNull(client, "client");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        this.clock = clock;
        this.firstPollInterval = firstPollInterval;
    }

    @Override
    public String put(String key, InputStream data, long length, PutOptions options) {
        ObjectMetadata metadata = options.metadata();
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .namespaceName(namespace)
                .bucketName(bucket)
                .objectName(key)
                .contentLength(length)
                .putObjectBody(data)
                .contentType(metadata.contentType())
                .contentDisposition(metadata.contentDisposition())
                // Sem prefixo: o ObjectMetadataInterceptor do SDK acrescenta "opc-meta-" a cada chave.
                .opcMeta(metadata.userMetadata());
        switch (options.condition()) {
            case Condition.None none -> { }
            case Condition.IfNotExists ifNotExists -> request.ifNoneMatch("*");
            case Condition.IfVersionMatches match -> request.ifMatch(match.version());
        }
        try {
            return client.putObject(request.build()).getETag();
        } catch (BmcException e) {
            if (options.condition() instanceof Condition.IfVersionMatches && e.getStatusCode() == 404) {
                throw new PreconditionFailedException("Pré-condição falhou: " + uri(key) + " não existe", e);
            }
            throw translate(e, "Falha ao gravar " + uri(key));
        }
    }

    @Override
    public MultipartSession initiateMultipart(String key, ObjectMetadata metadata) {
        CreateMultipartUploadDetails details = CreateMultipartUploadDetails.builder()
                .object(key)
                .contentType(metadata.contentType())
                .contentDisposition(metadata.contentDisposition())
                .metadata(metadata.userMetadata())
                .build();
        try {
            String uploadId = client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                            .namespaceName(namespace)
                            .bucketName(bucket)
                            .createMultipartUploadDetails(details)
                            .build())
                    .getMultipartUpload()
                    .getUploadId();
            return new OciMultipartSession(client, namespace, bucket, key, uploadId);
        } catch (BmcException e) {
            throw translate(e, "Falha ao iniciar upload multipart de " + uri(key));
        }
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        try {
            HeadObjectResponse r = client.headObject(HeadObjectRequest.builder()
                    .namespaceName(namespace).bucketName(bucket).objectName(key).build());
            return Optional.of(new ObjectInfo(key, r.getContentLength(), r.getETag(), r.getLastModified().toInstant(),
                    new ObjectMetadata(r.getContentType(), r.getContentDisposition(), unprefixed(r.getOpcMeta()))));
        } catch (BmcException e) {
            if (e.getStatusCode() == 404 && !bucketMissing(e)) {
                return Optional.empty();
            }
            throw translate(e, "Falha ao consultar " + uri(key));
        }
    }

    @Override
    public InputStream open(String key, ByteRange range) {
        return read(key, range).stream();
    }

    /** Uma chamada só: a resposta traz o tamanho e a faixa servida, com o total do objeto. */
    @Override
    public ObjectContent read(String key, ByteRange range) {
        GetObjectRequest.Builder request = GetObjectRequest.builder()
                .namespaceName(namespace).bucketName(bucket).objectName(key);
        if (range.isSuffix()) {
            request.range(new Range(null, range.length()));   // bytes=-n
        } else if (!range.isAll()) {
            request.range(new Range(range.offset(), range.toEnd() ? null : range.lastByte()));
        }
        try {
            GetObjectResponse response = client.getObject(request.build());
            Range served = response.getContentRange();
            if (served == null) {
                return new ObjectContent(response.getInputStream(), ByteRange.all(), response.getContentLength());
            }
            return new ObjectContent(response.getInputStream(),
                    ByteRange.of(served.getStartByte(), served.getEndByte() - served.getStartByte() + 1),
                    served.getContentLength());
        } catch (BmcException e) {
            throw translate(e, "Falha ao ler " + uri(key));
        }
    }

    @Override
    public void checkAccess() {
        try {
            client.headBucket(HeadBucketRequest.builder().namespaceName(namespace).bucketName(bucket).build());
        } catch (BmcException e) {
            // HEAD não tem corpo: o 404 de bucket inexistente chega sem o código BucketNotFound
            throw e.getStatusCode() == 404 ? new StorageException("Bucket não existe: " + uri(""), e)
                    : translate(e, "Falha ao acessar " + uri(""));
        }
    }

    @Override
    public Stream<ObjectSummary> list(String prefix) {
        ListObjectsRequest request = ListObjectsRequest.builder()
                .namespaceName(namespace)
                .bucketName(bucket)
                .prefix(prefix)
                .fields("name,size,etag,timeModified")
                .build();
        // O paginator do SDK busca a próxima página (nextStartWith) sob demanda.
        Iterable<com.oracle.bmc.objectstorage.model.ObjectSummary> objects =
                client.getPaginators().listObjectsRecordIterator(request);
        return StorageStreams.translating(
                StreamSupport.stream(objects.spliterator(), false).map(OciObjectStorage::summary),
                BmcException.class, e -> translate(e, "Falha ao listar " + uri(prefix)));
    }

    @Override
    public Stream<ListEntry> listDirectory(String prefix) {
        // O paginator do SDK só expõe objetos; as pastas (prefixes) exigem paginar à mão.
        Function<String, ListObjects> fetch = start -> {
            try {
                return client.listObjects(ListObjectsRequest.builder()
                        .namespaceName(namespace)
                        .bucketName(bucket)
                        .prefix(prefix)
                        .delimiter("/")
                        .start(start)
                        .fields("name,size,etag,timeModified")
                        .build()).getListObjects();
            } catch (BmcException e) {
                throw translate(e, "Falha ao listar " + uri(prefix));
            }
        };
        Set<String> seen = new HashSet<>();
        return Stream.iterate(fetch.apply(null), Objects::nonNull,
                        page -> page.getNextStartWith() == null ? null : fetch.apply(page.getNextStartWith()))
                .flatMap(page -> Stream.concat(
                                page.getObjects().stream().<ListEntry>map(OciObjectStorage::summary),
                                prefixes(page).stream().map(CommonPrefix::new))
                        .sorted(StorageStreams.BY_KEY))
                // ponytail: guarda todas as pastas vistas para não repetir uma que cruze a fronteira de página;
                // memória proporcional ao número de pastas do nível.
                .filter(entry -> !(entry instanceof CommonPrefix folder) || seen.add(folder.key()));
    }

    private static List<String> prefixes(ListObjects page) {
        return page.getPrefixes() == null ? List.of() : page.getPrefixes();
    }

    private static ObjectSummary summary(com.oracle.bmc.objectstorage.model.ObjectSummary o) {
        return new ObjectSummary(o.getName(), o.getSize(), o.getEtag(), o.getTimeModified().toInstant());
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .namespaceName(namespace).bucketName(bucket).objectName(key).build());
        } catch (BmcException e) {
            if (e.getStatusCode() != 404 || bucketMissing(e)) {   // objeto inexistente: idempotente
                throw translate(e, "Falha ao apagar " + uri(key));
            }
        }
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        // A cópia só falha dentro da work request; checar antes garante o ObjectNotFoundException do contrato.
        if (head(sourceKey).isEmpty()) {
            throw new ObjectNotFoundException("Origem da cópia não existe: " + uri(sourceKey), null);
        }
        CopyObjectDetails details = CopyObjectDetails.builder()
                .sourceObjectName(sourceKey)
                .destinationRegion(region())
                .destinationNamespace(namespace)
                .destinationBucket(bucket)
                .destinationObjectName(targetKey)
                .build();
        try {
            String workRequestId = client.copyObject(CopyObjectRequest.builder()
                            .namespaceName(namespace)
                            .bucketName(bucket)
                            .copyObjectDetails(details)
                            .build())
                    .getOpcWorkRequestId();
            awaitWorkRequest(workRequestId, "cópia de " + uri(sourceKey) + " para " + uri(targetKey));
        } catch (BmcException e) {
            throw translate(e, "Falha ao copiar " + uri(sourceKey) + " para " + uri(targetKey));
        }
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        return createPar(key, ttl, CreatePreauthenticatedRequestDetails.AccessType.ObjectRead, "download");
    }

    @Override
    public PresignedRequest presignPut(String key, Duration ttl, PutOptions options) {
        ObjectMetadata metadata = options.metadata();
        Map<String, String> headers = new LinkedHashMap<>();
        if (metadata.contentType() != null) {
            headers.put("Content-Type", metadata.contentType());
        }
        if (metadata.contentDisposition() != null) {
            headers.put("Content-Disposition", metadata.contentDisposition());
        }
        metadata.userMetadata().forEach((k, v) -> headers.put(USER_METADATA_PREFIX + k, v));
        switch (options.condition()) {
            case Condition.None none -> { }
            case Condition.IfNotExists ifNotExists -> headers.put("if-none-match", "*");
            case Condition.IfVersionMatches match -> headers.put("if-match", match.version());
        }
        URI url = createPar(key, ttl, CreatePreauthenticatedRequestDetails.AccessType.ObjectWrite, "upload");
        return new PresignedRequest("PUT", url, headers);
    }

    private URI createPar(String key, Duration ttl, CreatePreauthenticatedRequestDetails.AccessType access,
                          String purpose) {
        CreatePreauthenticatedRequestDetails details = CreatePreauthenticatedRequestDetails.builder()
                .name(purpose + " " + key)
                .objectName(key)
                .accessType(access)
                .timeExpires(Date.from(clock.instant().plus(ttl)))
                .build();
        try {
            String accessUri = client.createPreauthenticatedRequest(CreatePreauthenticatedRequestRequest.builder()
                            .namespaceName(namespace)
                            .bucketName(bucket)
                            .createPreauthenticatedRequestDetails(details)
                            .build())
                    .getPreauthenticatedRequest()
                    .getAccessUri();
            // accessUri é o caminho (/p/<token>/n/.../o/...); o host é o endpoint regional do cliente.
            return URI.create(client.getEndpoint() + accessUri);
        } catch (BmcException e) {
            throw translate(e, "Falha ao gerar URL temporária de " + uri(key));
        }
    }

    private void awaitWorkRequest(String workRequestId, String description) {
        Instant deadline = clock.instant().plus(COPY_TIMEOUT);
        Duration interval = firstPollInterval;
        while (true) {
            WorkRequest.Status status = client.getWorkRequest(GetWorkRequestRequest.builder()
                    .workRequestId(workRequestId).build()).getWorkRequest().getStatus();
            switch (status) {
                case Completed -> {
                    return;
                }
                case Failed, Canceled, Canceling -> throw new StorageException(
                        "A " + description + " terminou com status " + status + " (work request " + workRequestId + ")",
                        null);
                default -> { }
            }
            if (clock.instant().isAfter(deadline)) {
                throw new StorageException("Timeout aguardando a " + description + " (work request "
                        + workRequestId + ")", null);
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StorageException("Interrompido aguardando a " + description, e);
            }
            interval = interval.multipliedBy(2).compareTo(MAX_POLL_INTERVAL) > 0 ? MAX_POLL_INTERVAL
                    : interval.multipliedBy(2);
        }
    }

    /** Região do endpoint do cliente: {@code https://[<ns>.]objectstorage.<região>.<domínio>}. */
    private String region() {
        List<String> labels = Arrays.asList(URI.create(client.getEndpoint()).getHost().split("\\."));
        int index = labels.indexOf("objectstorage");
        if (index < 0 || index + 1 >= labels.size()) {
            throw new IllegalStateException("Não foi possível obter a região do endpoint " + client.getEndpoint());
        }
        return labels.get(index + 1);
    }

    private String uri(String key) {
        return "oci://" + bucket + "@" + namespace + "/" + key;
    }

    private static boolean bucketMissing(BmcException e) {
        return "BucketNotFound".equals(e.getServiceCode());
    }

    static StorageException translate(BmcException e, String message) {
        if (bucketMissing(e)) {
            return new StorageException(message + " (bucket não existe)", e);
        }
        return StorageException.fromHttpStatus(e.getStatusCode(), message, e);
    }

    /**
     * O SDK já tira o "opc-meta-" das chaves; sobra outro nos objetos gravados por versões anteriores
     * deste adapter, que prefixavam antes do SDK (a chave guardada ficou "opc-meta-opc-meta-...").
     */
    private static Map<String, String> unprefixed(Map<String, String> opcMeta) {
        Map<String, String> result = new HashMap<>();
        if (opcMeta != null) {
            opcMeta.forEach((k, v) -> result.put(
                    k.startsWith(USER_METADATA_PREFIX) ? k.substring(USER_METADATA_PREFIX.length()) : k, v));
        }
        return result;
    }
}
