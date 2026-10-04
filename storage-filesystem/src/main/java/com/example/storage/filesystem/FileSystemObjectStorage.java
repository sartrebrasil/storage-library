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
import java.nio.file.attribute.BasicFileAttributes;
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
 * {@code contentType}, {@code contentDisposition}, {@code user.<chave>} e o tamanho e a data de
 * modificação do arquivo que ele descreve. Dados e sidecar são gravados em temporários e
 * renomeados no lugar, então quem lê nunca vê nenhum dos dois pela metade.</p>
 *
 * <p>Um sidecar que não bate com o tamanho e a data do arquivo (o arquivo foi trocado por fora da
 * API, ou o processo caiu entre as duas renomeações) é ignorado, assim como a falta dele:
 * {@link #head} devolve metadata vazia e sintetiza a versão a partir da data de modificação, a
 * mesma que {@link Condition.IfVersionMatches} compara.</p>
 *
 * <p>Nomes terminados em {@code .objmeta} ou começados por {@code .pending-} são reservados e
 * rejeitados como chave.</p>
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
        checkAccess();
        // Começa na pasta mais funda do prefixo ("a/b/c" começa em a/b), em vez de percorrer o root inteiro.
        int slash = prefix.lastIndexOf('/');
        Path start = slash < 0 ? root : resolve(prefix.substring(0, slash));
        if (!Files.isDirectory(start)) {
            return Stream.empty();
        }
        try (Stream<Path> walk = Files.walk(start)) {
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

    /** Confere o root, sem percorrer a árvore como o padrão da interface. */
    @Override
    public void checkAccess() {
        if (!Files.isDirectory(root)) {
            throw new StorageException("Bucket/raiz não existe: " + root, null);
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
        Path temp = null;
        try {
            ObjectMetadata metadata = metadataFrom(sidecarOf(source));
            Files.createDirectories(target.getParent());
            temp = newTempFile(target.getParent());
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            publish(temp, target, metadata);
        } catch (NoSuchFileException e) {
            deleteQuietly(temp);
            throw new ObjectNotFoundException("Origem da cópia não existe: " + sourceKey, e);
        } catch (IOException e) {
            deleteQuietly(temp);
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
        for (String segment : key.split("/")) {
            if (segment.endsWith(META_SUFFIX) || segment.startsWith(TEMP_PREFIX)) {
                throw new IllegalArgumentException("Chave usa um nome reservado (" + META_SUFFIX + " ou "
                        + TEMP_PREFIX + "): " + key);
            }
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
            return versionOf(sidecarOf(path), Files.getLastModifiedTime(path).toMillis());
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new StorageException("Falha ao ler versão atual de " + path, e);
        }
    }

    private String writeData(Path path, InputStream data, long length, ObjectMetadata metadata) {
        Path parent = path.getParent();
        Path temp;
        try {
            Files.createDirectories(parent);
            temp = newTempFile(parent);
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
            return publish(temp, path, metadata);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new StorageException("Falha ao gravar " + path, e);
        } catch (RuntimeException e) {
            deleteQuietly(temp);
            throw e;
        }
    }

    /**
     * Põe {@code temp} no lugar de {@code target} com um sidecar novo, e devolve a versão. O sidecar é
     * gravado antes num temporário e renomeado depois dos dados: uma queda entre as duas renomeações
     * deixa um sidecar que não bate com o arquivo, e que por isso é ignorado. Chamado também por
     * {@link FileSystemMultipartSession#complete}.
     */
    static String publish(Path temp, Path target, ObjectMetadata metadata) throws IOException {
        BasicFileAttributes data = Files.readAttributes(temp, BasicFileAttributes.class);
        String version = UUID.randomUUID().toString();
        Path metaTemp = newTempFile(target.getParent());
        try {
            writeMetadataFile(metaTemp, metadata, version, data.size(), data.lastModifiedTime().toMillis());
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.move(metaTemp, metaPath(target), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(metaTemp);
            throw e;
        }
        return version;
    }

    /** {@code createFile}, não {@code createTempFile}: este cria só para o dono, e o objeto herdaria isso. */
    private static Path newTempFile(Path dir) throws IOException {
        return Files.createFile(dir.resolve(TEMP_PREFIX + UUID.randomUUID()));
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
        BasicFileAttributes data = Files.readAttributes(dataPath, BasicFileAttributes.class);
        Properties props = describing(loadMetadata(dataPath), data);
        Instant lastModified = data.lastModifiedTime().toInstant();
        return new ObjectInfo(key, data.size(), versionOf(props, lastModified.toEpochMilli()), lastModified,
                metadataFrom(props));
    }

    private ObjectSummary summaryOf(String key, Path dataPath) {
        try {
            BasicFileAttributes data = Files.readAttributes(dataPath, BasicFileAttributes.class);
            Instant lastModified = data.lastModifiedTime().toInstant();
            String version = versionOf(describing(loadMetadata(dataPath), data), lastModified.toEpochMilli());
            return new ObjectSummary(key, data.size(), version, lastModified);
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

    /** O sidecar de {@code dataPath}, vazio se ele não existe ou não descreve mais o arquivo. */
    private static Properties sidecarOf(Path dataPath) throws IOException {
        return describing(loadMetadata(dataPath), Files.readAttributes(dataPath, BasicFileAttributes.class));
    }

    /**
     * {@code props} se ele descreve o arquivo (mesmo tamanho e data de modificação), senão vazio. Sidecars
     * gravados antes de guardarem tamanho e data valem como estão.
     */
    private static Properties describing(Properties props, BasicFileAttributes data) {
        String size = props.getProperty("size");
        String mtime = props.getProperty("mtime");
        boolean stale = (size != null && !size.equals(String.valueOf(data.size())))
                || (mtime != null && !mtime.equals(String.valueOf(data.lastModifiedTime().toMillis())));
        return stale ? new Properties() : props;
    }

    /** A versão do sidecar ou, sem ele, uma derivada da data de modificação. */
    private static String versionOf(Properties sidecar, long lastModifiedMillis) {
        return sidecar.getProperty("version", "v" + lastModifiedMillis);
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

    private static void writeMetadataFile(Path file, ObjectMetadata metadata, String version, long size,
                                          long lastModifiedMillis) throws IOException {
        Properties props = new Properties();
        props.setProperty("version", version);
        props.setProperty("size", String.valueOf(size));
        props.setProperty("mtime", String.valueOf(lastModifiedMillis));
        if (metadata.contentType() != null) {
            props.setProperty("contentType", metadata.contentType());
        }
        if (metadata.contentDisposition() != null) {
            props.setProperty("contentDisposition", metadata.contentDisposition());
        }
        metadata.userMetadata().forEach((k, v) -> props.setProperty("user." + k, v));
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, null);
        }
    }

    private static Path metaPath(Path dataPath) {
        return dataPath.resolveSibling(dataPath.getFileName().toString() + META_SUFFIX);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
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
