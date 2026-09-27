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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/** Contrato contra um MinIO real. Pulado quando não há Docker. */
@Testcontainers(disabledWithoutDocker = true)
class S3MinioContractTest extends ObjectStorageContract {

    private static final String BUCKET = "contract";

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>("minio/minio:latest")
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    private static S3Client s3;
    private static S3Presigner presigner;
    private static S3ObjectStorage storage;

    @BeforeAll
    static void setUp() {
        URI endpoint = URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("minioadmin", "minioadmin"));
        s3 = S3Client.builder().region(Region.US_EAST_1).endpointOverride(endpoint).forcePathStyle(true)
                .credentialsProvider(credentials).build();
        presigner = S3Presigner.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(credentials).build();
        s3.createBucket(b -> b.bucket(BUCKET));
        storage = new S3ObjectStorage(s3, presigner, BUCKET);
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
}
