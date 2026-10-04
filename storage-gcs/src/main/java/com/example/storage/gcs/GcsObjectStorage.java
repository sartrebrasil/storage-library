package com.example.storage.gcs;

import com.example.storage.ByteRange;
import com.example.storage.CommonPrefix;
import com.example.storage.ListEntry;
import com.example.storage.StorageStreams;
import com.example.storage.Condition;
import com.example.storage.DeleteResult;
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
import com.google.auth.ServiceAccountSigner;
import com.google.cloud.BaseServiceException;
import com.google.cloud.BatchResult;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.HttpStorageOptions;
import com.google.cloud.storage.MultipartUploadClient;
import com.google.cloud.storage.MultipartUploadSettings;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageBatch;
import com.google.cloud.storage.multipartupload.model.CreateMultipartUploadRequest;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.Channels;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Implementação sobre Google Cloud Storage.
 *
 * <p>O upload em partes usa o multipart da XML API ({@link MultipartUploadClient}, mesmas
 * regras do S3); as demais operações usam o cliente {@link Storage}. A versão exposta é a
 * <em>generation</em> do objeto.</p>
 *
 * <p>URLs pré-assinadas são Signed URLs V4 (máx. 7 dias). Com chave de service account a
 * assinatura é local; sem ela (VM, GKE workload identity) o SDK assina via IAM e a conta
 * precisa de {@code roles/iam.serviceAccountTokenCreator} sobre si mesma. Em
 * {@link #presignPut}, content-type, metadata e pré-condição são assinados.</p>
 */
public final class GcsObjectStorage implements ObjectStorage {

    private static final int DELETE_BATCH = 100;
    private static final int MAX_PUT_BUFFER = 15 * 1024 * 1024;

    private final Storage storage;
    private final MultipartUploadClient multipart;
    private final String bucket;

    public GcsObjectStorage(Storage storage, MultipartUploadClient multipart, String bucket) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.multipart = Objects.requireNonNull(multipart, "multipart");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
    }

    /** Cria os dois clientes a partir das mesmas opções (ex.: {@code HttpStorageOptions.getDefaultInstance()}). */
    public static GcsObjectStorage create(HttpStorageOptions options, String bucket) {
        return new GcsObjectStorage(options.getService(),
                MultipartUploadClient.create(MultipartUploadSettings.of(options)), bucket);
    }

    @Override
    public String put(String key, InputStream data, long length, PutOptions options) {
        ObjectMetadata metadata = options.metadata();
        BlobInfo info = BlobInfo.newBuilder(bucket, key)
                .setContentType(metadata.contentType())
                .setContentDisposition(metadata.contentDisposition())
                .setMetadata(metadata.userMetadata().isEmpty() ? null : metadata.userMetadata())
                .build();
        Storage.BlobWriteOption[] conditions = switch (options.condition()) {
            case Condition.None none -> new Storage.BlobWriteOption[0];
            case Condition.IfNotExists ifNotExists -> new Storage.BlobWriteOption[] {Storage.BlobWriteOption.doesNotExist()};
            case Condition.IfVersionMatches match ->
                    new Storage.BlobWriteOption[] {Storage.BlobWriteOption.generationMatch(generation(match.version()))};
        };
        try {
            // O SDK lê até o fim do stream e, sem bufferSize, aloca 15 MiB por chamada qualquer que seja o tamanho.
            int bufferSize = (int) Math.min(length, MAX_PUT_BUFFER);
            return String.valueOf(storage.createFrom(info, new ExactLengthInputStream(data, length), bufferSize,
                    conditions).getGeneration());
        } catch (BaseServiceException e) {
            if (options.condition() instanceof Condition.IfVersionMatches && e.getCode() == 404) {
                throw new PreconditionFailedException("Pré-condição falhou: " + uri(key) + " não existe", e);
            }
            throw translate(e, "Falha ao gravar " + uri(key));
        } catch (IOException e) {
            throw new StorageException("Falha ao gravar " + uri(key), e);
        }
    }

    @Override
    public MultipartSession initiateMultipart(String key, ObjectMetadata metadata) {
        metadata.requireWritable();
        CreateMultipartUploadRequest request = CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(metadata.contentType())
                .contentDisposition(metadata.contentDisposition())
                .metadata(metadata.userMetadata())
                .build();
        try {
            String uploadId = multipart.createMultipartUpload(request).uploadId();
            return new GcsMultipartSession(multipart, bucket, key, uploadId);
        } catch (BaseServiceException e) {
            throw translate(e, "Falha ao iniciar upload multipart de " + uri(key));
        }
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        try {
            return Optional.ofNullable(storage.get(BlobId.of(bucket, key))).map(GcsObjectStorage::info);
        } catch (BaseServiceException e) {
            throw translate(e, "Falha ao consultar " + uri(key));
        }
    }

    @Override
    public InputStream open(String key, ByteRange range) {
        return read(key, range).stream();
    }

    /**
     * O canal do GCS lê sob demanda: a faixa é resolvida contra o tamanho do {@code Blob},
     * já buscado para fixar a generation, e uma faixa fora do objeto falha aqui, não no consumo.
     */
    @Override
    public ObjectContent read(String key, ByteRange range) {
        try {
            Blob blob = storage.get(BlobId.of(bucket, key));
            if (blob == null) {
                throw new ObjectNotFoundException("Objeto não encontrado: " + uri(key), null);
            }
            long size = blob.getSize();
            ByteRange resolved = range.resolve(size);
            // Fixa a generation lida acima: uma sobrescrita durante a leitura gera erro, não mistura versões.
            ReadChannel reader = storage.reader(BlobId.of(bucket, key, blob.getGeneration()));
            if (!resolved.isAll()) {
                reader.seek(resolved.offset());
                reader = reader.limit(resolved.offset() + resolved.length());
            }
            return new ObjectContent(Channels.newInputStream(reader), resolved, size);
        } catch (BaseServiceException e) {
            throw translate(e, "Falha ao ler " + uri(key));
        } catch (IOException e) {
            throw new StorageException("Falha ao ler " + uri(key), e);
        }
    }

    /** Uma página de um objeto: exige só {@code storage.objects.list}, não permissão sobre o bucket. */
    @Override
    public void checkAccess() {
        try {
            storage.list(bucket, Storage.BlobListOption.pageSize(1));
        } catch (BaseServiceException e) {
            throw e.getCode() == 404 ? new StorageException("Bucket não existe: " + uri(""), e)
                    : translate(e, "Falha ao acessar " + uri(""));
        }
    }

    @Override
    public Stream<ObjectSummary> list(String prefix) {
        return listing(prefix, () -> StreamSupport.stream(
                        storage.list(bucket, Storage.BlobListOption.prefix(prefix)).iterateAll().spliterator(), false)
                .map(GcsObjectStorage::summary));
    }

    @Override
    public Stream<ListEntry> listDirectory(String prefix) {
        // currentDirectory() usa "/" como delimiter; pastas vêm como Blob com isDirectory().
        return listing(prefix, () -> Stream.iterate(
                        storage.list(bucket, Storage.BlobListOption.prefix(prefix), Storage.BlobListOption.currentDirectory()),
                        Objects::nonNull, page -> page.hasNextPage() ? page.getNextPage() : null)
                .flatMap(page -> StreamSupport.stream(page.getValues().spliterator(), false)
                        .<ListEntry>map(b -> b.isDirectory() ? new CommonPrefix(b.getName()) : summary(b))
                        .sorted(StorageStreams.BY_KEY)));
    }

    /** A primeira página é buscada na chamada; as demais durante o consumo. As duas traduzem o erro. */
    private <T> Stream<T> listing(String prefix, Supplier<Stream<T>> pages) {
        try {
            return StorageStreams.translating(pages.get(), BaseServiceException.class,
                    e -> translate(e, "Falha ao listar " + uri(prefix)));
        } catch (BaseServiceException e) {
            throw translate(e, "Falha ao listar " + uri(prefix));
        }
    }

    private static ObjectSummary summary(Blob b) {
        return new ObjectSummary(b.getName(), b.getSize(), String.valueOf(b.getGeneration()),
                b.getUpdateTimeOffsetDateTime().toInstant());
    }

    @Override
    public void delete(String key) {
        try {
            storage.delete(BlobId.of(bucket, key));   // false = não existia
        } catch (BaseServiceException e) {
            throw translate(e, "Falha ao apagar " + uri(key));
        }
    }

    @Override
    public DeleteResult deleteAll(Collection<String> keys) {
        Map<String, StorageException> failures = new LinkedHashMap<>();
        List<String> all = List.copyOf(keys);
        for (int start = 0; start < all.size(); start += DELETE_BATCH) {
            List<String> batch = all.subList(start, Math.min(all.size(), start + DELETE_BATCH));
            // Lote próprio em vez de storage.delete(List): aquele devolve false tanto para chave
            // inexistente quanto para erro do item (403, 429...), e o erro se perderia.
            StorageBatch requests = storage.batch();
            for (String key : batch) {
                requests.delete(BlobId.of(bucket, key)).notify(new BatchResult.Callback<>() {
                    @Override
                    public void success(Boolean deleted) {
                        // false = não existia, que conta como sucesso
                    }

                    @Override
                    public void error(com.google.cloud.storage.StorageException e) {
                        failures.put(key, translate(e, "Falha ao apagar " + uri(key)));
                    }
                });
            }
            try {
                requests.submit();
            } catch (BaseServiceException e) {
                StorageException failure = translate(e, "Falha ao apagar lote em gs://" + bucket);
                batch.forEach(k -> failures.put(k, failure));
            }
        }
        return new DeleteResult(failures);
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        try {
            // getResult() repete o rewrite até concluir (objetos grandes levam várias chamadas).
            storage.copy(Storage.CopyRequest.of(BlobId.of(bucket, sourceKey), BlobId.of(bucket, targetKey)))
                    .getResult();
        } catch (BaseServiceException e) {
            throw translate(e, "Falha ao copiar " + uri(sourceKey) + " para " + uri(targetKey));
        }
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        return sign(key, ttl, Storage.SignUrlOption.withV4Signature());
    }

    /** {@code response-content-disposition} entra na assinatura V4 como query param. */
    @Override
    public URI presignGet(String key, Duration ttl, String downloadName) {
        String disposition = ObjectMetadata.attachmentDisposition(downloadName);
        return sign(key, ttl, Storage.SignUrlOption.withV4Signature(),
                Storage.SignUrlOption.withQueryParams(Map.of("response-content-disposition", disposition)));
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
        metadata.userMetadata().forEach((k, v) -> headers.put("x-goog-meta-" + k, v));
        switch (options.condition()) {
            case Condition.None none -> { }
            case Condition.IfNotExists ifNotExists -> headers.put("x-goog-if-generation-match", "0");
            case Condition.IfVersionMatches match ->
                    headers.put("x-goog-if-generation-match", String.valueOf(generation(match.version())));
        }
        URI url = sign(key, ttl, Storage.SignUrlOption.withV4Signature(),
                Storage.SignUrlOption.httpMethod(HttpMethod.PUT), Storage.SignUrlOption.withExtHeaders(headers));
        return new PresignedRequest("PUT", url, headers);
    }

    private URI sign(String key, Duration ttl, Storage.SignUrlOption... options) {
        try {
            return storage.signUrl(BlobInfo.newBuilder(bucket, key).build(), ttl.toSeconds(), TimeUnit.SECONDS, options)
                    .toURI();
        } catch (ServiceAccountSigner.SigningException | URISyntaxException e) {
            throw new StorageException("Falha ao gerar URL temporária de " + uri(key), e);
        } catch (IllegalStateException e) {
            // Credencial que não assina (ADC de usuário, via gcloud auth application-default login)
            throw new StorageException("Falha ao gerar URL temporária de " + uri(key) + ": a credencial não assina; "
                    + "use uma service account (chave ou workload identity com iam.serviceAccountTokenCreator)", e);
        }
    }

    private static ObjectInfo info(Blob b) {
        return new ObjectInfo(b.getName(), b.getSize(), String.valueOf(b.getGeneration()),
                b.getUpdateTimeOffsetDateTime().toInstant(),
                new ObjectMetadata(b.getContentType(), b.getContentDisposition(), b.getMetadata()));
    }

    private static long generation(String version) {
        try {
            return Long.parseLong(version);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Versão inválida para GCS (esperada generation numérica): " + version, e);
        }
    }

    private String uri(String key) {
        return "gs://" + bucket + "/" + key;
    }

    static StorageException translate(BaseServiceException e, String message) {
        return StorageException.fromHttpStatus(e.getCode(), message, e);
    }

    /**
     * Entrega exatamente {@code length} bytes e falha se {@code data} tiver menos ou mais. O upload só é
     * concluído no fim do stream, então a falha impede que o objeto seja gravado truncado ou com sobra.
     */
    private static final class ExactLengthInputStream extends FilterInputStream {

        private long remaining;

        ExactLengthInputStream(InputStream data, long length) {
            super(data);
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining == 0) {
                if (in.read() >= 0) {
                    throw new IOException("O stream tem mais bytes que o tamanho informado");
                }
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n < 0) {
                throw new IOException("O stream terminou " + remaining + " bytes antes do tamanho informado");
            }
            remaining -= n;
            return n;
        }
    }
}
