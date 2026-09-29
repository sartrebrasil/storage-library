package com.example.storage.filesystem;

import com.example.storage.ObjectStorage;
import com.example.storage.testkit.ObjectStorageContract;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class FileSystemObjectStorageTest extends ObjectStorageContract {

    @TempDir
    Path root;

    @Override
    protected ObjectStorage storage() {
        return new FileSystemObjectStorage(root);
    }

    @Override
    protected boolean supportsHttpPresign() {
        return false;   // presign devolve file:// — não acessível por HttpClient
    }

    @Override
    protected ObjectStorage storageWithMissingBucket() {
        return new FileSystemObjectStorage(root.resolve("nao-existe"));
    }

    @Override
    protected int listCount() {
        return 200;   // sem paginação nativa para exercitar; um scan menor já cobre a ordenação
    }
}
