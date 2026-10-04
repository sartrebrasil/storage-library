package com.example.storage.filesystem;

import com.example.storage.MultipartSession;
import com.example.storage.ObjectMetadata;
import com.example.storage.StorageException;
import com.example.storage.UploadedPart;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;

/**
 * Cada parte vira um arquivo em {@code .uploads/<uploadId>/part-<n>}; {@link #complete}
 * concatena os arquivos direto no destino, sem manter nenhuma parte inteira em memória.
 */
final class FileSystemMultipartSession implements MultipartSession {

    private final Path targetPath;
    private final Path uploadDir;
    private final String key;
    private final String uploadId;
    private final ObjectMetadata metadata;
    private final Object writeLock;

    FileSystemMultipartSession(Path targetPath, Path uploadDir, String key, ObjectMetadata metadata,
                               Object writeLock) {
        this.writeLock = writeLock;
        this.targetPath = targetPath;
        this.uploadDir = uploadDir;
        this.key = key;
        this.uploadId = uploadDir.getFileName().toString();
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
        try {
            Files.createDirectories(uploadDir);
            try (OutputStream out = Files.newOutputStream(partPath(partNumber),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write(data, 0, length);
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
        Path temp = uploadDir.resolveSibling(uploadId + "-assembled.tmp");
        try {
            Files.createDirectories(targetPath.getParent());
            try (OutputStream out = Files.newOutputStream(temp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (UploadedPart part : sorted) {
                    Files.copy(partPath(part.partNumber()), out);
                }
            }
            synchronized (writeLock) {
                FileSystemObjectStorage.publish(temp, targetPath, metadata);
            }
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
        try (var files = Files.list(uploadDir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("part-"))
                    .map(name -> new UploadedPart(Integer.parseInt(name.substring("part-".length())), name, null))
                    .sorted(Comparator.comparingInt(UploadedPart::partNumber))
                    .toList();
        } catch (NoSuchFileException alreadyGone) {
            return List.of();   // concluído/abortado
        } catch (IOException e) {
            throw new StorageException("Falha ao listar partes do upload de " + key, e);
        }
    }

    private Path partPath(int partNumber) {
        return uploadDir.resolve("part-" + partNumber);
    }

    private void deleteUploadDir() {
        if (!Files.isDirectory(uploadDir)) {
            return;
        }
        try (var files = Files.walk(uploadDir)) {
            files.sorted(Comparator.reverseOrder()).forEach(FileSystemMultipartSession::deleteQuietly);
        } catch (IOException ignored) {
            // ponytail: limpeza best-effort; uma parte órfã em .uploads não afeta a leitura de objetos
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // a falha que importa é a original do upload
        }
    }
}
