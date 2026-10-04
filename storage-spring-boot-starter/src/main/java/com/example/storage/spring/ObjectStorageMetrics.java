package com.example.storage.spring;

import com.example.storage.ByteRange;
import com.example.storage.DeleteResult;
import com.example.storage.ListEntry;
import com.example.storage.MultipartSession;
import com.example.storage.ObjectInfo;
import com.example.storage.ObjectContent;
import com.example.storage.ObjectMetadata;
import com.example.storage.ObjectNotFoundException;
import com.example.storage.ObjectStorage;
import com.example.storage.PresignedRequest;
import com.example.storage.PutOptions;
import com.example.storage.ObjectSummary;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Decora um {@link ObjectStorage}, observando cada operação em {@code storage.operations}
 * (tags {@code operation}, {@code storage}, {@code outcome}). Com o handler de métricas do Boot,
 * a observação vira o timer {@code storage.operations}; com Micrometer Tracing, vira também um
 * span ({@code storage put}, {@code storage head}...), filho da observação corrente da thread.
 * Todo método de {@link ObjectStorage} é repassado ao adapter, inclusive os que têm default na
 * interface ({@code read}, {@code listDirectory}, {@code deleteAll}): os adapters os sobrescrevem
 * com versões nativas (uma leitura só, delimiter, lote), que os defaults substituiriam.
 *
 * <p>{@code outcome} é {@code success}, {@code not_found} ({@link ObjectNotFoundException}, que
 * não marca o span como erro) ou {@code error}.</p>
 *
 * <p>{@code open}, {@code read}, {@code list} e {@code listDirectory} medem até o fim do consumo: o fim do stream, um erro de leitura
 * ou o {@code close}, o que vier primeiro. Um stream abandonado sem fechar nem esgotar nunca encerra
 * a observação. Partes de multipart ({@link MultipartSession#uploadPart}) não são medidas.</p>
 */
class ObjectStorageMetrics implements DelegatingObjectStorage {

    private final ObjectStorage delegate;
    private final ObservationRegistry registry;
    private final String storageName;

    ObjectStorageMetrics(ObjectStorage delegate, ObservationRegistry registry, String storageName) {
        this.delegate = delegate;
        this.registry = registry;
        this.storageName = storageName;
    }

    @Override
    public ObjectStorage delegate() {
        return delegate;
    }

    private Observation start(String operation) {
        return Observation.createNotStarted("storage.operations", registry)
                .contextualName("storage " + operation)
                .lowCardinalityKeyValue("operation", operation)
                .lowCardinalityKeyValue("storage", storageName)
                .start();
    }

    private static void stop(Observation observation, Throwable failure) {
        String outcome = "success";
        if (failure instanceof ObjectNotFoundException) {
            outcome = "not_found";
        } else if (failure != null) {
            outcome = "error";
            observation.error(failure);
        }
        observation.lowCardinalityKeyValue("outcome", outcome).stop();
    }

    private <T> T timed(String operation, Supplier<T> call) {
        Observation observation = start(operation);
        RuntimeException failure = null;
        try (Observation.Scope scope = observation.openScope()) {
            return call.get();
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            stop(observation, failure);
        }
    }

    /** Como {@link #timed}, mas só encerra a observação na falha: no sucesso, quem consome encerra. */
    private static <T> T opened(Observation observation, Supplier<T> call) {
        try (Observation.Scope scope = observation.openScope()) {
            return call.get();
        } catch (RuntimeException e) {
            stop(observation, e);
            throw e;
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
        Observation observation = start("open");
        return new ObservedInputStream(opened(observation, () -> delegate.open(key, range)), new Ending(observation));
    }

    @Override
    public ObjectContent read(String key, ByteRange range) {
        Observation observation = start("read");
        ObjectContent content = opened(observation, () -> delegate.read(key, range));
        return new ObjectContent(new ObservedInputStream(content.stream(), new Ending(observation)),
                content.range(), content.totalSize());
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
        return observedStream("list", () -> delegate.list(prefix));
    }

    @Override
    public Stream<ListEntry> listDirectory(String prefix) {
        return observedStream("listDirectory", () -> delegate.listDirectory(prefix));
    }

    private <T> Stream<T> observedStream(String operation, Supplier<Stream<T>> call) {
        Observation observation = start(operation);
        Stream<T> items = opened(observation, call);
        Ending ending = new Ending(observation);
        return StreamSupport.stream(new ObservedSpliterator<>(items.spliterator(), ending), false)
                .onClose(items::close)
                .onClose(() -> ending.stop(null));
    }

    @Override
    public void delete(String key) {
        timed("delete", () -> {
            delegate.delete(key);
            return null;
        });
    }

    @Override
    public DeleteResult deleteAll(Collection<String> keys) {
        return timed("deleteAll", () -> delegate.deleteAll(keys));
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

    /** Encerra a observação uma única vez, no primeiro evento: fim, erro ou close. */
    private static final class Ending {

        private final Observation observation;
        private boolean stopped;

        Ending(Observation observation) {
            this.observation = observation;
        }

        void stop(Throwable failure) {
            if (!stopped) {
                stopped = true;
                ObjectStorageMetrics.stop(observation, failure);
            }
        }
    }

    private static final class ObservedInputStream extends FilterInputStream {

        private final Ending ending;

        ObservedInputStream(InputStream in, Ending ending) {
            super(in);
            this.ending = ending;
        }

        @Override
        public int read() throws IOException {
            try {
                return ended(in.read());
            } catch (IOException | RuntimeException e) {
                ending.stop(e);
                throw e;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return ended(in.read(b, off, len));
            } catch (IOException | RuntimeException e) {
                ending.stop(e);
                throw e;
            }
        }

        private int ended(int result) {
            if (result < 0) {
                ending.stop(null);
            }
            return result;
        }

        @Override
        public void close() throws IOException {
            try {
                in.close();
            } catch (IOException | RuntimeException e) {
                ending.stop(e);
                throw e;
            }
            ending.stop(null);
        }
    }

    private static final class ObservedSpliterator<T> extends Spliterators.AbstractSpliterator<T> {

        private final Spliterator<T> source;
        private final Ending ending;

        ObservedSpliterator(Spliterator<T> source, Ending ending) {
            super(source.estimateSize(), source.characteristics());
            this.source = source;
            this.ending = ending;
        }

        @Override
        public boolean tryAdvance(Consumer<? super T> action) {
            // Exceção do action é de quem consome, não do storage: não vira outcome=error.
            boolean[] inAction = {false};
            try {
                boolean more = source.tryAdvance(item -> {
                    inAction[0] = true;
                    action.accept(item);
                    inAction[0] = false;
                });
                if (!more) {
                    ending.stop(null);
                }
                return more;
            } catch (RuntimeException e) {
                if (!inAction[0]) {
                    ending.stop(e);
                }
                throw e;
            }
        }
    }
}
