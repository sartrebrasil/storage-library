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
import net.schmizz.sshj.sftp.FileAttributes;
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
 * filesystem local, com o tamanho e a data de modificação do arquivo que descreve. O sidecar é
 * gravado num temporário e renomeado depois dos dados; um sidecar que não bate com o arquivo
 * (trocado por fora da API, ou queda entre as duas renomeações) é ignorado, e a versão passa a
 * ser derivada da data de modificação, a mesma que {@link Condition.IfVersionMatches} compara.
 * Nomes terminados em {@code .objmeta} ou começados por {@code .pending-} são reservados.
 * Escrita é sempre: grava num arquivo temporário em
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
 * <p>Cada operação abre seu próprio canal SFTP: um {@link SFTPClient} não é seguro para uso
 * concorrente por várias threads (a conexão SSH subjacente é, por isso é reaproveitada).
 * {@link #open} mantém o canal aberto até o {@code InputStream} devolvido ser fechado. Os canais
 * vêm de uma {@link SftpConnection}, que limita quantos ficam abertos ao mesmo tempo e pode
 * reconectar; vários buckets no mesmo servidor devem compartilhar a mesma.</p>
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

    private final SftpConnection connection;
    private final String root;

    /**
     * Sobre um {@link SSHClient} já conectado, sem reconexão e com o próprio limite de canais. Para vários
     * buckets no mesmo servidor, ou para reconectar, use {@link #SftpObjectStorage(SftpConnection, String)}.
     */
    public SftpObjectStorage(SSHClient sshClient, String root) {
        this(SftpConnection.of(sshClient), root);
    }

    public SftpObjectStorage(SftpConnection connection, String root) {
        this.connection = Objects.requireNonNull(connection, "connection");
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
        metadata.requireWritable();
        String target = resolve(key);
        String uploadId = UUID.randomUUID().toString();
        String uploadDir = root + "/" + UPLOADS_DIR + "/" + uploadId;
        return new SftpMultipartSession(connection, target, uploadDir, key, uploadId, metadata);
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        String path = resolve(key);
        return withSftp("Falha ao consultar " + key, sftp -> {
            FileAttributes attrs;
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
            Properties props = describing(loadMetadata(sftp, path), attrs);
            Instant lastModified = Instant.ofEpochSecond(attrs.getMtime());
            return Optional.of(new ObjectInfo(key, attrs.getSize(), versionOf(props, attrs), lastModified,
                    metadataFrom(props)));
        });
    }

    @Override
    public InputStream open(String key, ByteRange range) {
        String path = resolve(key);
        SftpConnection.Channel channel = null;
        RemoteFile file = null;
        try {
            channel = connection.open();
            file = channel.sftp().open(path, Set.of(OpenMode.READ));
            long size = file.length();
            ByteRange resolved = range.resolve(size);
            InputStream body = resolved.isAll() ? file.new RemoteFileInputStream()
                    : new BoundedInputStream(file.new RemoteFileInputStream(resolved.offset()), resolved.length());
            return new ClosingInputStream(body, file, channel);
        } catch (IOException e) {
            closeQuietly(file);
            closeQuietly(channel);
            throw translate(e, "Falha ao abrir " + key);
        } catch (RuntimeException e) {
            closeQuietly(file);
            closeQuietly(channel);
            throw e;
        }
    }

    @Override
    public Stream<ObjectSummary> list(String prefix) {
        // Começa na pasta mais funda do prefixo ("a/b/c" começa em a/b), em vez de percorrer o root inteiro.
        int slash = prefix.lastIndexOf('/');
        String start = slash < 0 ? root : resolve(prefix.substring(0, slash));
        List<ObjectSummary> items = withSftp("Falha ao listar " + prefix, sftp -> {
            List<ObjectSummary> collected = new ArrayList<>();
            try {
                walk(sftp, start, prefix, collected);
            } catch (SFTPException e) {
                if (!isNotFound(e)) {
                    throw e;
                }
                if (start.equals(root) || !exists(sftp, root)) {
                    throw new StorageException("Bucket/raiz não existe: " + root, e);
                }
            }
            return collected;
        });
        return items.stream()
                .sorted(Comparator.comparing(ObjectSummary::key))
                .toList()
                .stream();
    }

    /** {@code stat} do root, sem percorrer a árvore como o padrão da interface. */
    @Override
    public void checkAccess() {
        withSftp("Falha ao acessar " + root, sftp -> {
            if (!isDirectory(sftp, root)) {
                throw new StorageException("Bucket/raiz não existe: " + root, null);
            }
            return null;
        });
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
            FileAttributes attrs;
            try {
                attrs = sftp.stat(source);
            } catch (SFTPException e) {
                if (isNotFound(e)) {
                    throw new ObjectNotFoundException("Origem da cópia não existe: " + sourceKey, e);
                }
                throw e;
            }
            ObjectMetadata metadata = metadataFrom(describing(loadMetadata(sftp, source), attrs));
            mkdirs(sftp, parentOf(target));
            // Temporário + rename: quem lê nunca vê o destino pela metade, e copy(k, k) não trunca a origem.
            String temp = tempPath(target);
            try {
                copyRemoteFile(sftp, source, temp);
                publish(sftp, temp, target, metadata, false);
            } catch (IOException | RuntimeException e) {
                rmQuietly(sftp, temp);
                throw e;
            }
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
            if (segment.endsWith(META_SUFFIX) || segment.startsWith(TEMP_PREFIX)) {
                throw new IllegalArgumentException("Chave usa um nome reservado (" + META_SUFFIX + " ou "
                        + TEMP_PREFIX + "): " + key);
            }
        }
        return root + "/" + key;
    }

    /** A versão que {@link #head} devolveria, ou {@code null} se o arquivo não existe. */
    private String currentVersion(String path) {
        return withSftp("Falha ao ler versão atual de " + path, sftp -> {
            FileAttributes attrs;
            try {
                attrs = sftp.stat(path);
            } catch (SFTPException e) {
                if (isNotFound(e)) {
                    return null;
                }
                throw e;
            }
            return versionOf(describing(loadMetadata(sftp, path), attrs), attrs);
        });
    }

    private String writeData(String path, InputStream data, long length, ObjectMetadata metadata, boolean failIfExists) {
        String temp = tempPath(path);
        return withSftp("Falha ao gravar " + path, sftp -> {
            mkdirs(sftp, parentOf(path));
            try {
                long count;
                try (RemoteFile file = sftp.open(temp, Set.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))) {
                    try (OutputStream out = file.new RemoteFileOutputStream()) {
                        count = data.transferTo(out);
                    }
                }
                if (count != length) {
                    throw new StorageException("Tamanho informado (" + length + ") difere do conteúdo ("
                            + count + ") em " + path, null);
                }
                return publish(sftp, temp, path, metadata, failIfExists);
            } catch (IOException | RuntimeException e) {
                rmQuietly(sftp, temp);   // inclusive quando o stream de entrada falha no meio
                throw e;
            }
        });
    }

    /**
     * Põe {@code temp} no lugar de {@code target} com um sidecar novo, e devolve a versão. O sidecar vai
     * antes para um temporário e é renomeado depois dos dados: uma queda entre as duas renomeações deixa
     * um sidecar que não bate com o arquivo, e que por isso é ignorado. Chamado também por
     * {@link SftpMultipartSession#complete}.
     */
    static String publish(SFTPClient sftp, String temp, String target, ObjectMetadata metadata, boolean failIfExists)
            throws IOException {
        FileAttributes data = sftp.stat(temp);
        String version = UUID.randomUUID().toString();
        String metaTemp = tempPath(target);
        try {
            writeMetadata(sftp, metaTemp, metadata, version, data);
            if (failIfExists) {
                renameIfAbsent(sftp, temp, target);
            } else {
                sftp.rename(temp, target, Set.of(RenameFlags.OVERWRITE, RenameFlags.ATOMIC));
            }
            sftp.rename(metaTemp, metaPath(target), Set.of(RenameFlags.OVERWRITE, RenameFlags.ATOMIC));
        } catch (IOException | RuntimeException e) {
            rmQuietly(sftp, metaTemp);
            throw e;
        }
        return version;
    }

    /**
     * {@code rename} sem {@code OVERWRITE} falha se o destino existe, mas o OpenSSH responde isso como
     * {@code SSH_FX_FAILURE} genérico, igual a uma queda ou disco cheio: só é precondição se o destino existe.
     */
    private static void renameIfAbsent(SFTPClient sftp, String temp, String target) throws IOException {
        try {
            sftp.rename(temp, target);
        } catch (IOException e) {
            if (exists(sftp, target)) {
                throw new PreconditionFailedException("Pré-condição ifNotExists falhou em " + target, e);
            }
            throw e;
        }
    }

    private static boolean exists(SFTPClient sftp, String path) throws IOException {
        try {
            sftp.stat(path);
            return true;
        } catch (SFTPException e) {
            if (isNotFound(e)) {
                return false;
            }
            throw e;
        }
    }

    static String tempPath(String target) {
        return parentOf(target) + "/" + TEMP_PREFIX + UUID.randomUUID();
    }

    /** Só desce nas pastas e só lê o sidecar dos arquivos que podem estar sob {@code prefix}. */
    private void walk(SFTPClient sftp, String dirPath, String prefix, List<ObjectSummary> sink) throws IOException {
        for (RemoteResourceInfo entry : sftp.ls(dirPath)) {
            String name = entry.getName();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            String key = entry.getPath().substring(root.length() + 1);
            if (entry.isDirectory()) {
                if (dirPath.equals(root) && name.equals(UPLOADS_DIR)) {
                    continue;   // pasta reservada dos uploads multipart
                }
                String folder = key + "/";
                if (folder.startsWith(prefix) || prefix.startsWith(folder)) {
                    walk(sftp, entry.getPath(), prefix, sink);
                }
            } else if (entry.isRegularFile() && isDataFile(name) && key.startsWith(prefix)) {
                FileAttributes attrs = entry.getAttributes();
                String version = versionOf(describing(loadMetadata(sftp, entry.getPath()), attrs), attrs);
                sink.add(new ObjectSummary(key, attrs.getSize(), version, Instant.ofEpochSecond(attrs.getMtime())));
            }
        }
    }

    private static boolean isDataFile(String name) {
        return !name.endsWith(META_SUFFIX) && !name.startsWith(TEMP_PREFIX);
    }

    private static void copyRemoteFile(SFTPClient sftp, String source, String target) throws IOException {
        try (RemoteFile in = sftp.open(source, Set.of(OpenMode.READ));
             RemoteFile out = sftp.open(target, Set.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))) {
            try (InputStream is = in.new RemoteFileInputStream();
                 OutputStream os = out.new RemoteFileOutputStream()) {
                is.transferTo(os);
            }
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

    private static void writeMetadata(SFTPClient sftp, String file, ObjectMetadata metadata, String version,
                                      FileAttributes data) throws IOException {
        Properties props = new Properties();
        props.setProperty("version", version);
        props.setProperty("size", String.valueOf(data.getSize()));
        props.setProperty("mtime", String.valueOf(data.getMtime()));
        if (metadata.contentType() != null) {
            props.setProperty("contentType", metadata.contentType());
        }
        if (metadata.contentDisposition() != null) {
            props.setProperty("contentDisposition", metadata.contentDisposition());
        }
        metadata.userMetadata().forEach((k, v) -> props.setProperty("user." + k, v));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        props.store(buffer, null);
        try (RemoteFile remote = sftp.open(file, Set.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))) {
            try (OutputStream out = remote.new RemoteFileOutputStream()) {
                out.write(buffer.toByteArray());
            }
        }
    }

    /**
     * {@code props} se ele descreve o arquivo (mesmo tamanho e data de modificação, em segundos), senão
     * vazio. Sidecars gravados antes de guardarem tamanho e data valem como estão.
     */
    private static Properties describing(Properties props, FileAttributes data) {
        String size = props.getProperty("size");
        String mtime = props.getProperty("mtime");
        boolean stale = (size != null && !size.equals(String.valueOf(data.getSize())))
                || (mtime != null && !mtime.equals(String.valueOf(data.getMtime())));
        return stale ? new Properties() : props;
    }

    /** A versão do sidecar ou, sem ele, uma derivada da data de modificação. */
    private static String versionOf(Properties sidecar, FileAttributes data) {
        return sidecar.getProperty("version", "v" + data.getMtime() * 1000);
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
     * Cria {@code path} e os pais que faltarem, aceitando os que já existem. Componente a componente, e não
     * {@code SFTPClient.mkdirs}: este checa e cria cada nível em passos separados, então duas partes de
     * multipart enviadas em paralelo criando o mesmo {@code .uploads/<id>} fazem uma delas falhar.
     * Chamado também por {@link SftpMultipartSession}.
     */
    static void mkdirs(SFTPClient sftp, String path) throws IOException {
        StringBuilder current = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            String dir = current.append('/').append(segment).toString();
            try {
                sftp.mkdir(dir);
            } catch (SFTPException e) {
                if (!isDirectory(sftp, dir)) {
                    throw e;
                }
            }
        }
    }

    private static boolean isDirectory(SFTPClient sftp, String path) throws IOException {
        try {
            return sftp.stat(path).getType() == FileMode.Type.DIRECTORY;
        } catch (SFTPException e) {
            if (isNotFound(e)) {
                return false;
            }
            throw e;
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
            SSHClient client = connection.current();
            return new URI("sftp", null, client.getRemoteHostname(), client.getRemotePort(), path, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T withSftp(String errorMessage, SftpAction<T> action) {
        try (SftpConnection.Channel channel = connection.open()) {
            return action.run(channel.sftp());
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
        private final SftpConnection.Channel channel;

        ClosingInputStream(InputStream in, RemoteFile file, SftpConnection.Channel channel) {
            super(in);
            this.file = file;
            this.channel = channel;
        }

        @Override
        public void close() throws IOException {
            // Ordem importa: try-with-resources fecha na ordem inversa da declaração, e o
            // RemoteFile precisa mandar seu SSH_FXP_CLOSE antes do canal morrer.
            try (SftpConnection.Channel c = channel; RemoteFile f = file) {
                super.close();
            }
        }
    }
}
