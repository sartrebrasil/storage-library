package com.example.storage.spring;

import com.example.storage.AccessDeniedException;
import com.example.storage.ByteRange;
import com.example.storage.DeleteResult;
import com.example.storage.MultipartConfig;
import com.example.storage.MultipartSession;
import com.example.storage.ObjectMetadata;
import com.example.storage.ObjectRetainedException;
import com.example.storage.ObjectStorage;
import com.example.storage.ObjectTooLargeException;
import com.example.storage.PreconditionFailedException;
import com.example.storage.PutOptions;
import com.example.storage.RangeNotSatisfiableException;
import com.example.storage.Retention;
import com.example.storage.StorageException;
import com.example.storage.UploadedPart;
import com.example.storage.memory.InMemoryObjectStorage;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixa o vocabulário que dashboards e alertas dos consumidores usam: nomes de meter, chaves de tag e
 * valores de {@code operation} e {@code outcome}. Mudar qualquer um é uma quebra de contrato: atualize
 * o README e este teste juntos.
 */
class StorageMetricsContractTest {

    private static final Map<String, Set<String>> TAG_KEYS = Map.of(
            "storage.operations", Set.of("operation", "storage", "provider", "outcome", "error"),
            "storage.operations.active", Set.of("operation", "storage", "provider"),
            "storage.operations.ttfb", Set.of("operation", "storage", "provider"),
            "storage.bytes", Set.of("operation", "storage", "provider", "direction"),
            "storage.deleteAll.keys", Set.of("storage", "provider", "result"),
            "storage.multipart", Set.of("storage", "provider", "outcome", "error"),
            "storage.multipart.active", Set.of("storage", "provider"),
            "storage.multipart.parts", Set.of("storage", "provider", "outcome"));

    private static final Set<String> OPERATIONS = Set.of("put", "initiateMultipart", "getRetention",
            "setRetention", "getLegalHold", "setLegalHold", "head", "open", "read", "checkAccess", "list",
            "listDirectory", "delete", "deleteAll", "copy", "presignGet", "presignPut",
            "multipartComplete", "multipartAbort");

    private static final Set<String> OUTCOMES = Set.of("success", "not_found", "unsupported", "partial",
            "retained", "access_denied", "precondition_failed", "too_large", "range_not_satisfiable", "error");

    @Test
    void nomesTagsEValoresSaoOContrato() {
        Map<String, RuntimeException> failures = new TreeMap<>(Map.of(
                "retained", new ObjectRetainedException("x", null),
                "access_denied", new AccessDeniedException("x", null),
                "precondition_failed", new PreconditionFailedException("x", null),
                "too_large", new ObjectTooLargeException("x", 1),
                "range_not_satisfiable", new RangeNotSatisfiableException("x", null),
                "unsupported", new UnsupportedOperationException("x"),
                "error", new StorageException("x", null)));
        ObjectStorage failing = Mockito.mock(ObjectStorage.class);
        failures.forEach((key, e) -> Mockito.doThrow(e).when(failing).delete(key));
        Mockito.when(failing.deleteAll(List.of("a"))).thenReturn(
                new DeleteResult(Map.of("a", new StorageException("x", null))));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class,
                        StorageMetricsAutoConfiguration.class))
                .withUserConfiguration(StorageMetricsAutoConfigurationTest.Observations.class)
                .withBean("memory", ObjectStorage.class, InMemoryObjectStorage::new)
                .withBean("failing", ObjectStorage.class, () -> failing)
                .run(context -> {
                    ObjectStorage storage = context.getBean("memory", ObjectStorage.class);
                    ObjectStorage broken = context.getBean("failing", ObjectStorage.class);

                    storage.put("k", "v".getBytes(), PutOptions.of("text/plain"));
                    storage.head("k");
                    storage.head("ausente");
                    storage.open("k").readAllBytes();
                    storage.read("k", ByteRange.all()).close();
                    storage.list("").toList();
                    storage.listDirectory("").toList();
                    storage.copy("k", "k2");
                    storage.presignGet("k", Duration.ofMinutes(1));
                    storage.presignPut("k", Duration.ofMinutes(1), PutOptions.of("text/plain"));
                    storage.setRetention("k", new Retention(Instant.now().plusSeconds(60), Retention.Mode.GOVERNANCE));
                    storage.getRetention("k");
                    storage.setLegalHold("k", true);
                    storage.getLegalHold("k");
                    storage.checkAccess();
                    storage.delete("k2");
                    storage.deleteAll(List.of("k2"));
                    for (String key : failures.keySet()) {
                        try {
                            broken.delete(key);
                        } catch (RuntimeException expected) {
                            // só o outcome interessa
                        }
                    }
                    broken.deleteAll(List.of("a"));

                    MultipartSession completed = storage.initiateMultipart("m", ObjectMetadata.of("text/plain"));
                    UploadedPart part = completed.uploadPart(1, new byte[1], 1);
                    completed.complete(List.of(part));
                    MultipartSession aborted = storage.initiateMultipart("n", ObjectMetadata.of("text/plain"));
                    aborted.abort();
                    int min = MultipartConfig.MIN_PART_SIZE;
                    MultipartSession bad = storage.initiateMultipart("o", ObjectMetadata.of("text/plain"));
                    UploadedPart small = bad.uploadPart(1, new byte[1], 1);
                    UploadedPart other = bad.uploadPart(2, new byte[1], 1);
                    try {
                        bad.complete(List.of(small, other));   // parte 1 abaixo do mínimo
                    } catch (StorageException expected) {
                        // outcome=error em storage.multipart
                    }

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    Map<String, Set<String>> keys = registry.getMeters().stream().collect(Collectors.groupingBy(
                            m -> m.getId().getName(), TreeMap::new, Collectors.flatMapping(
                                    m -> m.getId().getTags().stream().map(Tag::getKey), Collectors.toCollection(TreeSet::new))));

                    assertThat(keys).isEqualTo(new TreeMap<>(TAG_KEYS).entrySet().stream().collect(
                            Collectors.toMap(Map.Entry::getKey, e -> (Set<String>) new TreeSet<>(e.getValue()),
                                    (a, b) -> a, TreeMap::new)));
                    assertThat(values(registry, "storage.operations", "operation")).isEqualTo(OPERATIONS);
                    assertThat(values(registry, "storage.operations", "outcome")).isEqualTo(OUTCOMES);
                    assertThat(values(registry, "storage.multipart", "outcome")).containsExactlyInAnyOrder(
                            "completed", "aborted", "error");
                    assertThat(values(registry, "storage.multipart.parts", "outcome")).containsExactly("success");
                    assertThat(values(registry, "storage.bytes", "direction")).containsExactlyInAnyOrder(
                            "upload", "download");
                    assertThat(values(registry, "storage.deleteAll.keys", "result")).containsExactlyInAnyOrder(
                            "ok", "failed");
                    assertThat(values(registry, "storage.operations", "provider")).containsExactly("unknown");
                });
    }

    private static Set<String> values(MeterRegistry registry, String meter, String tag) {
        return registry.getMeters().stream().filter(m -> m.getId().getName().equals(meter))
                .map(Meter::getId).map(id -> id.getTag(tag)).collect(Collectors.toCollection(TreeSet::new));
    }
}
