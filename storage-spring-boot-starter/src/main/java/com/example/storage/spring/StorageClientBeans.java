package com.example.storage.spring;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;

/**
 * Onde cada provedor procura o objeto raiz que a aplicação declara (cliente, presigner, conexão): o bean do tipo, ou o
 * {@code @Primary} quando há mais de um. O starter nunca cria nem fecha esses beans.
 */
final class StorageClientBeans {

    private StorageClientBeans() {
    }

    /** @throws IllegalStateException sem bean, ou com vários e nenhum {@code @Primary} */
    static <T> T require(BeanFactory beans, Class<T> type, StorageProperties.Provider provider) {
        String prefix = "storage.provider=" + provider.name().toLowerCase(java.util.Locale.ROOT) + ": ";
        T bean;
        try {
            bean = beans.getBeanProvider(type).getIfAvailable();
        } catch (NoUniqueBeanDefinitionException e) {
            throw new IllegalStateException(prefix + "há mais de um bean " + type.getName()
                    + "; marque um com @Primary", e);
        }
        if (bean == null) {
            throw new IllegalStateException(prefix + "declare um bean " + type.getName());
        }
        return bean;
    }
}
