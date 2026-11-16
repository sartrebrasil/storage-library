package com.example.storage.spring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** A regra do bean raiz de cada provedor (design de 2026-11-15): o tipo, com {@code @Primary} para desempatar. */
class StorageClientBeansTest {

    private static void register(DefaultListableBeanFactory factory, String name, S3Client bean, boolean primary) {
        RootBeanDefinition definition = new RootBeanDefinition(S3Client.class, () -> bean);
        definition.setPrimary(primary);
        factory.registerBeanDefinition(name, definition);
    }

    @Test
    void beanFaltandoFalhaComAMensagemDoProvedorEDoTipo() {
        assertThatThrownBy(() -> StorageClientBeans.require(new DefaultListableBeanFactory(), S3Client.class,
                StorageProperties.Provider.S3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("storage.provider=s3: declare um bean software.amazon.awssdk.services.s3.S3Client");
    }

    @Test
    void doisBeansSemPrimaryFalhamComAMensagemDoPrimary() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        register(factory, "um", mock(S3Client.class), false);
        register(factory, "dois", mock(S3Client.class), false);

        assertThatThrownBy(() -> StorageClientBeans.require(factory, S3Client.class, StorageProperties.Provider.S3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("storage.provider=s3: há mais de um bean software.amazon.awssdk.services.s3.S3Client; "
                        + "marque um com @Primary");
    }

    @Test
    void oPrimaryVence() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        S3Client primary = mock(S3Client.class);
        register(factory, "um", mock(S3Client.class), false);
        register(factory, "dois", primary, true);

        assertThat(StorageClientBeans.require(factory, S3Client.class, StorageProperties.Provider.S3))
                .isSameAs(primary);
    }

    @Test
    void umBeanSoEDevolvido() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        S3Client only = mock(S3Client.class);
        register(factory, "unico", only, false);

        assertThat(StorageClientBeans.require(factory, S3Client.class, StorageProperties.Provider.S3)).isSameAs(only);
    }
}
