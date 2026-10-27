package com.example.storage;

/** O objeto está sob retenção ou legal hold e a operação o apagaria, sobrescreveria ou reduziria a proteção. */
public class ObjectRetainedException extends AccessDeniedException {

    public ObjectRetainedException(String message, Throwable cause) {
        super(message, cause);
    }
}
