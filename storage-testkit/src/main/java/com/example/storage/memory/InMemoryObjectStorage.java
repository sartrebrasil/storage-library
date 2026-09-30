package com.example.storage.memory;

import com.example.storage.ByteRange;
import com.example.storage.Condition;
import com.example.storage.MultipartConfig;
import com.example.storage.MultipartSession;
import com.example.storage.ObjectInfo;
import com.example.storage.ObjectMetadata;
import com.example.storage.ObjectNotFoundException;
import com.example.storage.ObjectStorage;
import com.example.storage.ObjectSummary;
import com.example.storage.PreconditionFailedException;
import com.example.storage.PresignedRequest;
import com.example.storage.PutOptions;
import com.example.storage.StorageException;
import com.example.storage.UploadedPart;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Implementação para testes. Segue o contrato de {@link ObjectStorage} e imita as
 * regras do S3 no multipart (parte mínima de 5 MiB). URLs pré-assinadas usam o
 * esquema {@code memory:} e não são acessíveis por HTTP.
 */
public final class InMemoryObjectStorage implements ObjectStorage {

    private record Stored(byte[] data, ObjectMetadata metadata, String version, Instant lastModified) {
    }

    private final ConcurrentSkipListMap<String, Stored> objects = new ConcurrentSkipListMap<>();
    private final Set<String> activeUploads = ConcurrentHashMap.newKeySet();
    private final AtomicLong versions = new AtomicLong();

    @Override
    public String put(String key, InputStream data, long length, PutOptions options) {
        byte[] bytes;
        try {
            bytes = data.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (bytes.length != length) {
            throw new StorageException("Tamanho informado (" + length + ") difere do conteúdo ("
                    + bytes.length + ") em " + key, null);
        }
        return write(key, bytes, options.metadata(), options.condition());
    }

    @Override
    public MultipartSession initiateMultipart(String key, ObjectMetadata metadata) {
        String uploadId = UUID.randomUUID().toString();
        activeUploads.add(uploadId);
        return new Session(key, uploadId, metadata);
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        Stored stored = objects.get(key);
        return stored == null ? Optional.empty()
                : Optional.of(new ObjectInfo(key, stored.data().length, stored.version(),
                        stored.lastModified(), stored.metadata()));
    }

    @Override
    public InputStream open(String key, ByteRange range) {
        byte[] data = require(key).data();
        ByteRange resolved = range.resolve(data.length);
        return resolved.isAll() ? new ByteArrayInputStream(data)
                : new ByteArrayInputStream(data, (int) resolved.offset(), (int) resolved.length());
    }

    @Override
    public Stream<ObjectSummary> list(String prefix) {
        return objects.tailMap(prefix).entrySet().stream()
                .takeWhile(e -> e.getKey().startsWith(prefix))
                .map(e -> new ObjectSummary(e.getKey(), e.getValue().data().length,
                        e.getValue().version(), e.getValue().lastModified()));
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        Stored source = require(sourceKey);
        write(targetKey, source.data(), source.metadata(), Condition.none());
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        return memoryUri(key);
    }

    /** Valida o nome e devolve a mesma URI de {@link #presignGet(String, Duration)}. */
    @Override
    public URI presignGet(String key, Duration ttl, String downloadName) {
        ObjectMetadata.attachmentDisposition(downloadName);
        return memoryUri(key);
    }

    @Override
    public PresignedRequest presignPut(String key, Duration ttl, PutOptions options) {
        String contentType = options.metadata().contentType();
        return new PresignedRequest("PUT", memoryUri(key),
                contentType == null ? Map.of() : Map.of("Content-Type", contentType));
    }

    public Optional<byte[]> get(String key) {
        return Optional.ofNullable(objects.get(key)).map(Stored::data);
    }

    public int activeUploadCount() {
        return activeUploads.size();
    }

    private synchronized String write(String key, byte[] data, ObjectMetadata metadata, Condition condition) {
        Stored current = objects.get(key);
        boolean satisfied = switch (condition) {
            case Condition.None none -> true;
            case Condition.IfNotExists ifNotExists -> current == null;
            case Condition.IfVersionMatches match -> current != null && current.version().equals(match.version());
        };
        if (!satisfied) {
            throw new PreconditionFailedException("Pré-condição " + condition + " falhou em " + key, null);
        }
        String version = "v" + versions.incrementAndGet();
        objects.put(key, new Stored(data, metadata, version, Instant.now()));
        return version;
    }

    private Stored require(String key) {
        Stored stored = objects.get(key);
        if (stored == null) {
            throw new ObjectNotFoundException("Objeto não encontrado: " + key, null);
        }
        return stored;
    }

    private static URI memoryUri(String key) {
        try {
            return new URI("memory", key, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private final class Session implements MultipartSession {
        private final String key;
        private final String uploadId;
        private final ObjectMetadata metadata;
        private final Map<Integer, byte[]> parts = new ConcurrentHashMap<>();

        Session(String key, String uploadId, ObjectMetadata metadata) {
            this.key = key;
            this.uploadId = uploadId;
            this.metadata = metadata;
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
            parts.put(partNumber, Arrays.copyOf(data, length));   // copia: o buffer será reutilizado
            return new UploadedPart(partNumber, "etag-" + partNumber, null);
        }

        @Override
        public void complete(List<UploadedPart> uploaded) {
            List<UploadedPart> sorted = uploaded.stream()
                    .sorted(Comparator.comparingInt(UploadedPart::partNumber)).toList();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < sorted.size(); i++) {
                byte[] part = parts.get(sorted.get(i).partNumber());
                boolean last = i == sorted.size() - 1;
                if (!last && part.length < MultipartConfig.MIN_PART_SIZE) {
                    throw new StorageException("EntityTooSmall: parte " + (i + 1), null);
                }
                out.writeBytes(part);
            }
            write(key, out.toByteArray(), metadata, Condition.none());
            activeUploads.remove(uploadId);
        }

        @Override
        public void abort() {
            parts.clear();
            activeUploads.remove(uploadId);
        }

        @Override
        public List<UploadedPart> listParts() {
            if (!activeUploads.contains(uploadId)) {
                return List.of();   // concluído/abortado
            }
            return parts.keySet().stream().sorted()
                    .map(n -> new UploadedPart(n, "etag-" + n, null))
                    .toList();
        }
    }
}
