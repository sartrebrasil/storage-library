package com.example.storage.spring.exemplos;

import com.google.auth.Credentials;
import com.google.cloud.storage.HttpStorageOptions;
import com.google.cloud.storage.MultipartUploadClient;
import com.google.cloud.storage.MultipartUploadSettings;
import com.google.cloud.storage.Storage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Como a aplicação declara o {@link Storage} e o {@link MultipartUploadClient} que o starter usa com
 * {@code storage.provider=gcs} (design de 2026-11-15). O starter não cria credencial nem cliente.
 *
 * <p>Os dois saem das mesmas {@link HttpStorageOptions}: projeto e credenciais valem para o cliente e para o upload
 * multipart. Não há emulador nos testes do starter; o teste monta as peças com {@code NoCredentials}.</p>
 */
public final class GcsClientsExample {

    private GcsClientsExample() {
    }

    /**
     * @param credentialsOrNull sem valor, Application Default Credentials
     * @param projectIdOrNull   sem valor, o do ambiente
     */
    public static HttpStorageOptions options(Credentials credentialsOrNull, String projectIdOrNull) {
        HttpStorageOptions.Builder builder = HttpStorageOptions.newBuilder();
        if (credentialsOrNull != null) {
            builder.setCredentials(credentialsOrNull);
        }
        if (projectIdOrNull != null) {
            builder.setProjectId(projectIdOrNull);
        }
        return builder.build();
    }

    public static Storage storage(HttpStorageOptions options) {
        return options.getService();
    }

    public static MultipartUploadClient multipart(HttpStorageOptions options) {
        return MultipartUploadClient.create(MultipartUploadSettings.of(options));
    }

    /** Produção: Application Default Credentials (variável {@code GOOGLE_APPLICATION_CREDENTIALS}, Workload Identity). */
    @Configuration(proxyBeanMethods = false)
    public static class Producao {

        @Bean
        HttpStorageOptions httpStorageOptions() {
            return GcsClientsExample.options(null, null);
        }

        @Bean
        Storage storage(HttpStorageOptions options) {
            return GcsClientsExample.storage(options);
        }

        @Bean
        MultipartUploadClient multipartUploadClient(HttpStorageOptions options) {
            return GcsClientsExample.multipart(options);
        }
    }
}
