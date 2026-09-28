package com.example.storage;

/** Falha no storage. Subclasses indicam os casos que o chamador costuma tratar. */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Converte o status HTTP devolvido pelo provedor na exceção correspondente. */
    public static StorageException fromHttpStatus(int status, String message, Throwable cause) {
        return switch (status) {
            case 404 -> new ObjectNotFoundException(message, cause);
            case 416 -> new RangeNotSatisfiableException(message, cause);
            case 412 -> new PreconditionFailedException(message, cause);
            case 401, 403 -> new AccessDeniedException(message, cause);
            default -> new StorageException(message, cause);
        };
    }
}
