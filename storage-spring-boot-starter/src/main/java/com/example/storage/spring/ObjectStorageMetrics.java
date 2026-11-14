package com.example.storage.spring;

import com.example.storage.AccessDeniedException;
import com.example.storage.ByteRange;
import com.example.storage.DeleteResult;
import com.example.storage.ListEntry;
import com.example.storage.MultipartSession;
import com.example.storage.ObjectInfo;
import com.example.storage.ObjectContent;
import com.example.storage.ObjectMetadata;
import com.example.storage.ObjectNotFoundException;
import com.example.storage.ObjectStorage;
import com.example.storage.UploadedPart;
import com.example.storage.PresignedRequest;
import com.example.storage.PutOptions;
import com.example.storage.Retention;
import com.example.storage.ObjectRetainedException;
import com.example.storage.ObjectSummary;
import com.example.storage.ObjectTooLargeException;
import com.example.storage.PreconditionFailedException;
import com.example.storage.RangeNotSatisfiableException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
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
 * <p>Com o {@code micrometer-core}, registra também {@code storage.bytes}: o tamanho de cada
 * {@code put} ({@code direction=upload}, só se concluído) e os bytes lidos de cada
 * {@code open}/{@code read} ({@code download}, até o encerramento; {@code skip} não conta). {@code copy},
 * {@code presignPut} não entram: o tamanho não passa pelo decorador. As partes de multipart, sim
 * ({@code operation=uploadPart}).</p>
 *
 * <p>Uploads multipart: {@code storage.multipart} (duração, {@code outcome} {@code completed}/{@code aborted}/falha),
 * {@code storage.multipart.active} (abertos), {@code storage.multipart.parts} e {@code storage.bytes}
 * ({@code operation=uploadPart}); ver {@code ObservedMultipartSession}.</p>
 *
 * <p>{@code open} e {@code read} registram ainda {@code storage.operations.ttfb}, o tempo até o adapter
 * devolver o stream (só nas aberturas que deram certo), separado da duração do consumo, que depende do
 * cliente. Quanto disso é "primeiro byte" depende do adapter: no S3, por exemplo, é até os cabeçalhos.</p>
 *
 * <p>{@code deleteAll} registra {@code storage.deleteAll.keys} (tag {@code result}: {@code ok} ou
 * {@code failed}) e, se alguma chave falhou, {@code outcome=partial}: a chamada não lança, mas não
 * foi um sucesso.</p>
 *
 * <p>{@code outcome} é {@code success}; {@code not_found} ({@link ObjectNotFoundException} ou um
 * {@code head} que devolve vazio) e {@code unsupported} ({@link UnsupportedOperationException}: o
 * provedor não tem o recurso) e {@code partial}, que não marcam o span como erro; {@code retained}, {@code access_denied},
 * {@code precondition_failed}, {@code too_large} e {@code range_not_satisfiable} (a exceção
 * tipada correspondente); ou {@code error}, o resto. Os cinco últimos marcam o span como erro.</p>
 *
 * <p>{@code open}, {@code read}, {@code list} e {@code listDirectory} medem até o fim do consumo: o fim do stream, um erro de leitura
 * ou o {@code close}, o que vier primeiro. Um stream abandonado sem fechar nem esgotar nunca encerra
 * a observação, mas o {@code LongTaskTimer} {@code storage.operations.active}, que o handler do
 * Boot mantém enquanto ela está aberta, o expõe. Cada parte de multipart ({@link MultipartSession#uploadPart}) vira uma amostra de
 * {@code storage.multipart.parts}, não uma observação.</p>
 */
class ObjectStorageMetrics implements DelegatingObjectStorage {

    private final ObjectStorage delegate;
    private final ObservationRegistry registry;
    private final StorageId id;
    private final ExtraMeters extra;

    ObjectStorageMetrics(ObjectStorage delegate, ObservationRegistry registry, String storageName,
                         ExtraMeters extra) {
        this.delegate = delegate;
        this.registry = registry;
        this.id = StorageId.of(storageName, delegate);
        this.extra = extra;
    }

    @Override
    public ObjectStorage delegate() {
        return delegate;
    }

    private Observation start(String operation) {
        return Observation.createNotStarted("storage.operations", registry)
                .contextualName("storage " + operation)
                .lowCardinalityKeyValue("operation", operation)
                .lowCardinalityKeyValue("storage", id.name())
                .lowCardinalityKeyValue("provider", id.provider())
                .start();
    }

    private static void stop(Observation observation, Throwable failure) {
        stop(observation, failure, "success");
    }

    private static void stop(Observation observation, Throwable failure, String okOutcome) {
        String outcome = okOutcome;
        if (failure instanceof ObjectNotFoundException) {
            outcome = "not_found";
        } else if (failure instanceof UnsupportedOperationException) {
            outcome = "unsupported";
        } else if (failure != null) {
            // ObjectRetainedException é um AccessDeniedException: tem de vir antes.
            outcome = failure instanceof ObjectRetainedException ? "retained"
                    : failure instanceof AccessDeniedException ? "access_denied"
                    : failure instanceof PreconditionFailedException ? "precondition_failed"
                    : failure instanceof ObjectTooLargeException ? "too_large"
                    : failure instanceof RangeNotSatisfiableException ? "range_not_satisfiable"
                    : "error";
            observation.error(failure);
        }
        observation.lowCardinalityKeyValue("outcome", outcome).stop();
    }

    private <T> T timed(String operation, Supplier<T> call) {
        return timed(operation, call, result -> "success");
    }

    /** {@code outcomeOf} dá o outcome de um retorno normal ({@code head} vazio: {@code not_found}). */
    private <T> T timed(String operation, Supplier<T> call, Function<T, String> outcomeOf) {
        Observation observation = start(operation);
        Throwable failure = null;
        String okOutcome = "success";
        try (Observation.Scope scope = observation.openScope()) {
            T result = call.get();
            okOutcome = outcomeOf.apply(result);
            return result;
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            stop(observation, failure, okOutcome);
        }
    }

    /** Como {@link #timed}, mas só encerra a observação na falha: no sucesso, quem consome encerra. */
    private static <T> T opened(Observation observation, Supplier<T> call) {
        try (Observation.Scope scope = observation.openScope()) {
            return call.get();
        } catch (RuntimeException | Error e) {
            stop(observation, e);
            throw e;
        }
    }

    @Override
    public String put(String key, InputStream data, long length, PutOptions options) {
        String version = timed("put", () -> delegate.put(key, data, length, options));
        extra.bytes("put", id, "upload", length);
        return version;
    }

    @Override
    public MultipartSession initiateMultipart(String key, ObjectMetadata metadata) {
        return observed(timed("initiateMultipart", () -> delegate.initiateMultipart(key, metadata)));
    }

    @Override
    public MultipartSession initiateMultipart(String key, ObjectMetadata metadata, Retention retention) {
        return observed(timed("initiateMultipart", () -> delegate.initiateMultipart(key, metadata, retention)));
    }

    @Override
    public Optional<Retention> getRetention(String key) {
        return timed("getRetention", () -> delegate.getRetention(key));
    }

    @Override
    public void setRetention(String key, Retention retention) {
        timed("setRetention", () -> {
            delegate.setRetention(key, retention);
            return null;
        });
    }

    @Override
    public boolean getLegalHold(String key) {
        return timed("getLegalHold", () -> delegate.getLegalHold(key));
    }

    @Override
    public void setLegalHold(String key, boolean hold) {
        timed("setLegalHold", () -> {
            delegate.setLegalHold(key, hold);
            return null;
        });
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        return timed("head", () -> delegate.head(key), r -> r.isEmpty() ? "not_found" : "success");
    }

    @Override
    public InputStream open(String key, ByteRange range) {
        Observation observation = start("open");
        long start = System.nanoTime();
        InputStream stream = opened(observation, () -> delegate.open(key, range));
        extra.ttfb("open", id, System.nanoTime() - start);
        return new ObservedInputStream(stream, new Ending(observation, "open"));
    }

    @Override
    public ObjectContent read(String key, ByteRange range) {
        Observation observation = start("read");
        long start = System.nanoTime();
        ObjectContent content = opened(observation, () -> delegate.read(key, range));
        extra.ttfb("read", id, System.nanoTime() - start);
        return new ObjectContent(new ObservedInputStream(content.stream(), new Ending(observation, "read")),
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
        Ending ending = new Ending(observation, null);
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
        DeleteResult result = timed("deleteAll", () -> delegate.deleteAll(keys),
                r -> r.isSuccess() ? "success" : "partial");
        long failed = result.failures().size();
        extra.deletedKeys(id, keys.size() - failed, failed);
        return result;
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

    private MultipartSession observed(MultipartSession session) {
        Observation upload = Observation.createNotStarted("storage.multipart", registry)
                .contextualName("storage multipart")
                .lowCardinalityKeyValue("storage", id.name())
                .lowCardinalityKeyValue("provider", id.provider())
                .start();
        return new ObservedMultipartSession(session, upload);
    }

    /**
     * Observa o upload multipart de {@code initiateMultipart} até {@code complete} ou {@code abort}:
     * {@code storage.multipart} (duração do upload, {@code outcome} {@code completed}, {@code aborted}
     * ou o da falha do {@code complete}) e, enquanto aberto, o {@code LongTaskTimer}
     * {@code storage.multipart.active}: um upload nunca concluído nem abortado (o que o storage cobra
     * como parte órfã) não sai dele. {@code complete} e {@code abort} também entram em
     * {@code storage.operations}; cada {@code uploadPart}, em {@code storage.multipart.parts} e
     * {@code storage.bytes}, sem uma observação por parte (podem ser 10 mil). Um retry de parte conta de novo.
     */
    private final class ObservedMultipartSession implements MultipartSession {

        private final MultipartSession session;
        private final Observation upload;
        private final AtomicBoolean finished = new AtomicBoolean();

        ObservedMultipartSession(MultipartSession session, Observation upload) {
            this.session = session;
            this.upload = upload;
        }

        @Override
        public String key() {
            return session.key();
        }

        @Override
        public String uploadId() {
            return session.uploadId();
        }

        @Override
        public UploadedPart uploadPart(int partNumber, byte[] data, int length) {
            long start = System.nanoTime();
            try {
                UploadedPart part = session.uploadPart(partNumber, data, length);
                extra.part(id, "success", System.nanoTime() - start);
                extra.bytes("uploadPart", id, "upload", length);
                return part;
            } catch (RuntimeException | Error e) {
                extra.part(id, "error", System.nanoTime() - start);
                throw e;
            }
        }

        @Override
        public void complete(List<UploadedPart> parts) {
            try {
                timed("multipartComplete", () -> {
                    session.complete(parts);
                    return null;
                });
            } catch (RuntimeException | Error e) {
                finish(e, "completed");
                throw e;
            }
            finish(null, "completed");
        }

        @Override
        public void abort() {
            try {
                timed("multipartAbort", () -> {
                    session.abort();
                    return null;
                });
            } finally {
                finish(null, "aborted");
            }
        }

        @Override
        public List<UploadedPart> listParts() {
            return session.listParts();
        }

        @Override
        public List<UploadedPart> listCompletedParts() {
            return session.listCompletedParts();
        }

        /** Só o primeiro desfecho conta: um {@code abort} depois de um {@code complete} falho não o troca. */
        private void finish(Throwable failure, String outcome) {
            if (finished.compareAndSet(false, true)) {
                stop(upload, failure, outcome);
            }
        }
    }

    /**
     * Encerra a observação uma única vez, no primeiro evento: fim, erro ou close. Com {@code operation}
     * (leituras), registra também os bytes lidos até ali, inclusive os de uma leitura interrompida.
     */
    private final class Ending {

        private final Observation observation;
        private final String operation;
        private final AtomicLong read = new AtomicLong();
        // O corpo pode ser fechado por outra thread (timeout do container) enquanto a de I/O chega ao fim.
        private final AtomicBoolean stopped = new AtomicBoolean();

        Ending(Observation observation, String operation) {
            this.observation = observation;
            this.operation = operation;
        }

        void bytesRead(long count) {
            read.addAndGet(count);
        }

        void stop(Throwable failure) {
            if (stopped.compareAndSet(false, true)) {
                ObjectStorageMetrics.stop(observation, failure);
                if (operation != null && read.get() > 0) {
                    extra.bytes(operation, id, "download", read.get());
                }
            }
        }
    }

    private final class ObservedInputStream extends FilterInputStream {

        private final Ending ending;

        ObservedInputStream(InputStream in, Ending ending) {
            super(in);
            this.ending = ending;
        }

        @Override
        public int read() throws IOException {
            try {
                int b = in.read();
                if (b >= 0) {
                    ending.bytesRead(1);
                }
                return ended(b);
            } catch (IOException | RuntimeException e) {
                ending.stop(e);
                throw e;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                int n = in.read(b, off, len);
                if (n > 0) {
                    ending.bytesRead(n);
                }
                return ended(n);
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
            // Sem SIZED: com ele, count() responde sem percorrer e a observação não terminaria.
            super(source.estimateSize(), source.characteristics() & ~(SIZED | SUBSIZED));
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
