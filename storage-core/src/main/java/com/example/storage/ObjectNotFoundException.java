package com.example.storage;

/** O objeto não existe. {@link ObjectStorage#head} devolve {@code Optional.empty()} em vez disso. */
public class ObjectNotFoundException extends StorageException {

    public ObjectNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
