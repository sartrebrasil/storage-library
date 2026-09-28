package com.example.storage;

/** A faixa pedida não existe no objeto (HTTP 416): começa no fim dele ou depois, ou o objeto é vazio. */
public class RangeNotSatisfiableException extends StorageException {

    public RangeNotSatisfiableException(String message, Throwable cause) {
        super(message, cause);
    }
}
