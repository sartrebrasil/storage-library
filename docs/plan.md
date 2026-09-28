# Plano da library

## Decisões

- Coordenadas: `com.example:storage-*`, pacote base `com.example.storage`.
- Distribuição: somente `mvn install` local (sem repositório remoto).
- API síncrona (paralelismo com virtual threads); Java 21.
- Integração: Java puro + starter Spring Boot 3.5.x.
- Sem credenciais de CI para GCS e OCI: contrato desses adapters roda com
  mocks no build e contra bucket real só manualmente.

## API alvo (v1)

```java
public interface ObjectStorage {
    String put(String key, InputStream data, long length, PutOptions options);   // devolve a versão
    MultipartSession initiateMultipart(String key, ObjectMetadata metadata);
    Optional<ObjectInfo> head(String key);
    InputStream open(String key, ByteRange range);
    Stream<ObjectSummary> list(String prefix);
    Stream<ListEntry> listDirectory(String prefix);   // ObjectSummary | CommonPrefix
    void delete(String key);
    DeleteResult deleteAll(Collection<String> keys);
    void copy(String sourceKey, String targetKey);
    URI presignGet(String key, Duration ttl);
    PresignedRequest presignPut(String key, Duration ttl, PutOptions options);
}
```

Exceções: `StorageException` > `ObjectNotFoundException`,
`PreconditionFailedException`, `AccessDeniedException`.

## Regras de semântica

| Tema | Regra |
|---|---|
| `version` | Token opaco (ETag no S3/Azure/OCI, generation no GCS); base da escrita condicional. |
| `delete` | Idempotente: objeto inexistente não é erro. |
| `put` | Exige `length`; tamanho desconhecido usa `MultipartOutputStream`. |
| `presignPut` | Devolve os headers obrigatórios (`x-ms-blob-type` no Azure, `Content-Type` assinado). |
| `copy` | Bloqueante, só dentro do mesmo storage; na OCI consulta a work request até concluir. |
| `deleteAll` | Não atômico; devolve as chaves que falharam; fallback em loop paralelo. |
| `list` / `listDirectory` | `list` plana; `listDirectory` um nível com separador `/` fixo (único aceito pela OCI); ambas em ordem lexicográfica. Erros em páginas tardias também viram `StorageException`. |
| Metadata | Chaves `[a-z0-9_]`, valores ASCII, validados no core. |
| Checksum | Interno a cada adapter, fora da API. |

## Testes

- `storage-testkit`: `ObjectStorageContractTest` abstrato; `InMemoryObjectStorage` precisa passar.
- S3: Testcontainers + MinIO. Azure: Testcontainers + Azurite.
- GCS e OCI: mocks no build; contrato contra bucket real rodado manualmente.
- Teste de fumaça com os quatro adapters no mesmo classpath.

## Fases

| Fase | Entrega | Pronto quando | Status |
|---|---|---|---|
| F0 | Repo multi-módulo com o código do report-streaming migrado | Build verde, 35 testes passando | Concluída |
| F1 | API do core, exceções, validação de metadata, InMemory, contrato | InMemory passa no contrato | Concluída |
| F2 | S3 e Azure completos | Contrato verde com MinIO e Azurite | Concluída |
| F3 | GCS e OCI completos | Mocks verdes; contrato manual em bucket real | Mocks verdes; contrato em bucket real pendente |
| F4 | Starter Spring Boot 3.5.x | Teste de auto-configuração por provedor | Concluída |
| F5 | `mvn install` da 0.1.0 e migração do report-streaming | Exemplo roda contra MinIO e Azurite | Migrado; exemplo verificado contra MinIO |

Planos em andamento ficam em [`docs/plans/active/`](plans/active/):

- [Absorver as abstrações exigidas pelo oobj-ms-dfe-relatorios](plans/active/2026-09-28-absorver-abstracoes-dfe-relatorios.md)
