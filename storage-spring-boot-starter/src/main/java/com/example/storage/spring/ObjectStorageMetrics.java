package com.example.storage.spring;

import com.example.storage.ByteRange;
import com.example.storage.MultipartSession;
import com.example.storage.ObjectInfo;
import com.example.storage.ObjectMetadata;
import com.example.storage.ObjectStorage;
import com.example.storage.PresignedRequest;
import com.example.storage.PutOptions;
import com.example.storage.ObjectSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Decora um {@link ObjectStorage}, medindo cada operação em {@code storage.operations}
 * (tags {@code operation}, {@code storage}, {@code outcome}). Os métodos com default no
 * próprio {@link ObjectStorage} (ex.: {@code read}, {@code listDirectory}, {@code deleteAll})
 * não são medidos aqui: chamam os métodos abaixo, que já são.
 *
 * <p>{@code list} mede só a chamada que abre o stream; consumi-lo é responsabilidade de quem
 * chamou. Partes de multipart ({@link MultipartSession#uploadPart}) não são medidas.</p>
 */
class ObjectStorageMetrics implements ObjectStorage {

    private final ObjectStorage delegate;
    private final MeterRegistry registry;
    private final String storageName;

    ObjectStorageMetrics(ObjectStorage delegate, MeterRegistry registry, String storageName) {
        this.delegate = delegate;
        this.registry = registry;
        this.storageName = storageName;
    }

    private <T> T timed(String operation, Supplier<T> call) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            return call.get();
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            sample.stop(Timer.builder("storage.operations")
                    .tag("operation", operation)
                    .tag("storage", storageName)
                    .tag("outcome", outcome)
                    .register(registry));
        }
    }

    @Override
    public String put(String key, InputStream data, long length, PutOptions options) {
        return timed("put", () -> delegate.put(key, data, length, options));
    }

    @Override
    public MultipartSession initiateMultipart(String key, ObjectMetadata metadata) {
        return timed("initiateMultipart", () -> delegate.initiateMultipart(key, metadata));
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        return timed("head", () -> delegate.head(key));
    }

    @Override
    public InputStream open(String key, ByteRange range) {
        return timed("open", () -> delegate.open(key, range));
    }

    @Override
    public void checkAccess() {
        timed("checkAccess", () -> {
            delegate.checkAccess();
            return null;
        });
    }

    @Override
    public Stream<ObjectSummary> list(String prefix) {
        return timed("list", () -> delegate.list(prefix));
    }

    @Override
    public void delete(String key) {
        timed("delete", () -> {
            delegate.delete(key);
            return null;
        });
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        timed("copy", () -> {
            delegate.copy(sourceKey, targetKey);
            return null;
        });
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        return timed("presignGet", () -> delegate.presignGet(key, ttl));
    }

    @Override
    public URI presignGet(String key, Duration ttl, String downloadName) {
        return timed("presignGet", () -> delegate.presignGet(key, ttl, downloadName));
    }

    @Override
    public PresignedRequest presignPut(String key, Duration ttl, PutOptions options) {
        return timed("presignPut", () -> delegate.presignPut(key, ttl, options));
    }
}
