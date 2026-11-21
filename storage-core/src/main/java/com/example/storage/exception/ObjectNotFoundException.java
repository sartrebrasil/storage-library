package com.example.storage.exception;

import com.example.storage.ObjectStorage;

/** O objeto não existe. {@link ObjectStorage#head} devolve {@code Optional.empty()} em vez disso. */
public class ObjectNotFoundException extends StorageException {

    public ObjectNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
