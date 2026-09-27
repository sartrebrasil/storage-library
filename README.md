# storage-lib

Abstração de object storage para Java 21: uma API única sobre AWS S3,
Google Cloud Storage, Azure Blob Storage e OCI Object Storage, com upload
multipart em streaming (sem manter o arquivo inteiro em memória ou em disco).

> Versão 0.x: a API ainda pode mudar entre versões menores.
> Plano de evolução em [`docs/plan.md`](docs/plan.md).

## Módulos

| Artefato | Conteúdo |
|---|---|
| `storage-core` | `ObjectStorage`, `MultipartSession`, modelos, exceções, `MultipartOutputStream`. Somente JDK. |
| `storage-testkit` | `InMemoryObjectStorage` e `ObjectStorageContract` (testes de contrato para implementações). |
| `storage-s3` | Adapter AWS S3 (SDK v2). Funciona com MinIO/LocalStack. |
| `storage-gcs` | Adapter Google Cloud Storage (multipart da XML API). |
| `storage-azure` | Adapter Azure Blob Storage (block blobs). |
| `storage-oci` | Adapter OCI Object Storage. |
| `storage-spring-boot-starter` | Auto-configuração Spring Boot 3.5 por properties (`storage.*`). |
| `storage-bom` | Alinha as versões dos módulos acima. |

Cada adapter traz só o SDK do seu provedor.

## Build e instalação local

```bash
mvn install
```

Instala os artefatos em `~/.m2`. Para usar em outro projeto:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.example</groupId>
      <artifactId>storage-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>com.example</groupId>
    <artifactId>storage-s3</artifactId>
  </dependency>
  <dependency>
    <groupId>com.example</groupId>
    <artifactId>storage-testkit</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

## Operações

| Operação | Método | Observação |
|---|---|---|
| Upload simples | `put(key, data, length, options)` | Devolve a versão. Objetos grandes: `MultipartOutputStream`. |
| Upload em partes | `MultipartOutputStream` / `initiateMultipart` | Streaming com backpressure; `close()` sem `commit()` aborta. |
| Metadados | `head(key)` | `Optional.empty()` se não existir. |
| Leitura | `open(key, ByteRange)` | Objeto inteiro, faixa ou "a partir de". |
| Listagem | `list(prefix)` | Todos os objetos abaixo do prefixo, ordem lexicográfica, páginas sob demanda. |
| Listagem por pasta | `listDirectory(prefix)` | Um nível: objetos (`ObjectSummary`) e pastas (`CommonPrefix`), separador `/`. |
| Remoção | `delete(key)`, `deleteAll(keys)` | Idempotentes; `deleteAll` devolve as falhas. |
| Cópia | `copy(source, target)` | Mesmo bucket; preserva metadata; bloqueia até concluir. |
| Escrita condicional | `PutOptions.ifNotExists()`, `.ifVersionMatches(v)` | Falha com `PreconditionFailedException`. |
| URL de download | `presignGet(key, ttl)` | |
| URL de upload | `presignPut(key, ttl, options)` | Devolve método, URL e cabeçalhos que o cliente deve enviar. |

Exceções: `ObjectNotFoundException`, `PreconditionFailedException`,
`AccessDeniedException`, todas subclasses de `StorageException`.

Chaves de metadata do usuário: só `[a-z_][a-z0-9_]*` e valores ASCII, o que os
quatro provedores aceitam sem transformar.

### Diferenças por provedor

| | S3 | GCS | Azure | OCI |
|---|---|---|---|---|
| Versão | ETag | generation | ETag | ETag |
| URL temporária | Presigned (SigV4), local | Signed URL V4 | SAS | Pre-Authenticated Request (recurso no bucket, revogável) |
| `presignPut` impõe metadata e condição | Sim (assinados) | Sim (assinados) | Só `ifNotExists` (SAS sem "write") | Não |
| `deleteAll` | Lote de 1000 | Lote de 100 | Uma chamada por objeto | Uma chamada por objeto |
| `copy` | CopyObject; > 5 GiB em partes | Rewrite até concluir | Cópia assíncrona com polling | Work request com polling |

## Uso

```java
// S3 (o presigner precisa da mesma configuração do cliente)
ObjectStorage storage = new S3ObjectStorage(s3Client, s3Presigner, "reports");
// GCS
ObjectStorage storage = GcsObjectStorage.create(HttpStorageOptions.getDefaultInstance(), "reports");
// Azure: connection string/chave da conta, ou Entra ID
ObjectStorage storage = AzureBlobObjectStorage.withSharedKey(containerClient);
ObjectStorage storage = AzureBlobObjectStorage.withUserDelegation(containerClient);
// OCI
ObjectStorage storage = new OciObjectStorage(objectStorageClient, namespace, "reports");
```

```java
String version = storage.put("config.json", bytes, PutOptions.of("application/json").ifNotExists());

try (MultipartOutputStream out = MultipartOutputStream.open(
        storage, "relatorios/r.csv", ObjectMetadata.of("text/csv"), MultipartConfig.defaults(executor))) {
    out.write(bytes);
    out.commit();   // sem commit, close() aborta o upload
}

PresignedRequest upload = storage.presignPut("entrada/arquivo.csv", Duration.ofMinutes(15), PutOptions.of("text/csv"));
// o cliente faz upload.method() em upload.url() enviando upload.headers()
```

## Spring Boot

Adicione o starter e o adapter do provedor:

```xml
<dependency>
  <groupId>com.example</groupId>
  <artifactId>storage-spring-boot-starter</artifactId>
</dependency>
<dependency>
  <groupId>com.example</groupId>
  <artifactId>storage-s3</artifactId>
</dependency>
```

```yaml
storage:
  provider: s3              # s3 | gcs | azure | oci; sem valor, nenhum bean é criado
  bucket: reports           # no Azure, o container
  s3:
    region: sa-east-1
    endpoint: http://localhost:9000   # opcional (MinIO/LocalStack)
    path-style: true
    access-key: minioadmin            # opcional; sem ela, cadeia padrão da AWS
    secret-key: minioadmin
  azure:
    connection-string: UseDevelopmentStorage=true   # ou endpoint + DefaultAzureCredential
    endpoint: https://conta.blob.core.windows.net
  gcs:
    project-id: meu-projeto                         # opcional (ADC)
  oci:
    profile: DEFAULT                                # ~/.oci/config ou config-file
    namespace: meu-namespace                        # opcional (consultado na API)
```

Injete `ObjectStorage`. Clientes do SDK declarados pela aplicação (`S3Client`,
`S3Presigner`, `BlobContainerClient`, `HttpStorageOptions`, cliente OCI) são
reaproveitados; um `ObjectStorage` próprio desliga a auto-configuração. No Azure,
com connection string o SAS usa a chave da conta; com `endpoint`, user delegation
(requer `azure-identity` no classpath).

## Testes

`mvn install` roda os testes de contrato contra MinIO e Azurite via
Testcontainers (pulados sem Docker). GCS e OCI não têm emulador compatível:
são cobertos por testes com mocks e assinatura local real.
