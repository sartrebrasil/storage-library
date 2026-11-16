package com.example.storage.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.util.Map;

/**
 * Configuração do {@code ObjectStorage}. A conexão com o provedor (credenciais, região, endpoint, timeouts) é da
 * aplicação, que declara o objeto raiz como bean; aqui ficam só o provedor, os buckets e as opções dos adapters.
 *
 * <pre>
 * storage:
 *   provider: s3            # s3 | gcs | azure | oci | filesystem | sftp
 *   bucket: reports         # no Azure, o container
 *   s3:
 *     checksum: crc32       # crc32 | none
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
     * @param checksum {@code none} para backends compatíveis sem checksum flexível (ex.: LocalStack 3.0); é a opção do
     *                 adapter, e o cliente da aplicação precisa do mesmo ajuste
     */
    public record S3(@DefaultValue("crc32") Checksum checksum) {

        public enum Checksum { CRC32, NONE }
    }

    /**
     * @param sas tipo da SAS gerada pelo starter: {@code shared-key} (chave da conta) ou {@code user-delegation}
     *            (Entra ID). Obrigatório quando o starter cria o {@code ObjectStorage}; sem padrão
     */
    public record Azure(Sas sas) {

        public enum Sas { SHARED_KEY, USER_DELEGATION }
    }

    /**
     * @param namespace sem valor, é consultado na API na inicialização
     */
    public record Oci(String namespace) {
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
     * Cada bucket vira uma subpasta de {@code root} no servidor.
     *
     * @param root caminho absoluto no servidor; obrigatório com {@code provider: sftp}
     */
    public record Sftp(String root) {

        /** {@code ${VAR:}} chega como texto vazio: vazio conta como ausente. */
        public Sftp {
            root = root == null || root.isBlank() ? null : root;
        }
    }
}
