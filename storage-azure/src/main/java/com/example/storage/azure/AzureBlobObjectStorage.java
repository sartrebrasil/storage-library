package com.example.storage.azure;

import com.azure.core.exception.AzureException;
import com.azure.core.util.Context;
import com.azure.core.util.polling.LongRunningOperationStatus;
import com.azure.core.util.polling.PollResponse;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobCopyInfo;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobRange;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.options.BlobInputStreamOptions;
import com.azure.storage.blob.options.BlockBlobSimpleUploadOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.example.storage.ByteRange;
import com.example.storage.CommonPrefix;
import com.example.storage.ListEntry;
import com.example.storage.StorageStreams;
import com.example.storage.Condition;
import com.example.storage.MultipartSession;
import com.example.storage.ObjectInfo;
import com.example.storage.ObjectMetadata;
import com.example.storage.ObjectStorage;
import com.example.storage.ObjectSummary;
import com.example.storage.PreconditionFailedException;
import com.example.storage.PresignedRequest;
import com.example.storage.PutOptions;
import com.example.storage.StorageException;

import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Implementação sobre Azure Blob Storage com block blobs.
 *
 * <p>O tipo de SAS das URLs temporárias depende de como o cliente foi autenticado:</p>
 * <ul>
 *   <li>{@link #withSharedKey}: connection string / chave da conta (inclui Azurite).
 *       Assinatura local.</li>
 *   <li>{@link #withUserDelegation}: Entra ID (DefaultAzureCredential). Busca uma
 *       user delegation key a cada URL; validade máxima de 7 dias.</li>
 * </ul>
 *
 * <p>Em {@link #presignPut}, o SAS só controla a permissão: com {@code ifNotExists} ele
 * recebe apenas "create", que o Azure não deixa sobrescrever um blob existente. Content-type,
 * metadata e {@code If-Match} são cabeçalhos que o cliente envia, sem imposição pela assinatura.</p>
 *
 * <p>{@link #deleteAll} usa o padrão da interface (uma chamada por blob). {@link #put} usa
 * Put Blob numa requisição; para objetos grandes, use o multipart.</p>
 */
public final class AzureBlobObjectStorage implements ObjectStorage {

    // Tolerância para relógio adiantado no servidor: a chave precisa já estar válida.
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    // ponytail: timeout fixo da cópia assíncrona; expor na configuração se houver cópias muito grandes.
    private static final Duration COPY_TIMEOUT = Duration.ofMinutes(30);

    private final BlobContainerClient container;
    private final boolean userDelegation;
    private final Clock clock;

    AzureBlobObjectStorage(BlobContainerClient container, boolean userDelegation, Clock clock) {
        this.container = Objects.requireNonNull(container, "container");
        this.userDelegation = userDelegation;
        this.clock = clock;
    }

    /** Cliente autenticado por connection string ou chave da conta. */
    public static AzureBlobObjectStorage withSharedKey(BlobContainerClient container) {
        return new AzureBlobObjectStorage(container, false, Clock.systemUTC());
    }

    /** Cliente autenticado por Entra ID (managed identity, az login, service principal). */
    public static AzureBlobObjectStorage withUserDelegation(BlobContainerClient container) {
        return new AzureBlobObjectStorage(container, true, Clock.systemUTC());
    }

    @Override
    public String put(String key, InputStream data, long length, PutOptions options) {
        ObjectMetadata metadata = options.metadata();
        BlockBlobSimpleUploadOptions upload = new BlockBlobSimpleUploadOptions(data, length)
                .setHeaders(headers(metadata))
                .setMetadata(metadata.userMetadata())
                .setRequestConditions(conditions(options.condition()));
        try {
            return blob(key).getBlockBlobClient().uploadWithResponse(upload, null, Context.NONE).getValue().getETag();
        } catch (AzureException e) {
            if (options.condition() instanceof Condition.IfVersionMatches && status(e) == 404) {
                throw new PreconditionFailedException("Pré-condição falhou: " + key + " não existe", e);
            }
            throw translate(e, "Falha ao gravar " + key);
        }
    }

    @Override
    public MultipartSession initiateMultipart(String key, ObjectMetadata metadata) {
        return new AzureBlobMultipartSession(blob(key).getBlockBlobClient(),
                key, UUID.randomUUID().toString(), metadata);
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        try {
            BlobProperties p = blob(key).getProperties();
            return Optional.of(new ObjectInfo(key, p.getBlobSize(), p.getETag(), p.getLastModified().toInstant(),
                    new ObjectMetadata(p.getContentType(), p.getContentDisposition(), p.getMetadata())));
        } catch (AzureException e) {
            if (status(e) == 404 && !containerMissing(e)) {
                return Optional.empty();
            }
            throw translate(e, "Falha ao consultar " + key);
        }
    }

    @Override
    public InputStream open(String key, ByteRange range) {
        BlobInputStreamOptions options = new BlobInputStreamOptions();
        if (!range.isAll()) {
            options.setRange(new BlobRange(range.offset(), range.toEnd() ? null : range.length()));
        }
        try {
            // Lê em blocos sob demanda e fixa o ETag: mudanças no blob durante a leitura geram erro.
            return blob(key).openInputStream(options);
        } catch (AzureException e) {
            throw translate(e, "Falha ao ler " + key);
        }
    }

    @Override
    public Stream<ObjectSummary> list(String prefix) {
        return listing(prefix, container.listBlobs(new ListBlobsOptions().setPrefix(prefix), null).stream()
                .map(AzureBlobObjectStorage::summary));
    }

    @Override
    public Stream<ListEntry> listDirectory(String prefix) {
        return listing(prefix, container.listBlobsByHierarchy("/", new ListBlobsOptions().setPrefix(prefix), null)
                .streamByPage()
                .flatMap(page -> page.getValue().stream()
                        .<ListEntry>map(item -> Boolean.TRUE.equals(item.isPrefix())
                                ? new CommonPrefix(item.getName()) : summary(item))
                        .sorted(StorageStreams.BY_KEY)));
    }

    private <T> Stream<T> listing(String prefix, Stream<T> pages) {
        return StorageStreams.translating(pages, AzureException.class, e -> translate(e, "Falha ao listar " + prefix));
    }

    private static ObjectSummary summary(BlobItem item) {
        return new ObjectSummary(item.getName(), item.getProperties().getContentLength(),
                item.getProperties().getETag(), item.getProperties().getLastModified().toInstant());
    }

    @Override
    public void delete(String key) {
        try {
            blob(key).deleteIfExists();
        } catch (AzureException e) {
            throw translate(e, "Falha ao apagar " + key);
        }
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        try {
            // Mesma conta: o Azure autoriza a origem com a credencial do próprio cliente.
            PollResponse<BlobCopyInfo> result = blob(targetKey)
                    .beginCopy(blob(sourceKey).getBlobUrl(), Duration.ofSeconds(1))
                    .waitForCompletion(COPY_TIMEOUT);
            if (result.getStatus() != LongRunningOperationStatus.SUCCESSFULLY_COMPLETED) {
                throw new StorageException("Cópia de " + sourceKey + " para " + targetKey
                        + " terminou com status " + result.getStatus(), null);
            }
        } catch (AzureException e) {
            throw translate(e, "Falha ao copiar " + sourceKey + " para " + targetKey);
        }
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        return URI.create(sasUrl(key, ttl, new BlobSasPermission().setReadPermission(true)));
    }

    @Override
    public PresignedRequest presignPut(String key, Duration ttl, PutOptions options) {
        boolean createOnly = options.condition() instanceof Condition.IfNotExists;
        BlobSasPermission permission = new BlobSasPermission().setCreatePermission(true).setWritePermission(!createOnly);

        ObjectMetadata metadata = options.metadata();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-ms-blob-type", "BlockBlob");
        if (metadata.contentType() != null) {
            headers.put("x-ms-blob-content-type", metadata.contentType());
        }
        if (metadata.contentDisposition() != null) {
            headers.put("x-ms-blob-content-disposition", metadata.contentDisposition());
        }
        metadata.userMetadata().forEach((k, v) -> headers.put("x-ms-meta-" + k, v));
        switch (options.condition()) {
            case Condition.None none -> { }
            case Condition.IfNotExists ifNotExists -> headers.put("If-None-Match", "*");
            case Condition.IfVersionMatches match -> headers.put("If-Match", match.version());
        }
        return new PresignedRequest("PUT", URI.create(sasUrl(key, ttl, permission)), headers);
    }

    private String sasUrl(String key, Duration ttl, BlobSasPermission permission) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime expiry = now.plus(ttl);
        BlobServiceSasSignatureValues values = new BlobServiceSasSignatureValues(expiry, permission);
        BlobClient blob = blob(key);
        try {
            String sas;
            if (userDelegation) {
                // ponytail: uma user delegation key por URL; cache a chave se gerar URLs em volume.
                UserDelegationKey delegationKey = container.getServiceClient()
                        .getUserDelegationKey(now.minus(CLOCK_SKEW), expiry);
                sas = blob.generateUserDelegationSas(values, delegationKey);
            } else {
                sas = blob.generateSas(values);
            }
            return blob.getBlobUrl() + "?" + sas;
        } catch (AzureException e) {
            throw translate(e, "Falha ao gerar URL temporária de " + key);
        }
    }

    private BlobClient blob(String key) {
        return container.getBlobClient(key);
    }

    private static BlobHttpHeaders headers(ObjectMetadata metadata) {
        return new BlobHttpHeaders()
                .setContentType(metadata.contentType())
                .setContentDisposition(metadata.contentDisposition());
    }

    private static BlobRequestConditions conditions(Condition condition) {
        return switch (condition) {
            case Condition.None none -> null;
            case Condition.IfNotExists ifNotExists -> new BlobRequestConditions().setIfNoneMatch("*");
            case Condition.IfVersionMatches match -> new BlobRequestConditions().setIfMatch(match.version());
        };
    }

    private static int status(AzureException e) {
        return e instanceof BlobStorageException b ? b.getStatusCode() : -1;
    }

    private static boolean containerMissing(AzureException e) {
        return e instanceof BlobStorageException b && BlobErrorCode.CONTAINER_NOT_FOUND.equals(b.getErrorCode());
    }

    static StorageException translate(AzureException e, String message) {
        if (e instanceof BlobStorageException b) {
            if (containerMissing(e)) {
                return new StorageException(message + " (container não existe)", e);
            }
            // If-None-Match: * num blob existente responde 409, não 412
            if (BlobErrorCode.BLOB_ALREADY_EXISTS.equals(b.getErrorCode())) {
                return new PreconditionFailedException(message, e);
            }
            return StorageException.fromHttpStatus(b.getStatusCode(), message, e);
        }
        return new StorageException(message, e);
    }
}
