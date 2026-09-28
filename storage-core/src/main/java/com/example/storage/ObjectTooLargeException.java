package com.example.storage;

/**
 * O objeto passaria de {@link MultipartConfig#maxObjectBytes()}. O upload já foi abortado
 * quando esta exceção chega ao chamador.
 */
public class ObjectTooLargeException extends StorageException {

    private final long limit;

    public ObjectTooLargeException(String message, long limit) {
        super(message, null);
        this.limit = limit;
    }

    public long limit() {
        return limit;
    }
}
