package com.example.storage;

/** Credencial ausente, inválida ou sem permissão para a operação. */
public class AccessDeniedException extends StorageException {

    public AccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
