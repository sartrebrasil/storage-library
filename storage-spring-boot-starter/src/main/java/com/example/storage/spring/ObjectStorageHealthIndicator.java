package com.example.storage.spring;

import com.example.storage.ObjectStorage;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

/**
 * {@code UP} quando {@link ObjectStorage#checkAccess()} passa. Qualquer falha vira {@code DOWN}
 * com a exceção nos detalhes (e um WARN no log, via {@link AbstractHealthIndicator}).
 */
public class ObjectStorageHealthIndicator extends AbstractHealthIndicator {

    private final ObjectStorage storage;

    public ObjectStorageHealthIndicator(ObjectStorage storage) {
        super("Falha ao acessar o object storage");
        this.storage = storage;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        storage.checkAccess();
        builder.up();
    }
}
