package com.example.storage.gcs;

import com.example.storage.ObjectMetadata;
import com.example.storage.PresignedRequest;
import com.example.storage.PutOptions;
import com.example.storage.StorageException;
import com.google.auth.ServiceAccountSigner;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.MultipartUploadClient;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Com chave de service account a assinatura é local: SDK real, sem rede. */
class GcsPresignTest {

    private static GcsObjectStorage storage;

    @BeforeAll
    static void setUp() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        Storage real = StorageOptions.newBuilder()
                .setProjectId("projeto")
                .setCredentials(ServiceAccountCredentials.newBuilder()
                        .setClientEmail("reports@projeto.iam.gserviceaccount.com")
                        .setPrivateKey(generator.generateKeyPair().getPrivate())
                        .setPrivateKeyId("key-1")
                        .build())
                .build()
                .getService();
        storage = new GcsObjectStorage(real, mock(MultipartUploadClient.class), "bucket");
    }

    @Test
    void presignGetAssinaComV4EValidadeEmSegundos() {
        URI url = storage.presignGet("reports/r.csv", Duration.ofMinutes(15));

        assertEquals("storage.googleapis.com", url.getHost());
        assertEquals("/bucket/reports/r.csv", url.getPath());
        assertTrue(url.getQuery().contains("X-Goog-Algorithm=GOOG4-RSA-SHA256"), url::toString);
        assertTrue(url.getQuery().contains("X-Goog-Expires=900"), url::toString);
        assertTrue(url.getQuery().contains("X-Goog-Signature="), url::toString);
    }

    @Test
    void presignPutAssinaCabecalhosDeMetadataEPreCondicao() {
        var options = PutOptions.of(new ObjectMetadata("text/csv", null, Map.of("tenant", "t1"))).ifNotExists();

        PresignedRequest request = storage.presignPut("r.csv", Duration.ofMinutes(15), options);

        assertEquals("PUT", request.method());
        assertEquals(Map.of("Content-Type", "text/csv", "x-goog-meta-tenant", "t1",
                "x-goog-if-generation-match", "0"), request.headers());
        String signedHeaders = request.url().getQuery().replaceAll(".*X-Goog-SignedHeaders=([^&]*).*", "$1");
        assertEquals("content-type;host;x-goog-if-generation-match;x-goog-meta-tenant", signedHeaders);
    }

    @Test
    void falhaNaAssinaturaViraStorageException() {
        Storage mocked = mock(Storage.class);
        when(mocked.signUrl(any(), anyLong(), any(), any(Storage.SignUrlOption[].class)))
                .thenThrow(new ServiceAccountSigner.SigningException("sem permissão", null));

        assertThrows(StorageException.class, () -> new GcsObjectStorage(mocked, mock(MultipartUploadClient.class), "b")
                .presignGet("k", Duration.ofMinutes(1)));
    }

    @Test
    void presignGetComNomeDeDownloadAssinaResponseContentDisposition() {
        URI url = storage.presignGet("reports/r-a1.csv", Duration.ofMinutes(15), "report-1.csv");

        assertTrue(url.getRawQuery().contains("response-content-disposition=attachment%3B%20filename%3D%22report-1.csv%22"),
                url::toString);
        assertTrue(url.getQuery().contains("X-Goog-Signature="), url::toString);
    }
}
