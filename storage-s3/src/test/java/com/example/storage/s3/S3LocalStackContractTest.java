package com.example.storage.s3;

import com.example.storage.ObjectStorage;
import com.example.storage.testkit.ObjectStorageContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Contrato contra o LocalStack 3.0, a versão community que consumidores usam em testes de
 * integração (as mais novas exigem token de licença). Pulado quando não há Docker.
 *
 * <p>Roda com {@link S3ObjectStorage.Checksum#NONE} e o cliente em {@code WHEN_REQUIRED}: com
 * checksum CRC32, PutObject e UploadPart de corpo vazio falham no LocalStack 3.0 com
 * "'NoneType' object has no attribute 'to_bytes'" (500), com qualquer tipo de corpo.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class S3LocalStackContractTest extends ObjectStorageContract {

    private static final String BUCKET = "contract";

    @Container
    private static final GenericContainer<?> LOCALSTACK = new GenericContainer<>("localstack/localstack:3.0")
            .withEnv("SERVICES", "s3")
            .withExposedPorts(4566)
            .waitingFor(Wait.forLogMessage(".*Ready\\.\\n", 1));

    private static S3Client s3;
    private static S3Presigner presigner;
    private static S3ObjectStorage storage;

    @BeforeAll
    static void setUp() {
        URI endpoint = URI.create("http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566));
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        s3 = S3Client.builder().region(Region.US_EAST_1).endpointOverride(endpoint).forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .credentialsProvider(credentials).build();
        presigner = S3Presigner.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(credentials).build();
        s3.createBucket(b -> b.bucket(BUCKET));
        storage = new S3ObjectStorage(s3, presigner, BUCKET, S3ObjectStorage.Checksum.NONE);
    }

    @AfterAll
    static void tearDown() {
        presigner.close();
        s3.close();
    }

    @Override
    protected ObjectStorage storage() {
        return storage;
    }

    /** O S3 real só passou a aceitar escrita condicional em 2024; o LocalStack 3.0 ignora os cabeçalhos. */
    @Override
    protected boolean supportsConditionalWrites() {
        return false;
    }

    @Override
    protected ObjectStorage storageWithMissingBucket() {
        return new S3ObjectStorage(s3, presigner, "nao-existe", S3ObjectStorage.Checksum.NONE);
    }
}
