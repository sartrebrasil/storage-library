package com.example.storage.spring.exemplos;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Como a aplicação declara o {@link BlobServiceClient} que o starter usa com {@code storage.provider=azure} (design de
 * 2026-11-15). O starter deriva um {@code BlobContainerClient} por container a partir dele; um {@code BlobContainerClient}
 * declarado pela aplicação não é mais usado.
 *
 * <p>O tipo da SAS não segue mais a autenticação: {@code storage.azure.sas} diz {@code shared-key} (chave da conta, da
 * connection string) ou {@code user-delegation} (Entra ID, com {@code endpoint}).</p>
 */
public final class AzureClientsExample {

    private AzureClientsExample() {
    }

    /** Chave da conta: vale {@code storage.azure.sas=shared-key}. Inclui o Azurite ({@code UseDevelopmentStorage=true}). */
    public static BlobServiceClient blobServiceClientFromConnectionString(String connectionString) {
        return new BlobServiceClientBuilder().connectionString(connectionString).buildClient();
    }

    /** Entra ID: vale {@code storage.azure.sas=user-delegation}. Exige {@code com.azure:azure-identity} para o credential. */
    public static BlobServiceClient blobServiceClientFromEndpoint(String endpoint, TokenCredential credential) {
        return new BlobServiceClientBuilder().endpoint(endpoint).credential(credential).buildClient();
    }

    /** Produção: identidade gerenciada ou o login do ambiente, pela {@code DefaultAzureCredential}. */
    @Configuration(proxyBeanMethods = false)
    public static class Producao {

        @Bean
        BlobServiceClient blobServiceClient(@Value("${azure.storage.endpoint}") String endpoint) {
            return blobServiceClientFromEndpoint(endpoint, new DefaultAzureCredentialBuilder().build());
        }
    }

    /** Emulador: Azurite, com a chave da conta de desenvolvimento. */
    @Configuration(proxyBeanMethods = false)
    public static class Emulador {

        @Bean
        BlobServiceClient blobServiceClient() {
            return blobServiceClientFromConnectionString("UseDevelopmentStorage=true");
        }
    }
}
