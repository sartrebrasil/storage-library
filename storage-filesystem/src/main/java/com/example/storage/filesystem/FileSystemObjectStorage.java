package com.example.storage.filesystem;

import com.example.storage.AccessDeniedException;
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
import com.example.storage.SidecarFiles;
import com.example.storage.StorageException;
import com.example.storage.StorageStreams;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
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
 * <p>Escrita condicional é local ao processo: {@code put}, {@code copy} e o {@code complete} do
 * multipart publicam sob o mesmo lock da instância, então duas instâncias de
 * {@link FileSystemObjectStorage} (ou dois processos) sobre o mesmo diretório não enxergam a escrita
 * uma da outra antes de terminar.</p>
 *
 * <p>Chaves que o filesystem normalizaria para outro arquivo ({@code a/../b}, {@code a//b},
 * {@code a/}, segmento terminado em ponto ou espaço, que o Windows descarta) são rejeitadas. Uma
 * leitura que resolva, por symlink, para fora do root falha com {@link IllegalArgumentException}.
 * Pastas que ficam vazias depois de um {@code delete} são removidas.</p>
 */
public final class FileSystemObjectStorage implements ObjectStorage {

    static final String UPLOADS_DIR = SidecarFiles.UPLOADS_DIR;

    private final Path root;
    // ponytail: lock único por instância (não por chave); escritas em objetos diferentes
    // esperam uma pela outra. Trocar por lock por chave se o throughput de escrita doer.
    private final Object writeLock = new Object();

    public FileSystemObjectStorage(Path root) {
        this.root = Objects.requireNonNull(root, "root").normalize();
    }

    @Override
    public String put(String key, InputStream data, long length, PutOptions options) {
        Path path = resolve(key);
        synchronized (writeLock) {
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
        return new FileSystemMultipartSession(target, uploadDir, key, metadata, writeLock);
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        requireInsideRoot(path, key);
        try {
            return Optional.of(readInfo(key, path));
        } catch (IOException e) {
            throw translate(e, "Falha ao consultar " + key);
        }
    }

    @Override
    public InputStream open(String key, ByteRange range) {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {   // uma pasta também não é objeto
            throw new ObjectNotFoundException("Objeto não encontrado: " + key, null);
        }
        requireInsideRoot(path, key);
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            throw translate(e, "Falha ao abrir " + key);
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
            return StorageStreams.bounded(in, resolved.length());
        } catch (IOException e) {
            throw translate(e, "Falha ao abrir " + key);
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
                    .filter(Objects::nonNull)
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw translate(e, "Falha ao listar " + prefix);
        } catch (UncheckedIOException e) {
            throw translate(e.getCause(), "Falha ao listar " + prefix);
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
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return;   // inexistente, ou uma pasta, que não é objeto: idempotente
        }
        try {
            Files.deleteIfExists(path);
            Files.deleteIfExists(metaPath(path));
            deleteEmptyParents(path.getParent());
        } catch (IOException e) {
            throw translate(e, "Falha ao apagar " + key);
        }
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        Path source = resolve(sourceKey);
        Path target = resolve(targetKey);
        if (!Files.isRegularFile(source)) {
            throw new ObjectNotFoundException("Origem da cópia não existe: " + sourceKey, null);
        }
        requireInsideRoot(source, sourceKey);
        Path temp = null;
        try {
            ObjectMetadata metadata = metadataFrom(sidecarOf(source));
            Files.createDirectories(target.getParent());
            temp = newTempFile(target.getParent());
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            synchronized (writeLock) {
                publish(temp, target, metadata);
            }
        } catch (NoSuchFileException e) {
            deleteQuietly(temp);
            throw new ObjectNotFoundException("Origem da cópia não existe: " + sourceKey, e);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw translate(e, "Falha ao copiar " + sourceKey + " para " + targetKey);
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
        SidecarFiles.requireValidKey(key);
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Chave tenta escapar do root (path traversal): " + key);
        }
        return resolved;
    }

    /** O caminho já existe: resolve symlinks e confere que ele continua dentro do root. */
    private void requireInsideRoot(Path path, String key) {
        try {
            if (!path.toRealPath().startsWith(root.toRealPath())) {
                throw new IllegalArgumentException("Chave aponta para fora do root (symlink): " + key);
            }
        } catch (IOException e) {
            throw translate(e, "Falha ao consultar " + key);
        }
    }

    /** Best-effort: para na primeira pasta que não está vazia (ou que outra escrita acabou de usar). */
    private void deleteEmptyParents(Path dir) {
        for (Path current = dir; current != null && current.startsWith(root) && !current.equals(root);
             current = current.getParent()) {
            try {
                Files.delete(current);
            } catch (IOException notEmptyOrGone) {
                return;
            }
        }
    }

    private static StorageException translate(IOException e, String message) {
        if (e instanceof NoSuchFileException) {
            return new ObjectNotFoundException(message, e);
        }
        if (e instanceof java.nio.file.AccessDeniedException) {
            return new AccessDeniedException(message, e);
        }
        return new StorageException(message, e);
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
            throw translate(e, "Falha ao ler versão atual de " + path);
        }
    }

    private String writeData(Path path, InputStream data, long length, ObjectMetadata metadata) {
        Path parent = path.getParent();
        Path temp;
        try {
            Files.createDirectories(parent);
            temp = newTempFile(parent);
        } catch (IOException e) {
            throw translate(e, "Falha ao preparar escrita de " + path);
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
            throw translate(e, "Falha ao gravar " + path);
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
            Files.write(metaTemp, SidecarFiles.encode(metadata, version, data.size(),
                    data.lastModifiedTime().toMillis()));
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
        return Files.createFile(dir.resolve(SidecarFiles.TEMP_PREFIX + UUID.randomUUID()));
    }

    private boolean isDataFile(Path path) {
        Path rel = root.relativize(path);
        if (rel.getNameCount() > 0 && rel.getName(0).toString().equals(UPLOADS_DIR)) {
            return false;
        }
        return SidecarFiles.isDataFile(path.getFileName().toString());
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

    /** {@code null} se o arquivo foi apagado durante a listagem: ele só não aparece. */
    private ObjectSummary summaryOf(String key, Path dataPath) {
        try {
            BasicFileAttributes data = Files.readAttributes(dataPath, BasicFileAttributes.class);
            Instant lastModified = data.lastModifiedTime().toInstant();
            String version = versionOf(describing(loadMetadata(dataPath), data), lastModified.toEpochMilli());
            return new ObjectSummary(key, data.size(), version, lastModified);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao listar " + key, e);
        }
    }

    /** O sidecar de {@code dataPath}, vazio se ele não existe ou não descreve mais o arquivo. */
    private static Properties sidecarOf(Path dataPath) throws IOException {
        return describing(loadMetadata(dataPath), Files.readAttributes(dataPath, BasicFileAttributes.class));
    }

    /** No filesystem, a data de modificação do sidecar é em milissegundos. */
    private static Properties describing(Properties props, BasicFileAttributes data) {
        return SidecarFiles.describing(props, data.size(), data.lastModifiedTime().toMillis());
    }

    private static String versionOf(Properties sidecar, long lastModifiedMillis) {
        return SidecarFiles.versionOf(sidecar, lastModifiedMillis);
    }

    private static ObjectMetadata metadataFrom(Properties sidecar) {
        return SidecarFiles.metadataFrom(sidecar);
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

    private static Path metaPath(Path dataPath) {
        return dataPath.resolveSibling(dataPath.getFileName().toString() + SidecarFiles.META_SUFFIX);
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
}
