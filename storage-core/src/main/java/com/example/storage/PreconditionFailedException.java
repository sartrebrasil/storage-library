package com.example.storage;

/** A escrita condicional ({@link Condition}) não foi satisfeita. */
public class PreconditionFailedException extends StorageException {

    public PreconditionFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
