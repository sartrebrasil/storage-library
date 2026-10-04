package com.example.storage.spring;

import com.example.storage.ByteRange;
import com.example.storage.CommonPrefix;
import com.example.storage.DeleteResult;
import com.example.storage.ObjectContent;
import com.example.storage.ObjectNotFoundException;
import com.example.storage.ObjectStorage;
import com.example.storage.ObjectSummary;
import com.example.storage.StorageException;
import com.example.storage.memory.InMemoryObjectStorage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
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
                    assertThat(registry.get("storage.operations").tag("operation", "head").tag("outcome", "success")
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
