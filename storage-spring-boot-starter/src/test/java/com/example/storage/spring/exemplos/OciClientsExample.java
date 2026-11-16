package com.example.storage.spring.exemplos;

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Como a aplicação declara o cliente do OCI Object Storage que o starter usa com {@code storage.provider=oci} (design de
 * 2026-11-15): o bean é um {@code com.oracle.bmc.objectstorage.ObjectStorage}, e o namespace continua em
 * {@code storage.oci.namespace} (sem ele, o starter o consulta na API). O starter não cria credencial nem cliente.
 */
public final class OciClientsExample {

    private OciClientsExample() {
    }

    /** O cliente fica com a autenticação e a região do {@code auth} (a de um {@code ConfigFileAuthenticationDetailsProvider} traz as duas). */
    public static ObjectStorageClient objectStorageClient(BasicAuthenticationDetailsProvider auth) {
        return ObjectStorageClient.builder().build(auth);
    }

    /** Produção: o arquivo {@code ~/.oci/config}, perfil {@code DEFAULT}. */
    @Configuration(proxyBeanMethods = false)
    public static class Producao {

        @Bean(destroyMethod = "close")
        ObjectStorageClient objectStorageClient() throws IOException {
            return OciClientsExample.objectStorageClient(new ConfigFileAuthenticationDetailsProvider("DEFAULT"));
        }
    }
}
