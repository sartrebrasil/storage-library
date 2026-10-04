package com.example.storage.filesystem;

import com.example.storage.ByteRange;
import com.example.storage.Condition;
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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Implementação sobre o filesystem local ({@code java.nio.file}), sem SDK de nuvem: pastas
 * de verdade, um arquivo por objeto. {@code root} é o "bucket".
 *
 * <p>Como o filesystem não guarda versão nem metadata do objeto, cada arquivo tem um
 * sidecar {@code <arquivo>.objmeta} (formato {@link Properties}) com {@code version},
 * {@code contentType}, {@code contentDisposition} e {@code user.<chave>}. Um arquivo colocado
 * na árvore por fora desta API (sem sidecar) ainda é lido: {@link #head} devolve metadata vazia
 * e sintetiza a versão a partir da data de modificação.</p>
 *
 * <p>Uploads multipart gravam cada parte em {@code .uploads/<uploadId>/} e concatenam os
 * arquivos em {@link MultipartSession#complete}, sem manter nenhuma parte inteira em memória
 * (ver {@link FileSystemMultipartSession}).</p>
 *
 * <p>Escrita condicional é local ao processo: {@code put} sincroniza no próprio storage, então
 * duas instâncias de {@link FileSystemObjectStorage} (ou dois processos) sobre o mesmo diretório
 * não enxergam a escrita uma da outra antes de terminar.</p>
 */
public final class FileSystemObjectStorage implements ObjectStorage {

    private static final String META_SUFFIX = ".objmeta";
    private static final String TEMP_PREFIX = ".pending-";
    static final String UPLOADS_DIR = ".uploads";

    private final Path root;

    public FileSystemObjectStorage(Path root) {
        this.root = Objects.requireNonNull(root, "root").normalize();
    }

    @Override
    public String put(String key, InputStream data, long length, PutOptions options) {
        Path path = resolve(key);
        // ponytail: lock único por instância (não por chave); escritas em objetos diferentes
        // esperam uma pela outra. Trocar por lock por chave se o throughput de put() doer.
        synchronized (this) {
            checkCondition(path, options.condition());
            return writeData(path, data, length, options.metadata());
        }
    }

    @Override
    public MultipartSession initiateMultipart(String key, ObjectMetadata metadata) {
        metadata.requireWritable();
        Path target = resolve(key);
        String uploadId = UUID.randomUUID().toString();
        Path uploadDir = root.resolve(UPLOADS_DIR).resolve(uploadId);
        return new FileSystemMultipartSession(target, uploadDir, key, metadata);
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(readInfo(key, path));
        } catch (IOException e) {
            throw new StorageException("Falha ao consultar " + key, e);
        }
    }

    @Override
    public InputStream open(String key, ByteRange range) {
        Path path = resolve(key);
        long size;
        try {
            size = Files.size(path);
        } catch (NoSuchFileException e) {
            throw new ObjectNotFoundException("Objeto não encontrado: " + key, e);
        } catch (IOException e) {
            throw new StorageException("Falha ao abrir " + key, e);
        }
        ByteRange resolved = range.resolve(size);
        try {
            InputStream in = Files.newInputStream(path);
            if (resolved.isAll()) {
                return in;
            }
            try {
                in.skipNBytes(resolved.offset());
            } catch (IOException e) {
                in.close();
                throw e;
            }
            return new BoundedInputStream(in, resolved.length());
        } catch (IOException e) {
            throw new StorageException("Falha ao abrir " + key, e);
        }
    }

    @Override
    public Stream<ObjectSummary> list(String prefix) {
        if (!Files.isDirectory(root)) {
            throw new StorageException("Bucket/raiz não existe: " + root, null);
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(this::isDataFile)
                    .map(this::toKey)
                    .filter(key -> key.startsWith(prefix))
                    .sorted()
                    .map(key -> summaryOf(key, root.resolve(key)))
                    .toList()
                    .stream();
        } catch (IOException | UncheckedIOException e) {
            throw new StorageException("Falha ao listar " + prefix, e);
        }
    }

    @Override
    public void delete(String key) {
        Path path = resolve(key);
        try {
            Files.deleteIfExists(path);
            Files.deleteIfExists(metaPath(path));
        } catch (IOException e) {
            throw new StorageException("Falha ao apagar " + key, e);
        }
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        Path source = resolve(sourceKey);
        Path target = resolve(targetKey);
        if (!Files.isRegularFile(source)) {
            throw new ObjectNotFoundException("Origem da cópia não existe: " + sourceKey, null);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            ObjectMetadata metadata = metadataFrom(loadMetadata(source));
            writeMetadataFile(target, metadata, UUID.randomUUID().toString());
        } catch (IOException e) {
            throw new StorageException("Falha ao copiar " + sourceKey + " para " + targetKey, e);
        }
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        return resolve(key).toUri();
    }

    @Override
    public PresignedRequest presignPut(String key, Duration ttl, PutOptions options) {
        String contentType = options.metadata().contentType();
        return new PresignedRequest("PUT", resolve(key).toUri(),
                contentType == null ? Map.of() : Map.of("Content-Type", contentType));
    }

    // ------------------------------------------------------------------

    private Path resolve(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isEmpty() || key.startsWith("/") || key.contains("\\")) {
            throw new IllegalArgumentException("Chave inválida: " + key);
        }
        if (key.equals(UPLOADS_DIR) || key.startsWith(UPLOADS_DIR + "/")) {
            throw new IllegalArgumentException("Chave usa o prefixo reservado " + UPLOADS_DIR + ": " + key);
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Chave tenta escapar do root (path traversal): " + key);
        }
        return resolved;
    }

    private void checkCondition(Path path, Condition condition) {
        boolean exists = Files.isRegularFile(path);
        boolean satisfied = switch (condition) {
            case Condition.None none -> true;
            case Condition.IfNotExists ifNotExists -> !exists;
            case Condition.IfVersionMatches match -> exists && match.version().equals(currentVersion(path));
        };
        if (!satisfied) {
            throw new PreconditionFailedException("Pré-condição " + condition + " falhou em " + path, null);
        }
    }

    private String currentVersion(Path path) {
        try {
            return loadMetadata(path).getProperty("version");
        } catch (IOException e) {
            throw new StorageException("Falha ao ler versão atual de " + path, e);
        }
    }

    private String writeData(Path path, InputStream data, long length, ObjectMetadata metadata) {
        Path parent = path.getParent();
        Path temp;
        try {
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, TEMP_PREFIX, ".tmp");
        } catch (IOException e) {
            throw new StorageException("Falha ao preparar escrita de " + path, e);
        }
        try {
            long written;
            try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
                written = data.transferTo(out);
            }
            if (written != length) {
                throw new StorageException("Tamanho informado (" + length + ") difere do conteúdo ("
                        + written + ") em " + path, null);
            }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new StorageException("Falha ao gravar " + path, e);
        } catch (RuntimeException e) {
            deleteQuietly(temp);
            throw e;
        }
        String version = UUID.randomUUID().toString();
        try {
            writeMetadataFile(path, metadata, version);
        } catch (IOException e) {
            throw new StorageException("Falha ao gravar metadata de " + path, e);
        }
        return version;
    }

    private boolean isDataFile(Path path) {
        Path rel = root.relativize(path);
        if (rel.getNameCount() > 0 && rel.getName(0).toString().equals(UPLOADS_DIR)) {
            return false;
        }
        String name = path.getFileName().toString();
        return !name.endsWith(META_SUFFIX) && !name.startsWith(TEMP_PREFIX);
    }

    private String toKey(Path path) {
        return root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
    }

    private ObjectInfo readInfo(String key, Path dataPath) throws IOException {
        long size = Files.size(dataPath);
        Instant lastModified = Files.getLastModifiedTime(dataPath).toInstant();
        Properties props = loadMetadata(dataPath);
        String version = props.getProperty("version", "v" + lastModified.toEpochMilli());
        return new ObjectInfo(key, size, version, lastModified, metadataFrom(props));
    }

    private ObjectSummary summaryOf(String key, Path dataPath) {
        try {
            long size = Files.size(dataPath);
            Instant lastModified = Files.getLastModifiedTime(dataPath).toInstant();
            String version = loadMetadata(dataPath).getProperty("version", "v" + lastModified.toEpochMilli());
            return new ObjectSummary(key, size, version, lastModified);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao listar " + key, e);
        }
    }

    private static ObjectMetadata metadataFrom(Properties props) {
        Map<String, String> userMetadata = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith("user.")) {
                userMetadata.put(name.substring("user.".length()), props.getProperty(name));
            }
        }
        return new ObjectMetadata(props.getProperty("contentType"), props.getProperty("contentDisposition"), userMetadata);
    }

    private static Properties loadMetadata(Path dataPath) throws IOException {
        Properties props = new Properties();
        Path metaPath = metaPath(dataPath);
        if (Files.isRegularFile(metaPath)) {
            try (InputStream in = Files.newInputStream(metaPath)) {
                props.load(in);
            }
        }
        return props;
    }

    /** Chamado também por {@link FileSystemMultipartSession#complete}. */
    static void writeMetadataFile(Path dataPath, ObjectMetadata metadata, String version) throws IOException {
        Properties props = new Properties();
        props.setProperty("version", version);
        if (metadata.contentType() != null) {
            props.setProperty("contentType", metadata.contentType());
        }
        if (metadata.contentDisposition() != null) {
            props.setProperty("contentDisposition", metadata.contentDisposition());
        }
        metadata.userMetadata().forEach((k, v) -> props.setProperty("user." + k, v));
        try (OutputStream out = Files.newOutputStream(metaPath(dataPath))) {
            props.store(out, null);
        }
    }

    private static Path metaPath(Path dataPath) {
        return dataPath.resolveSibling(dataPath.getFileName().toString() + META_SUFFIX);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // a falha que importa é a original da escrita
        }
    }

    /** Limita a leitura a {@code remaining} bytes, sem carregar a faixa em memória. */
    private static final class BoundedInputStream extends FilterInputStream {

        private long remaining;

        BoundedInputStream(InputStream in, long remaining) {
            super(in);
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = super.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = super.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }
    }
}
