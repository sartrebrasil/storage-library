package com.example.storage.spring;

import com.example.storage.ObjectStorage;
import com.example.storage.StorageException;
import com.example.storage.memory.InMemoryObjectStorage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageMetricsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class, StorageMetricsAutoConfiguration.class));

    @Test
    void chamadaBemSucedidaRegistraOutcomeSuccess() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
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

        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(ObjectStorage.class, () -> broken)
                .run(context -> {
                    assertThatThrownBy(() -> context.getBean(ObjectStorage.class).delete("k"))
                            .isInstanceOf(StorageException.class);

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("storage.operations").tag("operation", "delete").tag("outcome", "error")
                            .timer().count()).isEqualTo(1);
                });
    }

    @Test
    void beanDecoradoUsaNomeDoBeanComoTagStorage() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean("reportsStorage", ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> {
                    context.getBean("reportsStorage", ObjectStorage.class).head("k");

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("storage.operations").tag("storage", "reportsStorage").timer().count())
                            .isEqualTo(1);
                });
    }

    @Test
    void semMeterRegistryNaoDecora() {
        runner.withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .run(context -> assertThat(context.getBean(ObjectStorage.class))
                        .isNotInstanceOf(ObjectStorageMetrics.class));
    }

    @Test
    void semMicrometerNoClasspathNaoAtiva() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                .run(context -> assertThat(context).doesNotHaveBean(StorageMetricsAutoConfiguration.class));
    }
}
