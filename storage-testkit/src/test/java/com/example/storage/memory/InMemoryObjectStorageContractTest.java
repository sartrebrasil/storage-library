package com.example.storage.memory;

import com.example.storage.ObjectStorage;
import com.example.storage.testkit.ObjectStorageContract;

class InMemoryObjectStorageContractTest extends ObjectStorageContract {

    private final InMemoryObjectStorage storage = new InMemoryObjectStorage();

    @Override
    protected ObjectStorage storage() {
        return storage;
    }

    @Override
    protected boolean supportsHttpPresign() {
        return false;
    }
}
