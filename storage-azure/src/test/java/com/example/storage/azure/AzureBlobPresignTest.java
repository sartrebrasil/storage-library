package com.example.storage.azure;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.example.storage.ObjectMetadata;
import com.example.storage.PresignedRequest;
import com.example.storage.PutOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AzureBlobPresignTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private static BlobContainerClient sharedKeyContainer() {
        // SAS com chave da conta é calculado localmente: cliente real, sem rede.
        String accountKey = Base64.getEncoder().encodeToString(new byte[64]);
        return new BlobContainerClientBuilder()
                .endpoint("https://conta.blob.core.windows.net")
                .containerName("reports")
                .credential(new StorageSharedKeyCredential("conta", accountKey))
                .buildClient();
    }

    @Test
    void sharedKeyAssinaLocalmenteComLeituraEExpiracao() {
        URI url = new AzureBlobObjectStorage(sharedKeyContainer(), false, clock)
                .presignGet("relatorios/r.csv.gz", Duration.ofMinutes(15));

        assertEquals("https://conta.blob.core.windows.net/reports/relatorios/r.csv.gz",
                url.getScheme() + "://" + url.getHost() + url.getPath());
        assertTrue(url.getQuery().contains("sp=r"), url::toString);
        assertTrue(url.getQuery().contains("se=2026-09-26T12:15:00Z"), url::toString);
        assertTrue(url.getQuery().contains("sig="), url::toString);
    }

    @Test
    void presignPutDaPermissaoDeEscritaEDevolveCabecalhos() {
        var metadata = new ObjectMetadata("text/csv", null, Map.of("tenant", "t1"));

        PresignedRequest request = new AzureBlobObjectStorage(sharedKeyContainer(), false, clock)
                .presignPut("r.csv", Duration.ofMinutes(15), PutOptions.of(metadata));

        assertEquals("PUT", request.method());
        assertTrue(request.url().getQuery().contains("sp=cw"), request.url()::toString);
        assertEquals(Map.of("x-ms-blob-type", "BlockBlob", "x-ms-blob-content-type", "text/csv",
                "x-ms-meta-tenant", "t1"), request.headers());
    }

    @Test
    void presignPutIfNotExistsSoPermiteCriar() {
        PresignedRequest request = new AzureBlobObjectStorage(sharedKeyContainer(), false, clock)
                .presignPut("r.csv", Duration.ofMinutes(15), PutOptions.of("text/csv").ifNotExists());

        // "c" sem "w": o Azure recusa sobrescrever um blob existente.
        assertTrue(request.url().getQuery().contains("sp=c&"), request.url()::toString);
        assertEquals("*", request.headers().get("If-None-Match"));
    }

    @Test
    void userDelegationBuscaChaveEAssinaComEla() {
        BlobContainerClient container = mock(BlobContainerClient.class);
        BlobServiceClient service = mock(BlobServiceClient.class);
        BlobClient blob = mock(BlobClient.class);
        UserDelegationKey key = new UserDelegationKey();
        when(container.getServiceClient()).thenReturn(service);
        when(container.getBlobClient("r.csv")).thenReturn(blob);
        when(service.getUserDelegationKey(any(), any())).thenReturn(key);
        when(blob.getBlobUrl()).thenReturn("https://conta.blob.core.windows.net/reports/r.csv");
        when(blob.generateUserDelegationSas(any(), eq(key))).thenReturn("sv=x&sp=r&sig=abc");

        URI url = new AzureBlobObjectStorage(container, true, clock).presignGet("r.csv", Duration.ofHours(1));

        OffsetDateTime now = NOW.atOffset(ZoneOffset.UTC);
        verify(service).getUserDelegationKey(now.minusMinutes(5), now.plusHours(1));
        ArgumentCaptor<BlobServiceSasSignatureValues> values =
                ArgumentCaptor.forClass(BlobServiceSasSignatureValues.class);
        verify(blob).generateUserDelegationSas(values.capture(), eq(key));
        assertEquals("r", values.getValue().getPermissions());
        assertEquals(now.plusHours(1), values.getValue().getExpiryTime());
        verify(blob, never()).generateSas(any());
        assertEquals(URI.create("https://conta.blob.core.windows.net/reports/r.csv?sv=x&sp=r&sig=abc"), url);
    }

    @Test
    void presignGetComNomeDeDownloadAssinaRscd() {
        URI url = new AzureBlobObjectStorage(sharedKeyContainer(), false, clock)
                .presignGet("relatorios/r-a1.csv", Duration.ofMinutes(15), "report-1.csv");

        assertTrue(url.getQuery().contains("rscd=attachment; filename=\"report-1.csv\""), url::toString);
        assertTrue(url.getQuery().contains("sig="), url::toString);
    }
}
