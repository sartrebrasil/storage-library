package com.example.storage.spring;

import com.example.storage.AccessDeniedException;
import com.example.storage.ByteRange;
import com.example.storage.CommonPrefix;
import com.example.storage.DeleteResult;
import com.example.storage.ObjectContent;
import com.example.storage.MultipartSession;
import com.example.storage.ObjectMetadata;
import com.example.storage.ObjectNotFoundException;
import com.example.storage.UploadedPart;
import com.example.storage.ObjectRetainedException;
import com.example.storage.ObjectTooLargeException;
import com.example.storage.PreconditionFailedException;
import com.example.storage.RangeNotSatisfiableException;
import com.example.storage.ObjectStorage;
import com.example.storage.ObjectSummary;
import com.example.storage.StorageException;
import com.example.storage.memory.InMemoryObjectStorage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageMetricsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class, StorageMetricsAutoConfiguration.class));

    @Test
    void chamadaBemSucedidaRegistraOutcomeSuccess() {
        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> {
                    context.getBean(ObjectStorage.class).put("k", "v".getBytes(),
                            com.example.storage.PutOptions.of("text/plain"));

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("storage.operations").tag("operation", "put").tag("outcome", "success")
                            .timer().count()).isEqualTo(1);
                });
    }

    @Test
    void falhaRegistraOutcomeError() {
        ObjectStorage broken = org.mockito.Mockito.mock(ObjectStorage.class);
        org.mockito.Mockito.doThrow(new StorageException("indisponível", null)).when(broken).delete("k");

        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, () -> broken)
                .run(context -> {
                    assertThatThrownBy(() -> context.getBean(ObjectStorage.class).delete("k"))
                            .isInstanceOf(StorageException.class);

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("storage.operations").tag("operation", "delete").tag("outcome", "error")
                            .tag("error", "StorageException").timer().count()).isEqualTo(1);
                });
    }

    @Test
    void beanDecoradoUsaNomeDoBeanComoTagStorage() {
        runner.withUserConfiguration(Observations.class)
                .withBean("reportsStorage", ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> {
                    context.getBean("reportsStorage", ObjectStorage.class).head("k");

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("storage.operations").tag("storage", "reportsStorage").timer().count())
                            .isEqualTo(1);
                });
    }

    @Test
    void comAsAutoConfiguracoesDoActuatorDecoraEMede() {
        // Sem registry do usuário: o ObservationRegistry e os handlers vêm do Actuator, como numa aplicação real.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration.class,
                        MetricsAutoConfiguration.class, SimpleMetricsExportAutoConfiguration.class,
                        CompositeMeterRegistryAutoConfiguration.class, StorageAutoConfiguration.class,
                        StorageMetricsAutoConfiguration.class))
                .withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> {
                    ObjectStorage storage = context.getBean(ObjectStorage.class);
                    assertThat(storage).isInstanceOf(ObjectStorageMetrics.class);

                    storage.head("k");

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("storage.operations").tag("operation", "head").tag("outcome", "not_found")
                            .timer().count()).isEqualTo(1);
                });
    }

    @Test
    void readListDirectoryEDeleteAllChegamAosOverridesDoAdapter() throws Exception {
        ObjectStorage adapter = org.mockito.Mockito.mock(ObjectStorage.class);
        org.mockito.Mockito.when(adapter.read("k", ByteRange.all()))
                .thenReturn(new ObjectContent(new ByteArrayInputStream("v".getBytes()), ByteRange.all(), 1));
        org.mockito.Mockito.when(adapter.listDirectory("p/"))
                .thenReturn(Stream.of(new CommonPrefix("p/a/")));
        org.mockito.Mockito.when(adapter.deleteAll(List.of("k"))).thenReturn(new DeleteResult(Map.of()));

        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, () -> adapter)
                .run(context -> {
                    ObjectStorage storage = context.getBean(ObjectStorage.class);

                    try (ObjectContent content = storage.read("k", ByteRange.all())) {
                        assertThat(content.stream().readAllBytes()).isEqualTo("v".getBytes());
                    }
                    assertThat(storage.listDirectory("p/").toList()).containsExactly(new CommonPrefix("p/a/"));
                    assertThat(storage.deleteAll(List.of("k")).isSuccess()).isTrue();

                    // Os defaults da interface fariam head + open, list e delete no lugar das versões nativas.
                    org.mockito.Mockito.verify(adapter, org.mockito.Mockito.never()).head("k");
                    org.mockito.Mockito.verify(adapter, org.mockito.Mockito.never()).list("p/");
                    org.mockito.Mockito.verify(adapter, org.mockito.Mockito.never()).delete("k");
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    for (String operation : List.of("read", "listDirectory", "deleteAll")) {
                        assertThat(registry.get("storage.operations").tag("operation", operation)
                                .tag("outcome", "success").timer().count()).as(operation).isEqualTo(1);
                    }
                });
    }

    @Test
    void errorDoAdapterNaoContaComoSucesso() {
        ObjectStorage broken = org.mockito.Mockito.mock(ObjectStorage.class);
        org.mockito.Mockito.doThrow(new LinkageError("classe ausente")).when(broken).delete("k");

        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, () -> broken)
                .run(context -> {
                    assertThatThrownBy(() -> context.getBean(ObjectStorage.class).delete("k"))
                            .isInstanceOf(LinkageError.class);

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("storage.operations").tag("operation", "delete").tag("outcome", "error")
                            .timer().count()).isEqualTo(1);
                });
    }

    @Test
    void countDeUmListComTamanhoConhecidoEncerraAObservacao() {
        ObjectStorage adapter = org.mockito.Mockito.mock(ObjectStorage.class);
        org.mockito.Mockito.when(adapter.list("")).thenAnswer(inv -> List.of(
                new ObjectSummary("a", 1, "v", null), new ObjectSummary("b", 1, "v", null)).stream());

        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, () -> adapter)
                .run(context -> {
                    // Sem close: um stream SIZED responderia count() sem percorrer, e a observação ficaria aberta.
                    assertThat(context.getBean(ObjectStorage.class).list("").count()).isEqualTo(2);

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("storage.operations").tag("operation", "list").timer().count())
                            .isEqualTo(1);
                });
    }

    @Test
    void semObservationRegistryNaoDecora() {
        runner.withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> assertThat(context.getBean(ObjectStorage.class))
                        .isNotInstanceOf(ObjectStorageMetrics.class));
    }

    @Test
    void semMicrometerNoClasspathNaoAtiva() {
        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .withClassLoader(new FilteredClassLoader(ObservationRegistry.class))
                .run(context -> assertThat(context).doesNotHaveBean(StorageMetricsAutoConfiguration.class));
    }

    @Test
    void presignGetComNomeDeDownloadDelegaEMede() {
        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> {
                    context.getBean(ObjectStorage.class).presignGet("k", java.time.Duration.ofMinutes(1), "r.csv");

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("storage.operations").tag("operation", "presignGet")
                            .tag("outcome", "success").timer().count()).isEqualTo(1);
                });
    }

    @Test
    void objetoInexistenteRegistraOutcomeNotFoundSemErro() {
        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> {
                    assertThatThrownBy(() -> context.getBean(ObjectStorage.class).open("inexistente"))
                            .isInstanceOf(ObjectNotFoundException.class);

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("storage.operations").tag("operation", "open").tag("outcome", "not_found")
                            .tag("error", "none").timer().count()).isEqualTo(1);
                });
    }

    @Test
    void openMedeAteOFimDaLeituraUmaUnicaVez() {
        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> {
                    ObjectStorage storage = context.getBean(ObjectStorage.class);
                    storage.put("k", "v".getBytes(), com.example.storage.PutOptions.of("text/plain"));
                    MeterRegistry registry = context.getBean(MeterRegistry.class);

                    try (InputStream in = storage.open("k")) {
                        assertThat(registry.find("storage.operations").tag("operation", "open").timer()).isNull();
                        assertThat(in.readAllBytes()).isEqualTo("v".getBytes());
                    }

                    assertThat(registry.get("storage.operations").tag("operation", "open").tag("outcome", "success")
                            .timer().count()).isEqualTo(1);
                });
    }

    @Test
    void listMedeAteEsgotarOStreamSemPrecisarDeClose() {
        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> {
                    ObjectStorage storage = context.getBean(ObjectStorage.class);
                    storage.put("a", "1".getBytes(), com.example.storage.PutOptions.of("text/plain"));
                    storage.put("b", "2".getBytes(), com.example.storage.PutOptions.of("text/plain"));

                    assertThat(storage.list("").toList()).hasSize(2);

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("storage.operations").tag("operation", "list").tag("outcome", "success")
                            .timer().count()).isEqualTo(1);
                });
    }

    @Test
    void excecaoDeQuemConsomeOListNaoViraErroDoStorage() {
        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> {
                    ObjectStorage storage = context.getBean(ObjectStorage.class);
                    storage.put("a", "1".getBytes(), com.example.storage.PutOptions.of("text/plain"));

                    try (Stream<ObjectSummary> objects = storage.list("")) {
                        assertThatThrownBy(() -> objects.forEach(o -> {
                            throw new IllegalStateException("bug de quem consome");
                        })).isInstanceOf(IllegalStateException.class);
                    }

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("storage.operations").tag("operation", "list").tag("outcome", "success")
                            .timer().count()).isEqualTo(1);
                });
    }

    @Test
    void healthCheckNaoGeraObservacao() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class,
                        StorageMetricsAutoConfiguration.class, StorageHealthAutoConfiguration.class))
                .withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> {
                    context.getBean(ObjectStorageHealthIndicator.class).health();

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find("storage.operations").timers()).isEmpty();
                });
    }

    @Test
    void headDeObjetoInexistenteRegistraNotFoundEExistenteSuccess() {
        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> {
                    ObjectStorage storage = context.getBean(ObjectStorage.class);
                    storage.put("k", "v".getBytes(), com.example.storage.PutOptions.of("text/plain"));

                    assertThat(storage.head("k")).isPresent();
                    assertThat(storage.head("inexistente")).isEmpty();

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("storage.operations").tag("operation", "head").tag("outcome", "success")
                            .tag("error", "none").timer().count()).isEqualTo(1);
                    assertThat(registry.get("storage.operations").tag("operation", "head").tag("outcome", "not_found")
                            .tag("error", "none").timer().count()).isEqualTo(1);
                });
    }

    @Test
    void cadaExcecaoTipadaTemSeuOutcome() {
        Map<RuntimeException, String> esperado = new java.util.LinkedHashMap<>();
        esperado.put(new ObjectRetainedException("r", null), "retained");
        esperado.put(new AccessDeniedException("a", null), "access_denied");
        esperado.put(new PreconditionFailedException("p", null), "precondition_failed");
        esperado.put(new ObjectTooLargeException("t", 1), "too_large");
        esperado.put(new RangeNotSatisfiableException("r", null), "range_not_satisfiable");
        esperado.put(new StorageException("e", null), "error");
        esperado.put(new UnsupportedOperationException("sem retenção"), "unsupported");

        esperado.forEach((exception, outcome) -> {
            ObjectStorage broken = org.mockito.Mockito.mock(ObjectStorage.class);
            org.mockito.Mockito.doThrow(exception).when(broken).delete("k");

            runner.withUserConfiguration(Observations.class)
                    .withBean(ObjectStorage.class, () -> broken)
                    .run(context -> {
                        assertThatThrownBy(() -> context.getBean(ObjectStorage.class).delete("k")).isSameAs(exception);

                        // unsupported, como not_found, não é falha do storage: o span não vira erro.
                        String error = "unsupported".equals(outcome) ? "none" : exception.getClass().getSimpleName();
                        MeterRegistry registry = context.getBean(MeterRegistry.class);
                        assertThat(registry.get("storage.operations").tag("operation", "delete")
                                .tag("outcome", outcome).tag("error", error).timer().count()).as(outcome).isEqualTo(1);
                    });
        });
    }

    @Test
    void putERecordBytesTransferidosEmStorageBytes() {
        runner.withUserConfiguration(Observations.class)
                .withBean("reportsStorage", ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> {
                    ObjectStorage storage = context.getBean(ObjectStorage.class);
                    storage.put("k", "12345".getBytes(), com.example.storage.PutOptions.of("text/plain"));
                    try (InputStream in = storage.open("k")) {
                        in.readAllBytes();
                    }
                    try (ObjectContent content = storage.read("k", ByteRange.all())) {
                        content.stream().readAllBytes();
                    }

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(bytes(registry, "put", "upload")).isEqualTo(5);
                    assertThat(bytes(registry, "open", "download")).isEqualTo(5);
                    assertThat(bytes(registry, "read", "download")).isEqualTo(5);
                    assertThat(registry.get("storage.bytes").tag("storage", "reportsStorage")
                            .tag("operation", "put").summary().getId().getBaseUnit()).isEqualTo("bytes");
                });
    }

    @Test
    void leituraFechadaAntesDoFimContaSoOsBytesLidos() {
        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> {
                    ObjectStorage storage = context.getBean(ObjectStorage.class);
                    storage.put("k", "0123456789".getBytes(), com.example.storage.PutOptions.of("text/plain"));

                    try (InputStream in = storage.open("k")) {
                        assertThat(in.readNBytes(3)).hasSize(3);
                    }

                    assertThat(bytes(context.getBean(MeterRegistry.class), "open", "download")).isEqualTo(3);
                });
    }

    @Test
    void putComFalhaENaoRegistraBytes() {
        ObjectStorage broken = org.mockito.Mockito.mock(ObjectStorage.class);
        org.mockito.Mockito.when(broken.put(org.mockito.ArgumentMatchers.eq("k"),
                        org.mockito.ArgumentMatchers.any(InputStream.class), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new StorageException("indisponível", null));

        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, () -> broken)
                .run(context -> {
                    assertThatThrownBy(() -> context.getBean(ObjectStorage.class).put("k", new byte[4],
                            com.example.storage.PutOptions.of("text/plain"))).isInstanceOf(StorageException.class);

                    assertThat(context.getBean(MeterRegistry.class).find("storage.bytes").summaries()).isEmpty();
                });
    }

    @Test
    void semMicrometerCoreObservaMasNaoRegistraBytes() {
        runner.withUserConfiguration(OnlyObservations.class)
                .withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                .run(context -> {
                    ObjectStorage storage = context.getBean(ObjectStorage.class);
                    assertThat(storage).isInstanceOf(ObjectStorageMetrics.class);
                    storage.put("k", "v".getBytes(), com.example.storage.PutOptions.of("text/plain"));
                    assertThat(context).doesNotHaveBean(ExtraMeters.class);
                });
    }

    @Test
    void deleteAllContaChavesOkEFalhasEMarcaPartial() {
        ObjectStorage adapter = org.mockito.Mockito.mock(ObjectStorage.class);
        org.mockito.Mockito.when(adapter.deleteAll(List.of("a", "b", "c"))).thenReturn(
                new DeleteResult(Map.of("b", new StorageException("negado", null))));
        org.mockito.Mockito.when(adapter.deleteAll(List.of("d"))).thenReturn(new DeleteResult(Map.of()));

        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, () -> adapter)
                .run(context -> {
                    ObjectStorage storage = context.getBean(ObjectStorage.class);
                    storage.deleteAll(List.of("a", "b", "c"));
                    storage.deleteAll(List.of("d"));

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("storage.deleteAll.keys").tag("result", "ok").counter().count())
                            .isEqualTo(3);
                    assertThat(registry.get("storage.deleteAll.keys").tag("result", "failed").counter().count())
                            .isEqualTo(1);
                    assertThat(registry.get("storage.operations").tag("operation", "deleteAll")
                            .tag("outcome", "partial").tag("error", "none").timer().count()).isEqualTo(1);
                    assertThat(registry.get("storage.operations").tag("operation", "deleteAll")
                            .tag("outcome", "success").timer().count()).isEqualTo(1);
                });
    }

    @Test
    void deleteAllTotalmenteFalhoContaSoFailed() {
        ObjectStorage adapter = org.mockito.Mockito.mock(ObjectStorage.class);
        org.mockito.Mockito.when(adapter.deleteAll(List.of("a"))).thenReturn(
                new DeleteResult(Map.of("a", new StorageException("negado", null))));

        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, () -> adapter)
                .run(context -> {
                    context.getBean(ObjectStorage.class).deleteAll(List.of("a"));

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find("storage.deleteAll.keys").tag("result", "ok").counter()).isNull();
                    assertThat(registry.get("storage.deleteAll.keys").tag("result", "failed").counter().count())
                            .isEqualTo(1);
                });
    }

    @Test
    void streamAbertoENaoFechadoFicaVisivelEmOperationsActive() throws Exception {
        runner.withUserConfiguration(Observations.class)
                .withBean("reportsStorage", ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> {
                    ObjectStorage storage = context.getBean(ObjectStorage.class);
                    storage.put("k", "0123456789".getBytes(), com.example.storage.PutOptions.of("text/plain"));
                    MeterRegistry registry = context.getBean(MeterRegistry.class);

                    InputStream in = storage.open("k");
                    in.read();
                    // O timer só aparece no encerramento; o LongTaskTimer, enquanto a observação está aberta.
                    assertThat(registry.get("storage.operations.active").tag("operation", "open")
                            .tag("storage", "reportsStorage").longTaskTimer().activeTasks()).isEqualTo(1);

                    in.close();
                    assertThat(registry.get("storage.operations.active").tag("operation", "open")
                            .longTaskTimer().activeTasks()).isZero();
                });
    }

    @Test
    void ttfbSeparaAAberturaDoConsumoLento() {
        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> {
                    ObjectStorage storage = context.getBean(ObjectStorage.class);
                    storage.put("k", "v".getBytes(), com.example.storage.PutOptions.of("text/plain"));

                    try (InputStream in = storage.open("k")) {
                        Thread.sleep(100);   // cliente lento
                        in.readAllBytes();
                    }
                    assertThatThrownBy(() -> storage.open("inexistente")).isInstanceOf(ObjectNotFoundException.class);

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    var ttfb = registry.get("storage.operations.ttfb").tag("operation", "open").timer();
                    var total = registry.get("storage.operations").tag("operation", "open")
                            .tag("outcome", "success").timer();
                    assertThat(ttfb.count()).as("só aberturas bem-sucedidas").isEqualTo(1);
                    assertThat(total.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(100);
                    assertThat(ttfb.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isLessThan(50);
                });
    }

    @Test
    void multipartConcluidoMedeUploadPartesEBytes() {
        runner.withUserConfiguration(Observations.class)
                .withBean("reportsStorage", ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> {
                    ObjectStorage storage = context.getBean(ObjectStorage.class);
                    MultipartSession session = storage.initiateMultipart("k", ObjectMetadata.of("text/plain"));
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("storage.multipart.active").longTaskTimer().activeTasks()).isEqualTo(1);

                    int min = com.example.storage.MultipartConfig.MIN_PART_SIZE;   // o in-memory exige, salvo na última
                    UploadedPart one = session.uploadPart(1, new byte[min], min);
                    UploadedPart two = session.uploadPart(2, new byte[3], 3);
                    session.complete(List.of(one, two));

                    assertThat(registry.get("storage.multipart.active").longTaskTimer().activeTasks()).isZero();
                    assertThat(registry.get("storage.multipart").tag("storage", "reportsStorage")
                            .tag("outcome", "completed").timer().count()).isEqualTo(1);
                    assertThat(registry.get("storage.multipart.parts").tag("outcome", "success").timer().count())
                            .isEqualTo(2);
                    assertThat(bytes(registry, "uploadPart", "upload")).isEqualTo(min + 3);
                    assertThat(registry.get("storage.operations").tag("operation", "multipartComplete")
                            .tag("outcome", "success").timer().count()).isEqualTo(1);
                    assertThat(registry.get("storage.operations").tag("operation", "initiateMultipart")
                            .timer().count()).isEqualTo(1);
                });
    }

    @Test
    void multipartAbortadoRegistraAborted() {
        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> {
                    MultipartSession session = context.getBean(ObjectStorage.class)
                            .initiateMultipart("k", ObjectMetadata.of("text/plain"));
                    session.uploadPart(1, new byte[2], 2);
                    session.abort();
                    session.abort();   // idempotente: não conta duas vezes

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("storage.multipart").tag("outcome", "aborted").timer().count())
                            .isEqualTo(1);
                    assertThat(registry.get("storage.multipart.active").longTaskTimer().activeTasks()).isZero();
                    assertThat(registry.get("storage.operations").tag("operation", "multipartAbort")
                            .timer().count()).isEqualTo(2);
                });
    }

    @Test
    void completeComFalhaERegistraErroEOAbortSeguinteNaoTrocaODesfecho() {
        MultipartSession broken = org.mockito.Mockito.mock(MultipartSession.class);
        org.mockito.Mockito.doThrow(new PreconditionFailedException("etag", null)).when(broken).complete(List.of());
        org.mockito.Mockito.when(broken.uploadPart(org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new StorageException("500", null));
        ObjectStorage adapter = org.mockito.Mockito.mock(ObjectStorage.class);
        org.mockito.Mockito.when(adapter.initiateMultipart("k", ObjectMetadata.empty())).thenReturn(broken);

        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, () -> adapter)
                .run(context -> {
                    MultipartSession session = context.getBean(ObjectStorage.class)
                            .initiateMultipart("k", ObjectMetadata.empty());
                    assertThatThrownBy(() -> session.uploadPart(1, new byte[1], 1)).isInstanceOf(StorageException.class);
                    assertThatThrownBy(() -> session.complete(List.of())).isInstanceOf(PreconditionFailedException.class);
                    session.abort();

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("storage.multipart.parts").tag("outcome", "error").timer().count())
                            .isEqualTo(1);
                    assertThat(registry.get("storage.multipart").tag("outcome", "precondition_failed")
                            .timer().count()).isEqualTo(1);
                    assertThat(registry.find("storage.multipart").tag("outcome", "aborted").timer()).isNull();
                    assertThat(registry.find("storage.bytes").summaries()).isEmpty();
                });
    }

    @Test
    void multipartNuncaConcluidoFicaEmActive() {
        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> {
                    context.getBean(ObjectStorage.class).initiateMultipart("k", ObjectMetadata.of("text/plain"));

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("storage.multipart.active").longTaskTimer().activeTasks()).isEqualTo(1);
                    assertThat(registry.find("storage.multipart").timer()).isNull();
                });
    }

    @Test
    void todoMeterTrazATagProvider() {
        runner.withUserConfiguration(Observations.class)
                .withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> {
                    ObjectStorage storage = context.getBean(ObjectStorage.class);
                    storage.put("k", "v".getBytes(), com.example.storage.PutOptions.of("text/plain"));
                    storage.open("k").close();
                    storage.deleteAll(List.of("k"));
                    storage.initiateMultipart("m", ObjectMetadata.of("text/plain")).uploadPart(1, new byte[1], 1);

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    // Um provider fora da lista (InMemory) é "unknown".
                    assertThat(registry.getMeters()).isNotEmpty().allSatisfy(meter -> assertThat(
                            meter.getId().getTag("provider")).as(meter.getId().getName()).isEqualTo("unknown"));
                });
    }

    @Test
    void providerVemDaClasseDoAdapter() {
        class S3ObjectStorage { }
        class FileSystemObjectStorage { }
        assertThat(StorageId.of("a", new S3ObjectStorage()).provider()).isEqualTo("s3");
        assertThat(StorageId.of("a", new FileSystemObjectStorage()).provider()).isEqualTo("filesystem");
        assertThat(StorageId.of("a", new Object()).provider()).isEqualTo("unknown");
    }

    private static double bytes(MeterRegistry registry, String operation, String direction) {
        return registry.get("storage.bytes").tag("operation", operation).tag("direction", direction)
                .summary().totalAmount();
    }

    @Configuration(proxyBeanMethods = false)
    static class OnlyObservations {

        @Bean
        ObservationRegistry observationRegistry() {
            return ObservationRegistry.create();
        }
    }

    /** O que o Actuator monta: as observações viram timers no {@link MeterRegistry}. */
    @Configuration(proxyBeanMethods = false)
    static class Observations {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ObservationRegistry observationRegistry(MeterRegistry meters) {
            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
            return registry;
        }
    }
}
