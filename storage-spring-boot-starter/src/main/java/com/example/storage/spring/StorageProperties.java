package com.example.storage.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.nio.file.Path;
import java.util.Map;

/**
 * Configuração do {@code ObjectStorage}.
 *
 * <pre>
 * storage:
 *   provider: s3            # s3 | gcs | azure | oci
 *   bucket: reports         # no Azure, o container
 *   s3:
 *     region: sa-east-1
 *     endpoint: http://localhost:9000   # MinIO/LocalStack
 *     path-style: true
 * </pre>
 *
 * Vários buckets, no lugar de {@code bucket} (um bean {@code <nome>ObjectStorage} por entrada,
 * com qualifier {@code <nome>}):
 *
 * <pre>
 * storage:
 *   provider: s3
 *   buckets:
 *     reports: oobj-reports
 *     artifacts: oobj-artifacts
 * </pre>
 *
 * @param provider sem valor, nenhum {@code ObjectStorage} é criado
 */
@ConfigurationProperties("storage")
public record StorageProperties(Provider provider,
                                String bucket,
                                Map<String, String> buckets,
                                @DefaultValue S3 s3,
                                @DefaultValue Gcs gcs,
                                @DefaultValue Azure azure,
                                @DefaultValue Oci oci,
                                @DefaultValue Filesystem filesystem,
                                @DefaultValue Sftp sftp) {

    public enum Provider { S3, GCS, AZURE, OCI, FILESYSTEM, SFTP }

    public StorageProperties {
        buckets = buckets == null ? Map.of() : Map.copyOf(buckets);
        if (bucket != null && !bucket.isBlank() && !buckets.isEmpty()) {
            throw new IllegalArgumentException("Defina storage.bucket ou storage.buckets, não os dois");
        }
    }

    /**
     * @param region    sem valor, usa a cadeia padrão da AWS (AWS_REGION, profile...)
     * @param endpoint  endpoint compatível (MinIO, LocalStack); sem valor, AWS
     * @param accessKey com {@code secretKey}, credencial estática; sem valor, cadeia padrão da AWS
     * @param checksum  {@code none} para backends compatíveis sem checksum flexível (ex.: LocalStack 3.0)
     * @param asyncCredentialUpdate sem {@code accessKey}: renova credenciais temporárias (IRSA, STS,
     *                  metadata) numa thread de fundo, antes de expirarem, em vez de na requisição
     */
    public record S3(String region, URI endpoint, boolean pathStyle, String accessKey, String secretKey,
                     @DefaultValue("crc32") Checksum checksum,
                     @DefaultValue("true") boolean asyncCredentialUpdate) {

        public enum Checksum { CRC32, NONE }

        /** {@code ${VAR:}} chega como texto vazio: vazio conta como ausente. */
        public S3 {
            region = blankToNull(region);
            accessKey = blankToNull(accessKey);
            secretKey = blankToNull(secretKey);
            if ((accessKey == null) != (secretKey == null)) {
                throw new IllegalArgumentException(
                        "Defina storage.s3.access-key e storage.s3.secret-key juntas, ou nenhuma das duas");
            }
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }

        @Override
        public String toString() {
            return "S3[region=" + region + ", endpoint=" + endpoint + ", pathStyle=" + pathStyle
                    + ", accessKey=" + accessKey + ", secretKey=" + mask(secretKey) + ", checksum=" + checksum
                    + ", asyncCredentialUpdate=" + asyncCredentialUpdate + "]";
        }
    }

    /**
     * @param projectId sem valor, usa o do ambiente (Application Default Credentials)
     */
    public record Gcs(String projectId) {
    }

    /**
     * Com {@code connectionString}, usa a chave da conta (inclui Azurite). Sem ela, usa
     * {@code endpoint} com DefaultAzureCredential (requer {@code azure-identity}).
     *
     * @param endpoint ex.: {@code https://conta.blob.core.windows.net}
     */
    public record Azure(String connectionString, String endpoint) {

        /** A connection string carrega a {@code AccountKey}. */
        @Override
        public String toString() {
            return "Azure[connectionString=" + mask(connectionString) + ", endpoint=" + endpoint + "]";
        }
    }

    /**
     * @param configFile sem valor, {@code ~/.oci/config}
     * @param namespace  sem valor, é consultado na API na inicialização
     */
    public record Oci(String configFile, @DefaultValue("DEFAULT") String profile, String namespace) {
    }

    /**
     * Cada bucket ({@code storage.bucket}/{@code storage.buckets}) vira uma subpasta de
     * {@code root}, criada sob demanda no primeiro {@code put}.
     *
     * @param root diretório base no filesystem local; obrigatório com {@code provider: filesystem}
     */
    public record Filesystem(Path root) {
    }

    /**
     * Cada bucket vira uma subpasta de {@code root} no servidor. Autenticação por
     * {@code password} ou {@code privateKeyPath}, nunca as duas. Identidade do servidor por
     * {@code knownHosts}, senão {@code ~/.ssh/known_hosts}; sem nenhum dos dois, exige
     * {@code insecureTrustAllHosts} (não recomendado fora de dev/teste).
     *
     * @param host                  obrigatório com {@code provider: sftp}
     * @param port                  porta do servidor SSH
     * @param username              obrigatório
     * @param password              autenticação por senha
     * @param privateKeyPath        autenticação por chave privada (arquivo)
     * @param root                  caminho absoluto no servidor; obrigatório com {@code provider: sftp}
     * @param knownHosts            arquivo {@code known_hosts} para verificar a identidade do servidor
     * @param insecureTrustAllHosts pula a verificação de host key (exposto a MITM); só dev/teste
     * @param keepAlive             intervalo dos pacotes de keepalive, que mantêm a sessão ociosa viva em NAT e
     *                              firewall e fazem uma conexão morta ser detectada e refeita; {@code 0} desliga
     * @param maxChannels           canais SFTP abertos ao mesmo tempo, somando todos os buckets; abaixo do
     *                              {@code MaxSessions} do servidor (10 no OpenSSH)
     */
    public record Sftp(String host, @DefaultValue("22") int port, String username, String password,
                       String privateKeyPath, String root, String knownHosts,
                       @DefaultValue("false") boolean insecureTrustAllHosts,
                       @DefaultValue("30s") Duration keepAlive, @DefaultValue("8") int maxChannels) {

        /** {@code ${VAR:}} chega como texto vazio: vazio conta como ausente. */
        public Sftp {
            host = blankToNull(host);
            username = blankToNull(username);
            password = blankToNull(password);
            privateKeyPath = blankToNull(privateKeyPath);
            root = blankToNull(root);
            knownHosts = blankToNull(knownHosts);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }

        @Override
        public String toString() {
            return "Sftp[host=" + host + ", port=" + port + ", username=" + username + ", password=" + mask(password)
                    + ", privateKeyPath=" + privateKeyPath + ", root=" + root + ", knownHosts=" + knownHosts
                    + ", insecureTrustAllHosts=" + insecureTrustAllHosts + ", keepAlive=" + keepAlive
                    + ", maxChannels=" + maxChannels + "]";
        }
    }

    /** O {@code toString} de um record mostraria o segredo em qualquer log das properties. */
    private static String mask(String secret) {
        return secret == null ? "null" : "****";
    }
}
