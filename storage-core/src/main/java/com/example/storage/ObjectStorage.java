package com.example.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Operações portáveis sobre um bucket (ou container, no Azure). O núcleo da
 * aplicação só conhece esta interface.
 *
 * <p>Regras comuns a todas as implementações:</p>
 * <ul>
 *   <li>Objeto inexistente: {@link #head} devolve vazio; {@link #open} e {@link #copy}
 *       lançam {@link ObjectNotFoundException}; {@link #delete} não faz nada.</li>
 *   <li>Pré-condição não atendida lança {@link PreconditionFailedException}.</li>
 *   <li>Faixa fora do objeto lança {@link RangeNotSatisfiableException} na chamada a
 *       {@link #open} ou {@link #read}, nunca durante o consumo do stream. A faixa é
 *       cortada no fim do objeto, como no HTTP.</li>
 *   <li>Demais falhas lançam {@link StorageException}.</li>
 * </ul>
 */
public interface ObjectStorage {

    /**
     * Grava um objeto numa única requisição. Para objetos grandes ou de tamanho
     * desconhecido, use {@link MultipartOutputStream}.
     *
     * @return a nova versão do objeto
     */
    String put(String key, InputStream data, long length, PutOptions options);

    default String put(String key, byte[] data, PutOptions options) {
        return put(key, new ByteArrayInputStream(data), data.length, options);
    }

    /**
     * Inicia um upload em partes. O objeto só fica visível no storage
     * depois de {@link MultipartSession#complete}, então nunca existe
     * um objeto "pela metade" para quem for baixar.
     *
     * @throws IllegalArgumentException metadata fora das regras de escrita ({@link ObjectMetadata#requireWritable()})
     */
    MultipartSession initiateMultipart(String key, ObjectMetadata metadata);

    Optional<ObjectInfo> head(String key);

    /** Abre o conteúdo para leitura. O chamador deve fechar o stream. */
    InputStream open(String key, ByteRange range);

    default InputStream open(String key) {
        return open(key, ByteRange.all());
    }

    /**
     * Como {@link #open}, devolvendo também o tamanho do corpo, a faixa servida e o
     * tamanho total, para repassar a leitura numa resposta HTTP ({@code 200}/{@code 206}).
     * O chamador deve fechar o conteúdo.
     *
     * <p>O padrão faz {@link #head} e {@link #open}; os adapters obtêm tudo na própria
     * leitura quando o provedor permite.</p>
     *
     * @throws ObjectNotFoundException      o objeto não existe
     * @throws RangeNotSatisfiableException a faixa não existe no objeto
     */
    default ObjectContent read(String key, ByteRange range) {
        ObjectInfo info = head(key).orElseThrow(() -> new ObjectNotFoundException("Objeto não encontrado: " + key, null));
        ByteRange resolved = range.resolve(info.size());
        return new ObjectContent(open(key, resolved), resolved, info.size());
    }

    /**
     * Confirma que o bucket existe e que as credenciais alcançam. Base de health checks.
     * O padrão lê o primeiro item de {@link #list}; os adapters usam a chamada de bucket do
     * provedor.
     *
     * @throws StorageException bucket inexistente, sem permissão ou inacessível
     */
    default void checkAccess() {
        try (Stream<ObjectSummary> objects = list("")) {
            objects.findFirst();
        }
    }

    /**
     * Lista os objetos cujo nome começa com {@code prefix}, em ordem lexicográfica.
     * As páginas são buscadas sob demanda enquanto o stream é consumido.
     */
    Stream<ObjectSummary> list(String prefix);

    /**
     * Lista um nível da hierarquia, usando {@code /} como separador: os objetos logo abaixo
     * de {@code prefix} e as "pastas" ({@link CommonPrefix}) que agrupam os mais profundos,
     * cada pasta uma única vez, tudo em ordem lexicográfica. Para listar a raiz, use {@code ""};
     * para uma pasta, termine o prefixo em {@code /} (ex.: {@code "relatorios/2026/"}).
     *
     * <p>O separador é fixo porque {@code /} é o único que os quatro provedores aceitam
     * (a OCI não suporta outro).</p>
     */
    default Stream<ListEntry> listDirectory(String prefix) {
        // Padrão genérico: percorre todos os objetos abaixo do prefixo. Adapters usam o delimiter nativo.
        String[] lastFolder = {null};
        return list(prefix).mapMulti((object, sink) -> {
            int slash = object.key().indexOf('/', prefix.length());
            if (slash < 0) {
                sink.accept(object);
                return;
            }
            // list() é ordenado: objetos da mesma pasta são consecutivos, então basta comparar com a anterior.
            String folder = object.key().substring(0, slash + 1);
            if (!folder.equals(lastFolder[0])) {
                lastFolder[0] = folder;
                sink.accept(new CommonPrefix(folder));
            }
        });
    }

    /** Remove o objeto. Idempotente: não falha se ele não existir. */
    void delete(String key);

    /**
     * Remove vários objetos. Não é atômico: devolve as chaves que falharam.
     * Chaves inexistentes contam como sucesso.
     */
    default DeleteResult deleteAll(Collection<String> keys) {
        Map<String, StorageException> failures = new LinkedHashMap<>();
        for (String key : keys) {
            try {
                delete(key);
            } catch (StorageException e) {
                failures.put(key, e);
            }
        }
        return new DeleteResult(failures);
    }

    /**
     * Copia dentro do mesmo storage, preservando conteúdo e metadata. Bloqueia até a
     * cópia terminar (em alguns provedores ela é assíncrona e é acompanhada por polling).
     */
    void copy(String sourceKey, String targetKey);

    /** URL temporária para baixar o objeto sem credenciais. */
    URI presignGet(String key, Duration ttl);

    /**
     * Como {@link #presignGet(String, Duration)}, com a resposta pedindo para salvar como
     * {@code downloadName} ({@code Content-Disposition: attachment}), qualquer que seja a chave.
     *
     * @throws IllegalArgumentException      nome inválido (ver {@link ObjectMetadata#attachmentDisposition})
     * @throws UnsupportedOperationException provedor sem override de resposta na URL (OCI, filesystem,
     *                                       SFTP); grave o nome no upload com {@link ObjectMetadata#withDownloadName}
     */
    default URI presignGet(String key, Duration ttl, String downloadName) {
        ObjectMetadata.attachmentDisposition(downloadName);
        throw new UnsupportedOperationException(getClass().getSimpleName() + " não sobrescreve Content-Disposition");
    }

    /**
     * Requisição temporária para enviar o objeto sem credenciais (ex.: direto do navegador).
     * O cliente deve enviar {@link PresignedRequest#headers()}. Metadata e pré-condição de
     * {@code options} viram cabeçalhos; nem todo provedor os impõe na assinatura
     * (veja o javadoc de cada implementação).
     */
    PresignedRequest presignPut(String key, Duration ttl, PutOptions options);
}
