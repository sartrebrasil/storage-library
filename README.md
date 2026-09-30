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
| `storage-filesystem` | Adapter sobre filesystem local (`java.nio.file`), sem SDK de nuvem. |
| `storage-sftp` | Adapter sobre SFTP (sshj), para servidores que só falam SSH. |
| `storage-spring-boot-starter` | Auto-configuração Spring Boot 3.5 por properties (`storage.*`). |
| `storage-spring-web` | Download por Spring MVC (`ObjectResponses`, `StreamLimiter`). Só `spring-web`, sem autoconfigure. |
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
| Upload em partes | `MultipartOutputStream` / `initiateMultipart` | Streaming com backpressure ou sequencial; `close()` sem `commit()` aborta. Ver [Multipart](#multipart). |
| Metadados | `head(key)` | `Optional.empty()` se não existir. |
| Leitura | `open(key, ByteRange)` | Objeto inteiro, faixa, "a partir de" ou sufixo. Faixa fora do objeto lança `RangeNotSatisfiableException` na chamada. |
| Leitura para HTTP | `read(key, ByteRange)` | `ObjectContent`: stream, `contentLength()`, `contentRange()` e tamanho total, para responder `200`/`206`. Ver [Leitura por faixa](#leitura-por-faixa). |
| Listagem | `list(prefix)` | Todos os objetos abaixo do prefixo, ordem lexicográfica, páginas sob demanda. |
| Listagem por pasta | `listDirectory(prefix)` | Um nível: objetos (`ObjectSummary`) e pastas (`CommonPrefix`), separador `/`. |
| Remoção | `delete(key)`, `deleteAll(keys)` | Idempotentes; `deleteAll` devolve as falhas. |
| Cópia | `copy(source, target)` | Mesmo bucket; preserva metadata; bloqueia até concluir. |
| Escrita condicional | `PutOptions.ifNotExists()`, `.ifVersionMatches(v)` | Falha com `PreconditionFailedException`. |
| URL de download | `presignGet(key, ttl)` | `PresignTtl(bytesPorSegundo, min, max).forSize(tamanho)` calcula um TTL proporcional ao tamanho. |
| URL de download com nome | `presignGet(key, ttl, downloadName)` | A resposta vem com `Content-Disposition: attachment; filename="…"`, qualquer que seja a chave. OCI, filesystem e SFTP lançam `UnsupportedOperationException`: grave o nome no upload com `ObjectMetadata.withDownloadName`. |
| URL de upload | `presignPut(key, ttl, options)` | Devolve método, URL e cabeçalhos que o cliente deve enviar. |
| Acesso ao bucket | `checkAccess()` | Falha se o bucket não existe ou as credenciais não alcançam. Base do health check. |

Exceções: `ObjectNotFoundException`, `PreconditionFailedException`,
`AccessDeniedException`, `ObjectTooLargeException`, `RangeNotSatisfiableException`, todas
subclasses de `StorageException`.

Chaves de metadata do usuário: só `[a-z_][a-z0-9_]*` e valores ASCII, o que os
quatro provedores aceitam sem transformar.

### Diferenças por provedor

| | S3 | GCS | Azure | OCI |
|---|---|---|---|---|
| Versão | ETag | generation | ETag | ETag |
| URL temporária | Presigned (SigV4), local | Signed URL V4 | SAS | Pre-Authenticated Request (recurso no bucket, revogável) |
| Nome de download na URL | `response-content-disposition`, assinado | `response-content-disposition`, assinado | `rscd` no SAS | Não suportado |
| `presignPut` impõe metadata e condição | Sim (assinados) | Sim (assinados) | Só `ifNotExists` (SAS sem "write") | Não |
| `deleteAll` | Lote de 1000 | Lote de 100 | Uma chamada por objeto | Uma chamada por objeto |
| `copy` | CopyObject; > 5 GiB em partes | Rewrite até concluir | Cópia assíncrona com polling | Work request com polling |
| Chamadas em `read` com faixa | 1 | 2 (metadados + leitura) | 1; sufixo: 2 | 1 |
| `checkAccess` / permissão exigida | HeadBucket / `s3:ListBucket` | Lista 1 objeto / `storage.objects.list` | Container exists / leitura de propriedades do container | HeadBucket / `BUCKET_INSPECT` |

### S3 compatível: checksum

O adapter pede checksum CRC32 em `put` e no multipart, e o SDK (>= 2.30) calcula CRC32 por
conta própria. Backends que não suportam checksum flexível falham com isso. O LocalStack 3.0,
por exemplo, responde 500 ("'NoneType' object has no attribute 'to_bytes'") a qualquer
upload de corpo vazio. Nesses casos use `S3ObjectStorage.Checksum.NONE` com o cliente em
`WHEN_REQUIRED`, ou `storage.s3.checksum: none` no starter, que configura os dois:

```java
S3Client s3 = S3Client.builder()
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        // região, endpoint, credenciais...
        .build();
ObjectStorage storage = new S3ObjectStorage(s3, presigner, "reports", S3ObjectStorage.Checksum.NONE);
```

O LocalStack 3.0 também ignora `If-None-Match` e `If-Match`: `ifNotExists` e
`ifVersionMatches` não são garantidos nele.

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
// Filesystem local: sem SDK, root é o "bucket"
ObjectStorage storage = new FileSystemObjectStorage(Path.of("/var/data/reports"));
// SFTP: root é um caminho absoluto no servidor; o SSHClient chega já conectado/autenticado
ObjectStorage storage = new SftpObjectStorage(sshClient, "/reports");
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

### Multipart

| `MultipartConfig` | Efeito |
|---|---|
| `defaults(executor)` | Partes de 16 MiB, até 4 subindo em paralelo. Memória ≈ 5 × 16 MiB. |
| `sequential(partSize)` | Cada parte sobe na thread que escreve, com um único buffer e sem executor. Memória ≈ `partSize`. |
| `.withMaxObjectBytes(n)` | A escrita que passaria de `n` bytes aborta o upload e lança `ObjectTooLargeException`. |
| `.withDigest("SHA-256")` | Calcula o digest dos bytes enviados; lido em `digestHex()` depois do commit. Algoritmo inválido falha na configuração. |

Falhas do storage no upload chegam como `StorageException`, com o tipo original
(`AccessDeniedException`, `ObjectTooLargeException`…), em `write` ou em `commit()`, e o upload é
abortado na hora: escritas seguintes falham com `IOException`. `IOException` fica para interrupção,
stream já finalizado e limite de partes.

`MultipartOutputStream.upload` cobre o caso comum: entrega ao corpo um stream que pode ser
fechado sem efeito (`GZIPOutputStream.close()` e `ObjectMapper.writeValue(OutputStream, …)` fecham
o que recebem), faz `commit()` quando o corpo retorna e aborta em qualquer exceção:

```java
MultipartConfig config = MultipartConfig.sequential(16 * MultipartConfig.MIB)
        .withMaxObjectBytes(maxBytes)
        .withDigest("SHA-256");
MultipartOutputStream.Result<Long> result = MultipartOutputStream.upload(storage, key, metadata, config,
        ObjectBody.gzipped(out -> writeReport(out)));   // o valor devolvido volta em result.value()
log.info("upload {} concluído: {} bytes, sha256={}", result.uploadId(), result.bytesWritten(), result.digestHex());
```

O corpo é um `ObjectBody<T>`. `ObjectBody.gzipped(body)` grava o gzip do que `body` escreve, e
digest e `bytesWritten` contam os bytes comprimidos. É o formato do arquivo (`.gz`): não grave
`Content-Encoding: gzip` na metadata, ou o navegador descomprime ao baixar.

Usando o stream direto (`open`/`commit`), entregue `out.nonClosing()` a quem fecha o stream:
fechar sem `commit()` aborta.

### Leitura por faixa

`ByteRange.parseHttp` lê o cabeçalho `Range` (`bytes=a-b`, `bytes=a-`, `bytes=-n`; várias
faixas não são suportadas) e lança `IllegalArgumentException` no resto. `ByteRange.parseHttpOrAll`
faz o que a RFC 9110 §14.2 pede de um servidor: cabeçalho ausente, malformado ou com várias faixas
vira `ByteRange.all()`, e o objeto é servido inteiro. `read` devolve o que a resposta precisa:

```java
try (ObjectContent content = storage.read(key, ByteRange.parseHttpOrAll(header))) {
    response.setStatus(content.isPartial() ? 206 : 200);
    response.setContentLengthLong(content.contentLength());
    content.contentRange().ifPresent(value -> response.setHeader("Content-Range", value));
    content.stream().transferTo(response.getOutputStream());
} catch (RangeNotSatisfiableException e) {
    response.setStatus(416);
    e.totalSize().ifPresent(size -> response.setHeader("Content-Range", "bytes */" + size));
}
```

`totalSize()` vem vazio quando o adapter não sabe o tamanho (o S3 não o devolve no erro). Quem já
fez `head` completa com `e.withTotalSize(info.size())`.

A faixa é cortada no fim do objeto, como no HTTP. `bytes=0-` equivale a `ByteRange.all()` e
é servido inteiro, sem `206`. Num objeto vazio, qualquer faixa é `416`.

### Download por Spring MVC

`storage-spring-web` monta a resposta acima para um controller: `200`/`206`, `Content-Disposition`
(RFC 6266, com `filename*` para nomes com acento), `Content-Type`, `Content-Length`, `Accept-Ranges`
e `Content-Range`, com o stream do storage como corpo. `StreamLimiter` limita downloads simultâneos
por instância sem bloquear; o `Permit` é solto quando o Spring fecha o corpo, ou na hora se a leitura
falhar.

```java
StreamLimiter limiter = new StreamLimiter(20);

@GetMapping("/files/{id}")
ResponseEntity<Resource> download(@PathVariable String id, @RequestHeader(value = "Range", required = false) String range) {
    ObjectInfo head = storage.head(keyOf(id)).orElseThrow(NotFound::new);
    StreamLimiter.Permit permit = limiter.tryAcquire().orElseThrow(TooManyDownloads::new);   // 429
    return ObjectResponses.attachment(storage, head, range,
            Attachment.of("report-1.csv.gz", "application/gzip").withHeader("X-Checksum-Sha256", sha256), permit);
}

@ExceptionHandler(RangeNotSatisfiableException.class)
ResponseEntity<Void> rangeNotSatisfiable(RangeNotSatisfiableException e) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
    ObjectResponses.unsatisfiedContentRange(e).ifPresent(value -> response.header(HttpHeaders.CONTENT_RANGE, value));
    return response.build();
}
```

O `head` é do chamador porque ele já o faz (para responder `404`/`410`), e é o tamanho dele que
completa o `416` quando o adapter não sabe. A lib não registra `@ControllerAdvice`: o mapeamento
de exceções é da aplicação. Só Spring MVC; WebFlux não é suportado.

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
  provider: s3              # s3 | gcs | azure | oci | filesystem | sftp; sem valor, nenhum bean é criado
  bucket: reports           # no Azure, o container; vários: buckets (abaixo)
  s3:
    region: sa-east-1
    endpoint: http://localhost:9000   # opcional (MinIO/LocalStack)
    path-style: true
    access-key: minioadmin            # opcional, junto com secret-key; vazia conta como ausente
    secret-key: minioadmin
    checksum: crc32                   # crc32 (padrão) | none, para backends sem checksum flexível
    async-credential-update: true     # padrão; sem access-key, renova credenciais temporárias em fundo
  azure:
    connection-string: UseDevelopmentStorage=true   # ou endpoint + DefaultAzureCredential
    endpoint: https://conta.blob.core.windows.net
  gcs:
    project-id: meu-projeto                         # opcional (ADC)
  oci:
    profile: DEFAULT                                # ~/.oci/config ou config-file
    namespace: meu-namespace                        # opcional (consultado na API)
  filesystem:
    root: /var/data/oobj-storage                    # cada bucket vira uma subpasta de root
  sftp:
    host: sftp.exemplo.com.br
    port: 22                                        # padrão
    username: oobj
    password: ${SFTP_PASSWORD}                      # ou private-key-path, nunca os dois
    root: /reports                                  # cada bucket vira uma subpasta de root
    known-hosts: /etc/ssh/known_hosts                # sem valor, tenta ~/.ssh/known_hosts
    insecure-trust-all-hosts: false                  # true pula a verificação de host key (só dev/teste)
```

Com o Actuator no classpath, o starter registra `storageHealthIndicator` (chave `storage`
em `/actuator/health`), que chama `checkAccess()`. Vale também para um `ObjectStorage`
declarado pela aplicação. Com vários `ObjectStorage` (um por bucket), o indicador vira
composto, um por bean, na chave do nome do bean (`/actuator/health/storage/reportsStorage`);
qualquer bucket `DOWN` deixa `storage` `DOWN`. Desligue com `management.health.storage.enabled=false`.

Com um `MeterRegistry` no contexto (Actuator + registro de métricas, ex.: Prometheus), o starter
decora todo `ObjectStorage` e mede cada operação em `storage.operations` (tags `operation` —
`put`, `head`, `open`, `list`, `delete`, `copy`, `checkAccess`...—, `storage` com o nome do bean,
e `outcome`, `success` ou `error`). Sem `MeterRegistry` no classpath ou no contexto, não decora
nada.

No S3, o cliente e o presigner usam o mesmo bean `AwsCredentialsProvider`: credencial
estática com `access-key`, senão a cadeia padrão da AWS. Na cadeia padrão, credenciais
temporárias (IRSA, STS, metadata da instância) são renovadas numa thread de fundo antes de
expirarem (`storage.s3.async-credential-update`, ligado por padrão), então nenhuma requisição
espera a renovação. Um `AwsCredentialsProvider` da aplicação substitui o do starter.

Injete `ObjectStorage`. Clientes do SDK declarados pela aplicação (`S3Client`,
`S3Presigner`, `BlobServiceClient`, `BlobContainerClient`, `HttpStorageOptions`, cliente OCI)
são reaproveitados; um `ObjectStorage` próprio desliga a auto-configuração.

Vários buckets: use `storage.buckets` no lugar de `storage.bucket` (os dois juntos falham na
inicialização). Cada entrada vira um bean `<nome>ObjectStorage` com qualifier `<nome>`, todos
com os mesmos clientes e credenciais:

```yaml
storage:
  provider: s3
  buckets:
    reports: oobj-reports
    artifacts: oobj-artifacts
```

```java
ReportService(@Qualifier("reports") ObjectStorage reports) { ... }
```

Buckets com configuração diferente (outra conta, região ou provedor): declare um
`ObjectStorage` por bucket, reaproveitando os clientes do starter (`S3Client` + `S3Presigner`,
`Storage`, `BlobServiceClient`, cliente OCI) ou os seus. No Azure, derive cada container com
`AzureBlobObjectStorage.withSharedKey(blobServiceClient.getBlobContainerClient("reports"))`
(ou `withUserDelegation`, com `endpoint`). No Azure,
com connection string o SAS usa a chave da conta; com `endpoint`, user delegation
(requer `azure-identity` no classpath).

## Testes

`mvn install` roda os testes de contrato contra MinIO, LocalStack 3.0, Azurite e um servidor
OpenSSH sftp-server real (imagem `atmoz/sftp`) via Testcontainers (pulados sem Docker). GCS e
OCI não têm emulador compatível: são cobertos por testes com mocks e assinatura local real.
