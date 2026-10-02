package com.example.storage.spring;

import com.example.storage.ObjectStorage;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Decora todo {@link ObjectStorage} com {@link ObjectStorageMetrics} quando há um
 * {@link ObservationRegistry} no contexto (o Actuator cria um; com um registro de métricas,
 * ex.: Prometheus, as observações viram timers, e com Micrometer Tracing, spans).
 * O nome do bean (ex.: {@code s3ObjectStorage}, {@code reportsObjectStorage}) vira a
 * tag {@code storage} das métricas em {@code storage.operations}.
 */
@AutoConfiguration(after = StorageAutoConfiguration.class)
@ConditionalOnClass(ObservationRegistry.class)
public class StorageMetricsAutoConfiguration {

    // Classe interna: a anotação do Micrometer só é lida depois que o @ConditionalOnClass passou.
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(ObservationRegistry.class)
    static class ObjectStorageMetricsConfiguration {

        @Bean
        static BeanPostProcessor objectStorageMetricsPostProcessor(ObservationRegistry registry) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    return bean instanceof ObjectStorage storage ? new ObjectStorageMetrics(storage, registry, beanName) : bean;
                }
            };
        }
    }
}
