package com.example.storage.spring;

import com.example.storage.ObjectStorage;
import com.example.storage.StorageException;
import com.example.storage.memory.InMemoryObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class StorageHealthAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StorageAutoConfiguration.class, StorageHealthAutoConfiguration.class));

    @Test
    void storageAcessivelFicaUp() {
        runner.withBean(ObjectStorage.class, InMemoryObjectStorage::new).run(context -> {
            assertThat(context).hasBean("storageHealthIndicator");
            assertThat(context.getBean(ObjectStorageHealthIndicator.class).health().getStatus()).isEqualTo(Status.UP);
        });
    }

    @Test
    void falhaNoCheckAccessFicaDown() {
        ObjectStorage broken = mock(ObjectStorage.class);
        doThrow(new StorageException("Bucket não existe: s3://reports", null)).when(broken).checkAccess();

        runner.withBean(ObjectStorage.class, () -> broken).run(context -> {
            var health = context.getBean(ObjectStorageHealthIndicator.class).health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails().get("error").toString()).contains("Bucket não existe");
        });
    }

    @Test
    void variosBucketsViramIndicadorCompostoPorBean() {
        ObjectStorage broken = mock(ObjectStorage.class);
        doThrow(new StorageException("Bucket não existe: s3://artifacts", null)).when(broken).checkAccess();

        runner.withBean("reportsStorage", ObjectStorage.class, InMemoryObjectStorage::new)
                .withBean("artifactsStorage", ObjectStorage.class, () -> broken)
                .run(context -> {
                    var composite = context.getBean("storageHealthIndicator", CompositeHealthContributor.class);
                    assertThat(((HealthIndicator) composite.getContributor("reportsStorage")).health().getStatus())
                            .isEqualTo(Status.UP);
                    assertThat(((HealthIndicator) composite.getContributor("artifactsStorage")).health().getStatus())
                            .isEqualTo(Status.DOWN);
                });
    }

    @Test
    void storageBucketsEntramNoIndicadorComposto() {
        runner.withPropertyValues("storage.provider=s3", "storage.s3.region=us-east-1",
                        "storage.s3.access-key=a", "storage.s3.secret-key=b",
                        "storage.buckets.reports=r", "storage.buckets.artifacts=a")
                .run(context -> {
                    var composite = context.getBean("storageHealthIndicator", CompositeHealthContributor.class);
                    assertThat(composite.getContributor("reportsObjectStorage")).isNotNull();
                    assertThat(composite.getContributor("artifactsObjectStorage")).isNotNull();
                });
    }

    @Test
    void semObjectStorageNaoCriaIndicator() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ObjectStorageHealthIndicator.class));
    }

    @Test
    void semActuatorNaoCriaIndicator() {
        runner.withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .withClassLoader(new FilteredClassLoader(HealthIndicator.class))
                .run(context -> assertThat(context).doesNotHaveBean(ObjectStorageHealthIndicator.class));
    }

    @Test
    void propriedadeDesligaOIndicator() {
        runner.withBean(ObjectStorage.class, InMemoryObjectStorage::new)
                .withPropertyValues("management.health.storage.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ObjectStorageHealthIndicator.class));
    }
}
