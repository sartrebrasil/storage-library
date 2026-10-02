package com.example.storage.spring;

import com.example.storage.ObjectStorage;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Registra {@code storageHealthIndicator} (chave {@code storage} em {@code /actuator/health})
 * quando o Actuator está no classpath e existe um {@link ObjectStorage}, criado pelo starter
 * ou pela aplicação. Com vários {@code ObjectStorage}, verifica todos. Desligue com
 * {@code management.health.storage.enabled=false}.
 */
@AutoConfiguration(after = StorageAutoConfiguration.class)
@ConditionalOnClass({HealthIndicator.class, ConditionalOnEnabledHealthIndicator.class})
public class StorageHealthAutoConfiguration {

    // Classe interna: a anotação do Actuator só é lida depois que o @ConditionalOnClass passou.
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(ObjectStorage.class)
    @ConditionalOnEnabledHealthIndicator("storage")
    static class StorageHealthIndicatorConfiguration {

        /**
         * Um {@link ObjectStorage}: indicador simples. Vários (um por bucket): composto, com um
         * indicador por bean, cada um na chave do nome do bean ({@code storage/reportsStorage}).
         */
        @Bean
        @ConditionalOnMissingBean(name = "storageHealthIndicator")
        HealthContributor storageHealthIndicator(Map<String, ObjectStorage> storages) {
            return storages.size() == 1
                    ? indicator(storages.values().iterator().next())
                    : CompositeHealthContributor.fromMap(storages, StorageHealthIndicatorConfiguration::indicator);
        }

        // Sem o decorador de métricas: cada probe viraria um span e uma amostra em storage.operations.
        private static ObjectStorageHealthIndicator indicator(ObjectStorage storage) {
            return new ObjectStorageHealthIndicator(
                    storage instanceof DelegatingObjectStorage decorated ? decorated.delegate() : storage);
        }
    }
}
