package com.example.storage.sftp;

import com.example.storage.MultipartSession;
import com.example.storage.ObjectMetadata;
import com.example.storage.StorageException;
import com.example.storage.UploadedPart;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.sftp.SFTPException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Cada parte vira um arquivo em {@code .uploads/<uploadId>/part-<n>}; {@link #complete}
 * concatena os arquivos direto no destino, em streaming. Cada chamada abre seu próprio canal
 * SFTP da {@link SftpConnection}, então partes em voo em paralelo (via
 * {@code MultipartConfig.maxInFlight}) não competem pelo mesmo canal, e esperam uma vaga quando o
 * limite de canais da conexão foi atingido.
 */
final class SftpMultipartSession implements MultipartSession {

    private final SftpConnection connection;
    private final String targetPath;
    private final String uploadDir;
    private final String key;
    private final String uploadId;
    private final ObjectMetadata metadata;

    SftpMultipartSession(SftpConnection connection, String targetPath, String uploadDir, String key,
                         String uploadId, ObjectMetadata metadata) {
        this.connection = connection;
        this.targetPath = targetPath;
        this.uploadDir = uploadDir;
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
        try (SftpConnection.Channel channel = connection.open()) {
            SFTPClient sftp = channel.sftp();
            SftpObjectStorage.mkdirs(sftp, uploadDir);
            try (RemoteFile file = sftp.open(partPath(partNumber), Set.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))) {
                try (OutputStream out = file.new RemoteFileOutputStream()) {
                    // write(data, 0, length) inteiro de uma vez trava esperando a janela SSH
                    // expandir; transferTo escreve em blocos de 8 KiB e dá tempo do ACK voltar.
                    new ByteArrayInputStream(data, 0, length).transferTo(out);
                }
            }
            return new UploadedPart(partNumber, "part-" + partNumber, null);
        } catch (IOException e) {
            throw new StorageException("Falha ao gravar parte " + partNumber + " de " + key, e);
        }
    }

    @Override
    public void complete(List<UploadedPart> parts) {
        List<UploadedPart> sorted = parts.stream()
                .sorted(Comparator.comparingInt(UploadedPart::partNumber))
                .toList();
        String parent = SftpObjectStorage.parentOf(targetPath);
        String temp = SftpObjectStorage.tempPath(targetPath);
        try (SftpConnection.Channel channel = connection.open()) {
            SFTPClient sftp = channel.sftp();
            SftpObjectStorage.mkdirs(sftp, parent);
            try (RemoteFile out = sftp.open(temp, Set.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))) {
                try (OutputStream os = out.new RemoteFileOutputStream()) {
                    for (UploadedPart part : sorted) {
                        try (RemoteFile in = sftp.open(partPath(part.partNumber()), Set.of(OpenMode.READ))) {
                            try (InputStream is = in.new RemoteFileInputStream()) {
                                is.transferTo(os);
                            }
                        }
                    }
                }
            }
            SftpObjectStorage.publish(sftp, temp, targetPath, metadata, false);
        } catch (IOException e) {
            deleteQuietly(temp);
            // As partes ficam: o complete pode ser repetido, e o abort as apaga.
            throw new StorageException("Falha ao concluir upload de " + key, e);
        }
        deleteUploadDir();
    }

    @Override
    public void abort() {
        deleteUploadDir();
    }

    @Override
    public List<UploadedPart> listParts() {
        try (SftpConnection.Channel channel = connection.open()) {
            SFTPClient sftp = channel.sftp();
            List<RemoteResourceInfo> entries;
            try {
                entries = sftp.ls(uploadDir);
            } catch (SFTPException alreadyGone) {
                return List.of();   // concluído/abortado
            }
            return entries.stream()
                    .map(RemoteResourceInfo::getName)
                    .filter(name -> name.startsWith("part-"))
                    .map(name -> new UploadedPart(Integer.parseInt(name.substring("part-".length())), name, null))
                    .sorted(Comparator.comparingInt(UploadedPart::partNumber))
                    .toList();
        } catch (IOException e) {
            throw new StorageException("Falha ao listar partes do upload de " + key, e);
        }
    }

    private String partPath(int partNumber) {
        return uploadDir + "/part-" + partNumber;
    }

    private void deleteUploadDir() {
        try (SftpConnection.Channel channel = connection.open()) {
            SFTPClient sftp = channel.sftp();
            List<RemoteResourceInfo> entries;
            try {
                entries = sftp.ls(uploadDir);
            } catch (SFTPException notFound) {
                return;   // já não existe: nada a limpar
            }
            for (RemoteResourceInfo entry : entries) {
                sftp.rm(entry.getPath());
            }
            sftp.rmdir(uploadDir);
        } catch (IOException ignored) {
            // ponytail: limpeza best-effort; uma parte órfã em .uploads não afeta a leitura de objetos
        }
    }

    private void deleteQuietly(String path) {
        try (SftpConnection.Channel channel = connection.open()) {
            SFTPClient sftp = channel.sftp();
            sftp.rm(path);
        } catch (IOException ignored) {
            // a falha que importa é a original do upload
        }
    }
}
