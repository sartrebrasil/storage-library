package com.example.storage.spring.exemplos;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Como a aplicação declara o {@link S3Client} e o {@link S3Presigner} que o starter usa com {@code storage.provider=s3}
 * (design de 2026-11-15). O starter não cria credencial, cliente nem presigner.
 *
 * <p>O presigner não herda endpoint nem path-style do cliente: os dois precisam da mesma configuração, por isso os
 * métodos recebem os mesmos argumentos. Para backends sem checksum flexível (LocalStack 3.0), acrescente ao builder do
 * cliente {@code requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)} e
 * {@code responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)}, junto de {@code storage.s3.checksum=none}.</p>
 */
public final class S3ClientsExample {

    private S3ClientsExample() {
    }

    /** @param regionOrNull sem valor, a cadeia padrão do SDK ({@code AWS_REGION}) */
    public static S3Client s3Client(AwsCredentialsProvider credentials, Region regionOrNull, URI endpointOrNull,
                                    boolean pathStyle) {
        S3ClientBuilder builder = S3Client.builder().credentialsProvider(credentials).forcePathStyle(pathStyle);
        if (regionOrNull != null) {
            builder.region(regionOrNull);
        }
        if (endpointOrNull != null) {
            builder.endpointOverride(endpointOrNull);
        }
        return builder.build();
    }

    public static S3Presigner s3Presigner(AwsCredentialsProvider credentials, Region regionOrNull, URI endpointOrNull,
                                          boolean pathStyle) {
        S3Presigner.Builder builder = S3Presigner.builder().credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
        if (regionOrNull != null) {
            builder.region(regionOrNull);
        }
        if (endpointOrNull != null) {
            builder.endpointOverride(endpointOrNull);
        }
        return builder.build();
    }

    /**
     * Produção: credenciais da cadeia padrão da AWS (variáveis, IRSA, perfil, metadata), renovadas em segundo plano antes
     * de expirarem; um só provider para o cliente e o presigner, fechado pelo Spring. Região e endpoint, quando
     * necessários, vêm de {@code AWS_REGION} e {@code AWS_ENDPOINT_URL_S3}.
     */
    @Configuration(proxyBeanMethods = false)
    public static class Producao {

        @Bean
        AwsCredentialsProvider awsCredentialsProvider() {
            return DefaultCredentialsProvider.builder().asyncCredentialUpdateEnabled(true).build();
        }

        @Bean
        S3Client s3Client(AwsCredentialsProvider credentials) {
            return S3ClientsExample.s3Client(credentials, null, null, false);
        }

        @Bean
        S3Presigner s3Presigner(AwsCredentialsProvider credentials) {
            return S3ClientsExample.s3Presigner(credentials, null, null, false);
        }
    }

    /** Emulador: MinIO em {@code http://localhost:9000}, credencial estática e path-style. */
    @Configuration(proxyBeanMethods = false)
    public static class Emulador {

        private static final AwsCredentialsProvider CREDENTIALS =
                StaticCredentialsProvider.create(AwsBasicCredentials.create("minioadmin", "minioadmin"));
        private static final URI ENDPOINT = URI.create("http://localhost:9000");

        @Bean
        S3Client s3Client() {
            return S3ClientsExample.s3Client(CREDENTIALS, Region.US_EAST_1, ENDPOINT, true);
        }

        @Bean
        S3Presigner s3Presigner() {
            return S3ClientsExample.s3Presigner(CREDENTIALS, Region.US_EAST_1, ENDPOINT, true);
        }
    }
}
