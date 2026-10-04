package com.example.storage.sftp;

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
import com.example.storage.StorageException;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.RenameFlags;
import net.schmizz.sshj.sftp.Response.StatusCode;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.sftp.SFTPException;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Implementação sobre SFTP ({@code sshj}), para servidores que não falam nenhum protocolo de
 * object storage — só SSH. {@code root} é um caminho absoluto no servidor (o "bucket").
 *
 * <p>Como o SFTP não guarda versão nem metadata arbitrária do objeto, cada arquivo tem um
 * sidecar {@code <arquivo>.objmeta} (formato {@link Properties}), igual ao adapter de
 * filesystem local. Escrita é sempre: grava num arquivo temporário em
 * {@code .pending-<uuid>} e {@code rename} para o destino — sem {@code OVERWRITE} quando a
 * condição é {@link Condition.IfNotExists}, então o próprio servidor rejeita se o destino já
 * existir (equivalente ao {@code SSH_FX_FILE_ALREADY_EXISTS}); com {@code OVERWRITE}+
 * {@code ATOMIC} nos demais casos, o que exige a extensão {@code posix-rename@openssh.com}
 * (presente no OpenSSH, a grande maioria dos servidores SFTP reais).</p>
 *
 * <p>{@link Condition.IfVersionMatches} não tem equivalente atômico no protocolo: o storage
 * sincroniza no próprio processo (ver {@code put}), então duas instâncias (ou dois processos)
 * apontando pro mesmo {@code root} não se enxergam.</p>
 *
 * <p>Cada operação abre seu próprio canal SFTP ({@link SSHClient#newSFTPClient()}): um
 * {@link SFTPClient} não é seguro para uso concorrente por várias threads (o canal SSH
 * subjacente é, por isso é reaproveitado). {@link #open} mantém o canal aberto até o
 * {@code InputStream} devolvido ser fechado.</p>
 *
 * <p>Uploads multipart gravam cada parte em {@code .uploads/<uploadId>/part-<n>} e concatenam
 * os arquivos em {@link MultipartSession#complete}, lendo e escrevendo em streaming.</p>
 *
 * <p>{@link #copy} não usa nenhuma extensão de cópia no servidor: lê a origem e escreve o
 * destino através do cliente (dois trechos do protocolo por bloco, não uma cópia no lado do
 * servidor). {@link #presignGet}/{@link #presignPut} devolvem um {@code sftp://} informativo
 * (sem credenciais, não é utilizável sozinho): o protocolo não tem conceito de URL temporária.</p>
 */
public final class SftpObjectStorage implements ObjectStorage {

    private static final String META_SUFFIX = ".objmeta";
    private static final String TEMP_PREFIX = ".pending-";
    static final String UPLOADS_DIR = ".uploads";

    private final SSHClient sshClient;
    private final String root;

    public SftpObjectStorage(SSHClient sshClient, String root) {
        this.sshClient = Objects.requireNonNull(sshClient, "sshClient");
        Objects.requireNonNull(root, "root");
        this.root = root.length() > 1 && root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
        if (!this.root.startsWith("/")) {
            throw new IllegalArgumentException("root deve ser um caminho absoluto no servidor: " + root);
        }
    }

    @Override
    public String put(String key, InputStream data, long length, PutOptions options) {
        String path = resolve(key);
        // ponytail: lock único por instância; escritas em objetos diferentes esperam uma pela
        // outra. IfNotExists não precisa dele (o rename sem OVERWRITE já é atômico no servidor);
        // fica só por simplicidade de ter um único caminho de código.
        synchronized (this) {
            if (options.condition() instanceof Condition.IfVersionMatches match
                    && !match.version().equals(currentVersion(path))) {
                throw new PreconditionFailedException("Pré-condição " + options.condition() + " falhou em " + path, null);
            }
            boolean failIfExists = options.condition() instanceof Condition.IfNotExists;
            return writeData(path, data, length, options.metadata(), failIfExists);
        }
    }

    @Override
    public MultipartSession initiateMultipart(String key, ObjectMetadata metadata) {
        String target = resolve(key);
        String uploadId = UUID.randomUUID().toString();
        String uploadDir = root + "/" + UPLOADS_DIR + "/" + uploadId;
        return new SftpMultipartSession(sshClient, target, uploadDir, key, uploadId, metadata);
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        String path = resolve(key);
        return withSftp("Falha ao consultar " + key, sftp -> {
            net.schmizz.sshj.sftp.FileAttributes attrs;
            try {
                attrs = sftp.stat(path);
            } catch (SFTPException e) {
                if (isNotFound(e)) {
                    return Optional.empty();
                }
                throw e;
            }
            if (attrs.getType() != FileMode.Type.REGULAR) {
                return Optional.empty();
            }
            Properties props = loadMetadata(sftp, path);
            Instant lastModified = Instant.ofEpochSecond(attrs.getMtime());
            String version = props.getProperty("version", "v" + lastModified.toEpochMilli());
            return Optional.of(new ObjectInfo(key, attrs.getSize(), version, lastModified, metadataFrom(props)));
        });
    }

    @Override
    public InputStream open(String key, ByteRange range) {
        String path = resolve(key);
        SFTPClient sftp = null;
        RemoteFile file = null;
        try {
            sftp = sshClient.newSFTPClient();
            file = sftp.open(path, Set.of(OpenMode.READ));
            long size = file.length();
            ByteRange resolved = range.resolve(size);
            InputStream body = resolved.isAll() ? file.new RemoteFileInputStream()
                    : new BoundedInputStream(file.new RemoteFileInputStream(resolved.offset()), resolved.length());
            return new ClosingInputStream(body, file, sftp);
        } catch (IOException e) {
            closeQuietly(file);
            closeQuietly(sftp);
            throw translate(e, "Falha ao abrir " + key);
        } catch (RuntimeException e) {
            closeQuietly(file);
            closeQuietly(sftp);
            throw e;
        }
    }

    @Override
    public Stream<ObjectSummary> list(String prefix) {
        List<ObjectSummary> items = withSftp("Falha ao listar " + prefix, sftp -> {
            List<ObjectSummary> collected = new ArrayList<>();
            try {
                walk(sftp, root, collected);
            } catch (SFTPException e) {
                if (isNotFound(e)) {
                    throw new StorageException("Bucket/raiz não existe: " + root, e);
                }
                throw e;
            }
            return collected;
        });
        return items.stream()
                .filter(summary -> summary.key().startsWith(prefix))
                .sorted(Comparator.comparing(ObjectSummary::key))
                .toList()
                .stream();
    }

    @Override
    public void delete(String key) {
        String path = resolve(key);
        withSftp("Falha ao apagar " + key, sftp -> {
            deleteIfExists(sftp, path);
            deleteIfExists(sftp, metaPath(path));
            return null;
        });
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        String source = resolve(sourceKey);
        String target = resolve(targetKey);
        withSftp("Falha ao copiar " + sourceKey + " para " + targetKey, sftp -> {
            try {
                sftp.stat(source);
            } catch (SFTPException e) {
                if (isNotFound(e)) {
                    throw new ObjectNotFoundException("Origem da cópia não existe: " + sourceKey, e);
                }
                throw e;
            }
            mkdirs(sftp, parentOf(target));
            copyRemoteFile(sftp, source, target);
            ObjectMetadata metadata = metadataFrom(loadMetadata(sftp, source));
            writeMetadata(sftp, target, metadata, UUID.randomUUID().toString());
            return null;
        });
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        return sftpUri(resolve(key));
    }

    @Override
    public PresignedRequest presignPut(String key, Duration ttl, PutOptions options) {
        return new PresignedRequest("PUT", sftpUri(resolve(key)), Map.of());
    }

    // ------------------------------------------------------------------

    private String resolve(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isEmpty() || key.startsWith("/") || key.contains("\\")) {
            throw new IllegalArgumentException("Chave inválida: " + key);
        }
        if (key.equals(UPLOADS_DIR) || key.startsWith(UPLOADS_DIR + "/")) {
            throw new IllegalArgumentException("Chave usa o prefixo reservado " + UPLOADS_DIR + ": " + key);
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Chave inválida (path traversal): " + key);
            }
        }
        return root + "/" + key;
    }

    private String currentVersion(String path) {
        return withSftp("Falha ao ler versão atual de " + path, sftp -> loadMetadata(sftp, path).getProperty("version"));
    }

    private String writeData(String path, InputStream data, long length, ObjectMetadata metadata, boolean failIfExists) {
        String parent = parentOf(path);
        String temp = parent + "/" + TEMP_PREFIX + UUID.randomUUID();
        long written = withSftp("Falha ao gravar " + path, sftp -> {
            mkdirs(sftp, parent);
            long count;
            try (RemoteFile file = sftp.open(temp, Set.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))) {
                try (OutputStream out = file.new RemoteFileOutputStream()) {
                    count = data.transferTo(out);
                }
            }
            if (count == length) {
                try {
                    if (failIfExists) {
                        sftp.rename(temp, path);
                    } else {
                        sftp.rename(temp, path, Set.of(RenameFlags.OVERWRITE, RenameFlags.ATOMIC));
                    }
                } catch (IOException e) {
                    rmQuietly(sftp, temp);
                    // rename() sem OVERWRITE só tem um jeito de falhar: destino já existe. O
                    // OpenSSH sftp-server responde isso como SSH_FX_FAILURE genérico em vez de
                    // FILE_ALREADY_EXISTS, então mapeia direto em vez de confiar no status code.
                    if (failIfExists) {
                        throw new PreconditionFailedException("Pré-condição ifNotExists falhou em " + path, e);
                    }
                    throw e;
                }
            }
            return count;
        });
        if (written != length) {
            deleteQuietly(temp);
            throw new StorageException("Tamanho informado (" + length + ") difere do conteúdo ("
                    + written + ") em " + path, null);
        }
        String version = UUID.randomUUID().toString();
        writeMetadata(path, metadata, version);
        return version;
    }

    private void walk(SFTPClient sftp, String dirPath, List<ObjectSummary> sink) throws IOException {
        for (RemoteResourceInfo entry : sftp.ls(dirPath)) {
            String name = entry.getName();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            if (entry.isDirectory()) {
                if (dirPath.equals(root) && name.equals(UPLOADS_DIR)) {
                    continue;   // pasta reservada dos uploads multipart
                }
                walk(sftp, entry.getPath(), sink);
            } else if (entry.isRegularFile() && isDataFile(name)) {
                String key = entry.getPath().substring(root.length() + 1);
                Properties props = loadMetadata(sftp, entry.getPath());
                Instant lastModified = Instant.ofEpochSecond(entry.getAttributes().getMtime());
                String version = props.getProperty("version", "v" + lastModified.toEpochMilli());
                sink.add(new ObjectSummary(key, entry.getAttributes().getSize(), version, lastModified));
            }
        }
    }

    private static boolean isDataFile(String name) {
        return !name.endsWith(META_SUFFIX) && !name.startsWith(TEMP_PREFIX);
    }

    /**
     * Copia para um temporário e renomeia sobre o destino: quem lê nunca vê o destino pela metade,
     * e {@code copy(k, k)} não trunca a origem antes de lê-la.
     */
    private static void copyRemoteFile(SFTPClient sftp, String source, String target) throws IOException {
        String temp = parentOf(target) + "/" + TEMP_PREFIX + UUID.randomUUID();
        try {
            try (RemoteFile in = sftp.open(source, Set.of(OpenMode.READ));
                 RemoteFile out = sftp.open(temp, Set.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))) {
                try (InputStream is = in.new RemoteFileInputStream();
                     OutputStream os = out.new RemoteFileOutputStream()) {
                    is.transferTo(os);
                }
            }
            sftp.rename(temp, target, Set.of(RenameFlags.OVERWRITE, RenameFlags.ATOMIC));
        } catch (IOException e) {
            rmQuietly(sftp, temp);
            throw e;
        }
    }

    private static void deleteIfExists(SFTPClient sftp, String path) throws IOException {
        try {
            sftp.rm(path);
        } catch (SFTPException e) {
            if (!isNotFound(e)) {
                throw e;
            }
        }
    }

    private static void rmQuietly(SFTPClient sftp, String path) {
        try {
            sftp.rm(path);
        } catch (IOException ignored) {
            // a falha que importa é a original da escrita
        }
    }

    private void deleteQuietly(String path) {
        try {
            withSftp("cleanup", sftp -> {
                rmQuietly(sftp, path);
                return null;
            });
        } catch (RuntimeException ignored) {
            // best-effort: uma escrita que já falhou não precisa falhar de novo na limpeza
        }
    }

    private void writeMetadata(String path, ObjectMetadata metadata, String version) {
        withSftp("Falha ao gravar metadata de " + path, sftp -> {
            writeMetadata(sftp, path, metadata, version);
            return null;
        });
    }

    static void writeMetadata(SFTPClient sftp, String path, ObjectMetadata metadata, String version) throws IOException {
        Properties props = new Properties();
        props.setProperty("version", version);
        if (metadata.contentType() != null) {
            props.setProperty("contentType", metadata.contentType());
        }
        if (metadata.contentDisposition() != null) {
            props.setProperty("contentDisposition", metadata.contentDisposition());
        }
        metadata.userMetadata().forEach((k, v) -> props.setProperty("user." + k, v));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        props.store(buffer, null);
        try (RemoteFile file = sftp.open(metaPath(path), Set.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))) {
            try (OutputStream out = file.new RemoteFileOutputStream()) {
                out.write(buffer.toByteArray());
            }
        }
    }

    private static Properties loadMetadata(SFTPClient sftp, String dataPath) throws IOException {
        Properties props = new Properties();
        try (RemoteFile file = sftp.open(metaPath(dataPath), Set.of(OpenMode.READ))) {
            try (InputStream in = file.new RemoteFileInputStream()) {
                props.load(in);
            }
        } catch (SFTPException e) {
            if (!isNotFound(e)) {
                throw e;
            }
        }
        return props;
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

    /**
     * {@code SFTPClient.mkdirs} não é idempotente como {@code Files.createDirectories}: falha
     * se algum segmento do caminho já existir. Chamado também por {@link SftpMultipartSession}.
     */
    static void mkdirs(SFTPClient sftp, String path) throws IOException {
        try {
            sftp.mkdirs(path);
        } catch (SFTPException e) {
            try {
                if (sftp.stat(path).getType() != FileMode.Type.DIRECTORY) {
                    throw e;
                }
            } catch (SFTPException stillMissing) {
                throw e;
            }
        }
    }

    static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "" : path.substring(0, slash);
    }

    static String metaPath(String dataPath) {
        return dataPath + META_SUFFIX;
    }

    private static boolean isNotFound(SFTPException e) {
        return e.getStatusCode() == StatusCode.NO_SUCH_FILE || e.getStatusCode() == StatusCode.NO_SUCH_PATH;
    }

    private URI sftpUri(String path) {
        try {
            return new URI("sftp", null, sshClient.getRemoteHostname(), sshClient.getRemotePort(), path, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T withSftp(String errorMessage, SftpAction<T> action) {
        try (SFTPClient sftp = sshClient.newSFTPClient()) {
            return action.run(sftp);
        } catch (IOException e) {
            throw translate(e, errorMessage);
        }
    }

    private static StorageException translate(IOException e, String message) {
        if (e instanceof SFTPException sftpException) {
            return switch (sftpException.getStatusCode()) {
                case NO_SUCH_FILE, NO_SUCH_PATH -> new ObjectNotFoundException(message, e);
                case PERMISSION_DENIED -> new AccessDeniedException(message, e);
                case FILE_ALREADY_EXISTS -> new PreconditionFailedException(message, e);
                default -> new StorageException(message, e);
            };
        }
        return new StorageException(message, e);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // a falha que importa é a original
        }
    }

    @FunctionalInterface
    private interface SftpAction<T> {
        T run(SFTPClient sftp) throws IOException;
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

    /** Fecha o corpo, depois o {@link RemoteFile} e por fim o canal, nessa ordem. */
    private static final class ClosingInputStream extends FilterInputStream {

        private final RemoteFile file;
        private final SFTPClient sftp;

        ClosingInputStream(InputStream in, RemoteFile file, SFTPClient sftp) {
            super(in);
            this.file = file;
            this.sftp = sftp;
        }

        @Override
        public void close() throws IOException {
            // Ordem importa: try-with-resources fecha na ordem inversa da declaração, e o
            // RemoteFile precisa mandar seu SSH_FXP_CLOSE antes do canal (SFTPClient) morrer.
            try (SFTPClient s = sftp; RemoteFile f = file) {
                super.close();
            }
        }
    }
}
