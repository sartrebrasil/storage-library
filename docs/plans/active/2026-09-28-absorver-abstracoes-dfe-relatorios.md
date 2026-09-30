# Plano: absorver as abstrações exigidas pelo oobj-ms-dfe-relatorios

- **Status:** ativo; G0, G2, G3, G4 e G5 concluídas; G6 parcial (N1 e N2 feitos); G7 em andamento (MR 1); falta o job de CI de G1
- **Criado em:** 28/09/2026
- **Consumidor:** `oobj-ms-dfe-relatorios`, módulos `reports-api` e `reports-worker` (OOBJ-918)
- **Origem:** comparativo publicado em https://claude.ai/artifact/NW7QYAf4hkC42E6eK55Avb.
  Os IDs `B*` (bloqueios) e `L*` (lacunas) abaixo são os mesmos do comparativo.

## Objetivo

A lib está em concepção e ainda não será adotada pelo `dfe-relatorios`. O consumidor
serve de fonte de requisitos: tudo que ele implementa e que faz sentido de forma genérica
é abstraído aqui. Critério final: a lib estar pronta para substituir o código S3 próprio do `dfe-relatorios`
(`S3MultipartOutputStream`, `S3ReportArtifactWriter`, `ReportS3Configuration`,
`S3HealthIndicator` e os acessos diretos ao SDK nos handlers de download) sem regressão
de comportamento. O plano cobre só o que pertence à lib. A migração do consumidor é
outro plano, no repositório dele.

## Regras que valem para todas as fases

- Toda mudança de API no core vale para os quatro adapters (S3, GCS, Azure, OCI). Uma
  capacidade que um provedor não tem precisa de fallback no core ou de uma documentação
  explícita da diferença na tabela "Diferenças por provedor" do README.
- Todo comportamento novo entra no `ObjectStorageContract` quando for portável, para que
  `InMemoryObjectStorage` e os adapters sejam verificados pelo mesmo teste.
- A API nova é aditiva. Nenhuma assinatura existente muda (a lib está em 0.x, mas o
  único consumidor ainda não migrou, e não há motivo para quebrar).
- Uma fase termina com `mvn install` verde, incluindo os contratos em MinIO e Azurite.
- README e `docs/plan.md` são atualizados na mesma mudança que altera o comportamento.

## Fora do escopo

| Item | Decisão | Motivo |
|---|---|---|
| B1: chaves de metadata com hífen | A lib continua estrita (`[a-z_][a-z0-9_]*`). O consumidor renomeia para `report_id` etc. | O Azure rejeita hífen. Relaxar por adapter quebra a portabilidade, que é a razão da lib existir. |
| L9: prefixo de chave e properties no namespace do consumidor | Não entra. O consumidor declara o próprio bean `ObjectStorage` e mantém o prefixo em `ArtifactCoordinates`. | O starter já desliga com um `ObjectStorage` próprio. Um prefixo global é uma abstração que nenhum outro caso pediu. |

## Revisões

- **29/09/2026 — L1 revisto: digest e `upload` entram na lib.** Na migração do consumidor, o
  `DigestOutputStream`, o `nonClosing()` + `commit()` e o try aninhado deixaram o writer mais
  complexo que o `S3MultipartOutputStream` que ele substituiu. A lib ganhou
  `MultipartConfig.withDigest(algorithm)` com `MultipartOutputStream.digestHex()`, e
  `MultipartOutputStream.upload(storage, key, metadata, config, body)`, que faz commit quando o corpo
  retorna e aborta em qualquer exceção, devolvendo `Result` (valor do corpo, `uploadId`, bytes, partes,
  digest). API aditiva: `open`/`commit`/`nonClosing` continuam. Testes: 3 novos em
  `MultipartOutputStreamTest`.
- **30/09/2026 — `MultipartOutputStream` separado por responsabilidade.** O stream ficou com
  buffer, limite, digest e estado; o envio das partes foi para `PartUploader` (package-private), com
  `SequentialPartUploader` (um buffer, envio na thread que escreve) e `ParallelPartUploader`
  (executor, pool de buffers, backpressure). API pública igual. Mudança de comportamento: uma falha
  de parte em `write` aborta o upload na hora, em vez de esperar o `close()`. Teste novo:
  `falhaDeParteAbortaNaHoraEBloqueiaNovasEscritas`.

## Fases

| Fase | Entrega | Itens | Depende de | Status |
|---|---|---|---|---|
| G0 | Identificação de gaps: confirmar cada item contra o código dos quatro adapters | todos | — | Concluída (L11 fechado em G3) |
| G1 | Compatibilidade de SDK | B5 | G0 | Pendente; só falta o job de CI. B4 fora do escopo (D1, D2) |
| G2 | Multipart: modo sequencial, stream sem `close`, limite de tamanho, `uploadId` | B2, B3, L2, L10 | G0 | Concluída em 28/09/2026 |
| G3 | S3: checksum configurável, parte vazia no LocalStack, credenciais no starter | L3, L11, L8 | G0 | Concluída em 28/09/2026 |
| G4 | Leitura: `Range` HTTP, conteúdo com metadados, 416 | L4, L5, L6 | G0 | Concluída em 28/09/2026 |
| G5 | Health check | L7 | G0 | Concluída em 28/09/2026 |
| G6 | Lacunas da segunda análise: credencial vazia, tipo da exceção no multipart, pool HTTP, métricas, nome na URL de download | N1 a N6 | — | N1 e N2 concluídos em 28/09/2026; N3 e N5 em rascunho; N4 adiado; N6 sem mudança |
| G7 | Pipeline de artefato: `ObjectBody` com gzip, TTL proporcional, `Range` tolerante, `416` com tamanho, módulo `storage-spring-web` | P1 a P7 | N5 (MR 2) | MR 1 (P1 a P4) implementado em 30/09/2026, branch `feat/g7-artifact-pipeline-core`; MRs 2 a 4 pendentes |

G2 a G5 são independentes entre si e podem virar MRs separados. G1 só bloqueia o
consumo pelo CI, não o desenvolvimento das outras fases.

---

### G0: identificação de gaps

Para cada item de G1 a G5, confirmar no código da lib:

1. se o gap existe como descrito no comparativo;
2. como cada provedor se comporta (API nativa disponível, limitação, fallback necessário);
3. qual teste de contrato prova o comportamento.

O resultado de cada item é anotado na seção "Registro de G0" no fim deste arquivo, e a
proposta de API da fase é ajustada se a verificação mudar algo.

**Pronto quando:** todos os itens têm veredito (confirmado, ajustado ou descartado) e
nenhuma proposta de API depende de uma suposição não verificada.

---

### G1: compatibilidade de SDK

**B4. Publicação.** Fora do escopo por enquanto (D1, D2): a lib fica no GitHub com o
groupId `com.example`. O texto abaixo fica como referência para quando for adotada. Hoje a lib só existe via `mvn install` local, com groupId
`com.example` e remoto no GitHub pessoal.

- Decidir groupId e pacote base definitivos. Ver "Decisões pendentes".
- Adicionar `distributionManagement` apontando para o repositório Maven interno (JFrog)
  e o job de publicação no CI.
- Tirar o `-SNAPSHOT` e publicar a `0.2.0` quando G2 a G4 estiverem prontas.

**B5. Versão do AWS SDK.** A lib compila contra `2.55.6`; o consumidor fixa `2.31.78` no
BOM dele, e esse BOM prevalece.

- Levantar as APIs do SDK que o `storage-s3` usa e confirmar que existem em `2.31.78`
  (`ChecksumAlgorithm`, `UploadPartResponse.checksumCRC32()`, `forcePathStyle`,
  `NoSuchUploadException`, `presignPutObject(Consumer)`).
- Verificado em G0: os testes do `storage-s3` passam com `2.31.78` (ver registro).
  Falta só fixar isso no CI: um job que roda `mvn -pl storage-s3 -am test
  -Daws.sdk.version=2.31.78`.
- Documentar no README a versão mínima suportada de cada SDK.

**Pronto quando:** o artefato é resolvido pelo pipeline do consumidor, e o `storage-s3`
passa nos testes com a versão mínima declarada.

---

### G2: multipart

**B3. Modo sequencial.** A memória de pico hoje é `(maxInFlight + 1) × partSize`, com
`maxInFlight ≥ 1` e `Executor` obrigatório. O consumidor exige um único buffer de 16 MiB
(STG-01 AC 1).

- `MultipartConfig` passa a aceitar `maxInFlight = 0`: a parte é enviada na própria
  thread que escreve, sem `Executor` (pode ser `null` nesse modo) e com um único buffer.
- Fábrica `MultipartConfig.sequential(int partSize)`.
- Teste: com `maxInFlight = 0`, `allocatedBuffers` nunca passa de 1 e nenhuma tarefa é
  submetida a executor.

**B2. Stream que ignora `close()`.** `close()` sem `commit()` aborta, o que é o contrato
certo da lib. Mas `GZIPOutputStream.close()` fecha em cascata o stream de baixo, e
`ObjectMapper.writeValue(OutputStream, …)` fecha o alvo por padrão (`AUTO_CLOSE_TARGET`).
Nos dois casos o upload é abortado antes do `commit()`.

- Novo método `OutputStream MultipartOutputStream.nonClosing()`: devolve uma visão que
  repassa `write` e `flush` e cujo `close()` não faz nada.
- Uso esperado:
  ```java
  try (MultipartOutputStream out = MultipartOutputStream.open(storage, key, metadata, config)) {
      try (GZIPOutputStream gzip = new GZIPOutputStream(out.nonClosing())) {
          body.writeTo(gzip);
      }               // escreve o trailer do gzip, sem fechar o multipart
      out.commit();   // sem commit, o close() externo aborta
  }
  ```
- Rejeitado: um modo `commitOnClose`. Ele reabre o risco que o contrato atual evita,
  que é publicar um relatório truncado quando uma exceção fecha o stream.
- Testes: gzip sobre `nonClosing()` seguido de `commit()` publica o objeto;
  `ObjectMapper.writeValue(out.nonClosing(), …)` seguido de `commit()` publica o objeto.

**L2. Limite de tamanho.** O consumidor aborta e falha quando o artefato passa de
`maxArtifactBytes`.

- `MultipartConfig` ganha `maxObjectBytes` (`long`, `-1` = sem limite, que é o default).
- Nova exceção `ObjectTooLargeException extends StorageException`. `write` que
  ultrapassar o limite aborta o upload e lança essa exceção, antes de copiar os bytes
  para o buffer.
- Teste de contrato: escrever `limite + 1` bytes lança a exceção, e nenhum objeto nem
  upload pendente fica no storage (`InMemoryObjectStorage.activeUploadCount() == 0`).

**L10. `uploadId` para observabilidade.** O consumidor registra o `uploadId` no início
e no abort.

- `MultipartSession` ganha `String uploadId()`, abstrato. As cinco implementações já
  guardam um id: S3, GCS e OCI o id nativo; Azure o id sintético que prefixa os block
  ids; `InMemoryObjectStorage` o da sessão. Ver G0.
- `MultipartOutputStream` inclui o id nas mensagens de falha e de abort.

**Pronto quando:** os quatro itens estão cobertos por testes, e o README documenta o modo
sequencial, `nonClosing()` e o exemplo com `DigestOutputStream` (L1).

**Entregue (28/09/2026):**

- `MultipartConfig`: `maxInFlight = 0` aceito, `executor` opcional nesse modo, fábrica
  `sequential(partSize)`, componente `maxObjectBytes` (`UNLIMITED` por padrão) e
  `withMaxObjectBytes(n)`. O construtor de três argumentos continua existindo.
- `MultipartOutputStream`: envio na thread que escreve no modo sequencial, com a falha
  aparecendo na própria escrita; `nonClosing()`; limite de tamanho que aborta antes de
  copiar os bytes; `uploadId()`; `uploadId` em todas as mensagens de erro.
- `MultipartSession.uploadId()` abstrato, implementado nas cinco sessões.
- `ObjectTooLargeException extends StorageException`, com `limit()`.
- Testes: 6 novos em `MultipartOutputStreamTest` (thread e buffer únicos no modo
  sequencial, falha no modo sequencial, gzip sobre `nonClosing()`, limite, `uploadId`,
  validação do config) e 2 no contrato (multipart sequencial, limite sem publicar),
  que rodam também contra MinIO e Azurite. `mvn -o install`: 152 testes, 0 falhas,
  2 pulados (presign HTTP no InMemory, já existentes).
- README: seção "Multipart" com a tabela de configurações e o exemplo com
  `DigestOutputStream` (L1).

---

### G3: S3

**L3. Checksum configurável.** O adapter força `ChecksumAlgorithm.CRC32` em
`createMultipartUpload`, `uploadPart` e `putObject`. O consumidor usa
`RequestChecksumCalculation.WHEN_REQUIRED` por compatibilidade com o LocalStack e com
backends S3 compatíveis.

- Construtor ou fábrica de `S3ObjectStorage` com um parâmetro de checksum
  (`CRC32` por padrão, ou nenhum).
- Property `storage.s3.checksum: crc32 | none` no starter.
- Teste: com `none`, nenhuma requisição carrega `checksumAlgorithm`.

**L11. Parte vazia no LocalStack 3.0.** Um `uploadPart` com corpo de tamanho zero falha
na emulação do LocalStack 3.0 ("'NoneType' object has no attribute 'to_bytes'"). O
consumidor contorna com `RequestBody.empty()`.

- Primeiro passo da fase: reproduzir. O defeito foi medido no consumidor com
  `RequestBody.fromByteBuffer`; a lib usa `fromContentProvider`, que segue outro caminho
  no SDK. Não está confirmado que a lib é afetada.
- Se reproduzir: `S3MultipartSession.uploadPart` usa `RequestBody.empty()` quando
  `length == 0` (o adapter Azure já trata `length == 0` à parte).
- Contrato do S3 também contra LocalStack 3.0, via Testcontainers, além do MinIO.
  Pelo menos o caso "objeto vazio por multipart".

**L8. Credenciais no starter.** O starter cria um `StaticCredentialsProvider` para o
cliente e outro para o presigner, e sem fallback.

- Um único bean `AwsCredentialsProvider` (`@ConditionalOnMissingBean`), compartilhado
  pelo cliente e pelo presigner: credencial estática quando `access-key` estiver
  definida, com fallback para `DefaultCredentialsProvider`, em cadeia com
  `reuseLastProviderEnabled(true)`.
- Teste de auto-configuração: cliente e presigner recebem a mesma instância.

**Pronto quando:** o contrato do S3 passa em MinIO e LocalStack, com e sem checksum.

**Entregue (28/09/2026):**

- L11 reproduzido com um contrato novo contra LocalStack 3.0 (`S3LocalStackContractTest`)
  e um caso novo no contrato (`multipartVazioGeraObjetoVazio`). A hipótese do plano estava
  errada: `RequestBody.empty()` não resolve. O LocalStack 3.0 falha em qualquer upload de
  corpo vazio com checksum CRC32, e passa com checksum desligado, com qualquer tipo de corpo.
  O contorno do consumidor funcionava por outro motivo: ele já usava `WHEN_REQUIRED`.
- L3: `S3ObjectStorage.Checksum { CRC32, NONE }` e o construtor
  `S3ObjectStorage(s3, presigner, bucket, checksum)`. Com `NONE`, nenhuma requisição pede
  checksum e as partes concluem sem CRC32. Só isso não desliga o cálculo do SDK: o cliente
  precisa de `WHEN_REQUIRED`, o que o Javadoc e o README documentam.
- Starter: `storage.s3.checksum: crc32 | none`; `none` também põe o cliente em
  `WHEN_REQUIRED`. Valor desconhecido falha na inicialização.
- L8: bean `s3CredentialsProvider` (`@ConditionalOnMissingBean`), compartilhado por cliente
  e presigner: estático com `access-key`, senão `DefaultCredentialsProvider`. A cadeia
  "estático, depois padrão" do plano foi descartada: um provider estático com chave
  definida sempre resolve, então o fallback nunca rodaria.
- `storage.s3.async-credential-update` (padrão `true`): a cadeia padrão renova credenciais
  temporárias numa thread de fundo, para que nenhuma requisição espere a renovação.
- Descoberta: o LocalStack 3.0 ignora `If-None-Match`/`If-Match`. O contrato ganhou
  `supportsConditionalWrites()`, e os 3 testes de escrita condicional são pulados nele.
- Testes: contrato completo no LocalStack 3.0 (29, 3 pulados), unitário do `Checksum.NONE`
  (create, uploadPart, put e parte sem checksum) e 4 de auto-configuração (provider único,
  cadeia padrão sem chave, provider da aplicação, checksum `none`/inválido).
  `mvn -o install`: 233 testes, 0 falhas, 6 pulados.

---

### G4: leitura

**L5. `Range` do HTTP.** `ByteRange` só representa `offset` + `length`. O consumidor
repassa o header `Range` do cliente, que pode ser um sufixo (`bytes=-500`).

- `ByteRange.suffix(long length)` para os últimos N bytes.
- `ByteRange.parseHttp(String header)` para `bytes=a-b`, `bytes=a-` e `bytes=-n`. Header
  com várias faixas ou malformado lança `IllegalArgumentException`, e o consumidor
  responde 416 ou ignora o header.
- Sufixo por provedor (ver G0): S3 aceita `bytes=-n` nativo; OCI aceita
  `new Range(null, n)`; GCS já busca o `Blob` antes de ler, então converte com o tamanho
  sem chamada extra; Azure (`BlobRange` só aceita offset) precisa de `head` antes. Só o
  Azure paga a chamada extra.
- Objeto vazio com `Range`: qualquer faixa é insatisfatível (416). O consumidor gera
  artefatos vazios para relatórios sem linhas, então o caso entra no contrato.

**L4. Conteúdo com metadados.** O handler de stream do consumidor precisa do
`Content-Length` da faixa, do `Content-Range` e do tamanho total, e hoje os lê da
resposta do SDK.

- Novo record `ObjectContent(InputStream stream, long contentLength, ByteRange range, long totalSize)`,
  que implementa `Closeable`.
- `ObjectStorage.read(String key, ByteRange range)` devolve `ObjectContent`. O default
  do core usa `head` + `open` (duas chamadas); cada adapter sobrescreve com uma chamada
  só, lendo os metadados da resposta.
- `ObjectContent.contentRange()` monta o valor `bytes a-b/total` do header.
- `open` continua como está.

**L6. 416.** `StorageException.fromHttpStatus` não mapeia 416.

- Nova exceção `RangeNotSatisfiableException extends StorageException`, mapeada para 416.
- S3 e OCI recebem o 416 na própria chamada de leitura. GCS e Azure só falhariam na
  primeira leitura do stream (leitura sob demanda), então esses dois validam o offset
  contra o tamanho já conhecido e lançam a exceção antes de devolver o stream.
- Teste de contrato: ler a partir de um offset maior ou igual ao tamanho do objeto lança
  essa exceção na chamada, não no consumo, em todos os adapters.

**Pronto quando:** o contrato cobre faixa fechada, aberta, sufixo e fora dos limites nos
quatro adapters (GCS e OCI com mocks, como hoje).

**Entregue (28/09/2026):**

- `ByteRange`: `suffix(n)`, `parseHttp(header)`, `httpValue()`, `isSuffix()` e
  `resolve(size)`, que devolve a faixa fechada cortada no fim do objeto ou lança
  `RangeNotSatisfiableException`. `from(0)` é igual a `all()`: `bytes=0-` é servido
  inteiro, sem `206`.
- `ObjectContent(stream, range, totalSize)`, com `contentLength()`, `isPartial()` e
  `contentRange()` derivados, e `fromHttp(stream, contentLength, contentRange)` para os
  adapters que recebem `Content-Range`. O `contentLength` do plano virou método derivado,
  não componente.
- `ObjectStorage.read(key, range)`: o default faz `head` + `open`. S3 e OCI usam uma
  chamada; GCS, a busca do `Blob` que já existia mais a leitura; Azure, uma chamada (duas
  com sufixo). Nos quatro adapters, `open` passou a delegar para `read`.
- `RangeNotSatisfiableException`, mapeada de 416 em `StorageException.fromHttpStatus`.
- Descobertas nos testes:
  - O MinIO responde `206` com `Content-Range: bytes 0--1/0` a uma faixa num objeto vazio,
    em vez de `416`. `ObjectContent.fromHttp` trata faixa invertida ou total zero como 416.
  - Se um backend compatível ignorar o `Range` (resposta sem `Content-Range`), o adapter S3
    lança `StorageException` em vez de devolver o objeto inteiro como se fosse a faixa.
- Testes: 6 no contrato (sufixo e corte no fim, 416 na chamada, faixa em objeto vazio,
  `read` com tamanho e faixa, `read` de inexistente), rodando em memória, MinIO e Azurite;
  mocks de GCS (sufixo resolvido pelo tamanho, 416 antes de abrir o canal) e OCI (sufixo
  nativo `bytes=-5`, 416); 5 no core (`parseHttp`, `resolve`, `fromHttp`, 416).

---

### G5: health check

**L7.** O consumidor tem um `S3HealthIndicator` próprio, com `getBucketLocation`.

- `ObjectStorage.checkAccess()`: `default` que consome o primeiro item de `list("")`.
  Cada adapter sobrescreve com a chamada de bucket nativa (`headBucket`,
  `storage.get(bucket)`, `containerClient.exists()`, `getBucket`).
- `ObjectStorageHealthIndicator` no starter, ativo só com o Actuator no classpath
  (`@ConditionalOnClass(HealthIndicator.class)`) e com
  `management.health.storage.enabled` diferente de `false`.
- Teste de auto-configuração: o indicator só existe com o Actuator no classpath, e
  reporta `DOWN` com o bucket inexistente.

**Pronto quando:** o consumidor pode apagar `S3HealthIndicator` e `S3HealthIndicatorConfig`
e ligar o novo indicator no grupo de readiness.

**Entregue (28/09/2026):**

- `ObjectStorage.checkAccess()`: o default lê o primeiro item de `list("")`.
  - S3: `HeadBucket`.
  - GCS: uma página de um objeto (`pageSize(1)`) em vez de `storage.get(bucket)`, para
    exigir só `storage.objects.list`, não permissão sobre o bucket.
  - Azure: `container.exists()`.
  - OCI: `HeadBucket`.
  - Bucket inexistente vira `StorageException` com "não existe", nunca
    `ObjectNotFoundException`: o `404` de um HEAD chega sem código de erro.
- `ObjectStorageHealthIndicator` e `StorageHealthAutoConfiguration` no starter: bean
  `storageHealthIndicator`, ativo com o Actuator no classpath e um `ObjectStorage`
  (do starter ou da aplicação); `management.health.storage.enabled=false` desliga.
- O Actuator entra como dependência `optional` sem transitivas: com elas, o Jackson do
  Actuator rebaixava o dos SDKs e quebrava a auto-configuração do GCS e do Azure.
- Testes: 2 no contrato (bucket configurado passa; bucket inexistente falha sem
  `ObjectNotFoundException`, contra MinIO e Azurite); mocks de GCS e OCI; 5 de
  auto-configuração (UP, DOWN com a mensagem, sem storage, sem Actuator, desligado por
  property).
- `mvn -o install`: 196 testes, 0 falhas, 3 pulados (presign HTTP e bucket inexistente no
  InMemory).

---

### G6: lacunas da segunda análise

Origem: a segunda análise de adoção (28/09/2026) contra o estado da lib depois de G2 a G5.
Nada abaixo está implementado. Cada item traz o problema, o código proposto e os testes.

| Item | Tipo | Recomendação |
|---|---|---|
| N1 | Bug no starter | Fazer |
| N2 | Bug de contrato | Fazer |
| N3 | Abstração nova | Fazer |
| N4 | Abstração nova | Adiar |
| N5 | Abstração nova | Fazer |
| N6 | Decisão de semântica | Não mudar; já documentado |

#### N1. Chave vazia derruba o starter

`storage.s3.access-key: ${VAR:}` chega como `""`, não `null`. O starter testa `!= null`, e
`AwsBasicCredentials.create("", "")` lança "Access key ID cannot be blank." na inicialização.
Uma chave sem a outra também falha, com uma mensagem do SDK que não cita a property.

Proposta: normalizar no binding, no construtor compacto do record. Vale também para `region`,
porque `storage.s3.region: ${VAR:}` faria `Region.of("")`.

```java
// StorageProperties.S3
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
```

Nenhuma mudança em `StorageAutoConfiguration`: o `!= null` passa a ser correto.

**Entregue (28/09/2026)** como rascunhado, com 2 testes de auto-configuração: chaves e região
vazias sobem com a cadeia padrão e sem região explícita; só `access-key` falha com a mensagem.

Testes (`StorageAutoConfigurationTest`):
- `access-key` e `secret-key` vazios: sobe com `DefaultCredentialsProvider`.
- Só `access-key`: falha com a mensagem acima.
- `region` vazio: sobe sem região explícita (cadeia padrão da AWS).

#### N2. `commit()` e `write()` escondem o tipo da `StorageException`

Falhas de parte e de conclusão viram `IOException("Falha ao concluir upload…", cause)`. Quem
classifica por tipo (retry, 403 ou 503) só vê `IOException`, e um `AccessDeniedException`
vira erro retentável.

Proposta: `StorageException` sobe como está; `IOException` fica para o que é I/O local
(interrupção, stream já finalizado, limite de partes).

```java
// MultipartOutputStream.commit
} catch (IOException | RuntimeException e) {
    abortQuietly(e);
    throw e;   // rethrow preciso: StorageException continua StorageException
}

// MultipartOutputStream.rethrowIfFailed
Throwable t = failure.get();
if (t instanceof StorageException storage) {
    throw storage;
}
if (t != null) {
    throw new IOException("Upload de parte falhou para " + describe(), t);
}

// MultipartOutputStream.awaitAllParts
} catch (ExecutionException e) {
    if (e.getCause() instanceof StorageException storage) {
        throw storage;
    }
    throw new IOException("Falha no upload de parte de " + describe(), e.getCause());
}
```

Custo: a exceção de uma parte assíncrona sobe com o stack trace da thread do upload, não do
chamador. A mensagem da sessão já traz a chave, e o chamador sabe onde chamou. Alternativa
rejeitada: envolver numa nova `StorageException` do mesmo subtipo, o que exigiria um
construtor de cópia em cada subclasse.

Javadoc de `write` e `commit`: "falhas do storage chegam como `StorageException` (runtime);
`IOException` só para interrupção, stream finalizado ou limite de partes".

Testes (`MultipartOutputStreamTest`):
- `falhaNoUploadDeParteAbortaNoCommit` passa a esperar `StorageException`, não `IOException`.
- Sessão que lança `AccessDeniedException` no `complete`: `commit()` lança
  `AccessDeniedException` e aborta.
- Modo sequencial com parte que lança `StorageException`: a própria escrita lança o mesmo tipo.

Impacto no consumidor: o `catch (RuntimeException)` do writer passa a receber essas falhas, e
o `ReportRetryPolicy` classifica por `StorageException` e subtipos.

**Entregue (28/09/2026)** como rascunhado. Testes: os dois casos de falha de parte (paralelo e
sequencial) esperam a `StorageException` original; conclusão com `AccessDeniedException` sobe
o tipo e aborta; escrita em stream finalizado continua `IOException`. Perda aceita: a mensagem
de uma falha de parte não traz mais o `uploadId`, só a chave (vem da sessão do adapter).
`mvn -o install`: 238 testes, 0 falhas, 6 pulados.

#### N3. Pool HTTP e timeouts do cliente S3

Cada download em `disposition=stream` segura uma conexão do pool até o cliente terminar de
baixar. O pool padrão do SDK tem 50 conexões; acima disso, as leituras esperam.

Detalhe que muda a implementação: o SDK 2.55.6 usa `apache5-client` e o 2.31.78 usa
`apache-client`. Referenciar `ApacheHttpClient` ou `Apache5HttpClient` no starter prenderia
uma das duas versões. Proposta: configurar pelo SPI `SdkHttpService`, que vale para o cliente
HTTP síncrono que estiver no classpath.

```yaml
storage:
  s3:
    max-connections: 200                 # sem valor: padrão do SDK (50)
    connection-acquisition-timeout: 10s  # sem valor: padrão do SDK
    api-call-timeout: 5m                 # sem valor: sem limite
    api-call-attempt-timeout: 1m         # sem valor: sem limite
```

```java
// StorageProperties.S3: novos componentes
Integer maxConnections, Duration connectionAcquisitionTimeout,
Duration apiCallTimeout, Duration apiCallAttemptTimeout

// StorageAutoConfiguration.S3StorageConfiguration
@Bean
@ConditionalOnMissingBean
@Conditional(S3HttpClientConfigured.class)   // só com max-connections ou connection-acquisition-timeout
SdkHttpClient s3HttpClient(StorageProperties properties) {
    StorageProperties.S3 s3 = properties.s3();
    AttributeMap.Builder http = AttributeMap.builder();
    if (s3.maxConnections() != null) {
        http.put(SdkHttpConfigurationOption.MAX_CONNECTIONS, s3.maxConnections());
    }
    if (s3.connectionAcquisitionTimeout() != null) {
        http.put(SdkHttpConfigurationOption.CONNECTION_ACQUIRE_TIMEOUT, s3.connectionAcquisitionTimeout());
    }
    return ServiceLoader.load(SdkHttpService.class).findFirst()
            .orElseThrow(() -> new IllegalStateException("Nenhum cliente HTTP síncrono do AWS SDK no classpath"))
            .createHttpClientBuilder().buildWithDefaults(http.build());
}

// s3Client(..., ObjectProvider<SdkHttpClient> s3HttpClient)
s3HttpClient.ifAvailable(builder::httpClient);
if (s3.apiCallTimeout() != null || s3.apiCallAttemptTimeout() != null) {
    builder.overrideConfiguration(o -> o.apiCallTimeout(s3.apiCallTimeout())
            .apiCallAttemptTimeout(s3.apiCallAttemptTimeout()));
}
```

O `SdkHttpClient` vira bean porque o `S3Client` só fecha o cliente HTTP que ele mesmo cria;
como bean, o Spring fecha no shutdown.

A confirmar na implementação: se `api-call-timeout` interrompe a leitura de um
`ResponseInputStream` longo (download em stream). Se interromper, a documentação precisa
avisar que ele limita a duração do download.

Testes:
- `max-connections` presente: existe o bean `SdkHttpClient`, e o `S3Client` o usa.
- Sem nenhuma das properties: nenhum bean `SdkHttpClient`, e o SDK usa o próprio padrão.
- `api-call-timeout` chega a `serviceClientConfiguration().overrideConfiguration()`.

#### N4. Métricas Micrometer (recomendação: adiar)

O consumidor já tem `@Timed` e o `ReportMetrics` de domínio. Um decorator no starter duplicaria
parte disso, e há um custo de API: embrulhar o bean por `BeanPostProcessor` esconde o tipo
concreto (`getBean(S3ObjectStorage.class)` deixa de funcionar). Esboço, para quando um segundo
consumidor pedir:

```java
// storage.operation{operation=head|read|put|delete|presign_get|multipart_part|multipart_complete,
//                   outcome=success|not_found|error, exception=<classe>}
public final class MeteredObjectStorage implements ObjectStorage {
    // delega cada método dentro de um Timer.Sample; initiateMultipart devolve uma sessão que
    // mede uploadPart e soma os bytes em storage.bytes.written
}
```

#### N5. Nome do arquivo na URL de download

> Implementado em 30/09/2026 como MR 2 da G7 (branch `feat/g7-presign-download-name`). Além do
> desenho abaixo: filesystem e SFTP também ficam com o default (`UnsupportedOperationException`), e o
> decorador de métricas do starter delega o método novo (sem isso, o default lançaria mesmo com um
> adapter que suporta). O helper de validação é `ObjectMetadata.attachmentDisposition`.

`presignGet(key, ttl)` não permite sobrescrever o `Content-Disposition` da resposta, e o
navegador salva com o nome da chave (`…-a1.csv.gz`), não `report-{id}.csv.gz`.

```java
// ObjectStorage
/**
 * Como {@link #presignGet(String, Duration)}, com a resposta pedindo para salvar como
 * {@code downloadName} ({@code Content-Disposition: attachment}).
 *
 * @throws IllegalArgumentException      nome fora de ASCII imprimível, ou com {@code "} ou {@code \}
 * @throws UnsupportedOperationException provedor sem override de resposta (OCI); grave o nome
 *                                       no upload com {@link ObjectMetadata#withDownloadName}
 */
default URI presignGet(String key, Duration ttl, String downloadName) {
    throw new UnsupportedOperationException(getClass().getSimpleName() + " não sobrescreve Content-Disposition");
}
```

| Adapter | Como |
|---|---|
| S3 | `getObjectRequest(r -> r.bucket(bucket).key(key).responseContentDisposition(disposition))`, entra na assinatura |
| GCS | `Storage.SignUrlOption.withQueryParams(Map.of("response-content-disposition", disposition))` |
| Azure | `values.setContentDisposition(disposition)` antes de gerar o SAS |
| OCI | Default (`UnsupportedOperationException`): o PAR não aceita override de resposta |
| InMemory | Devolve a mesma URI de `presignGet(key, ttl)` |

A validação do nome fica num helper do core, reaproveitado por `ObjectMetadata.withDownloadName`,
que hoje não valida o nome.

Testes:
- Contrato, com HTTP: o `GET` na URL devolve `Content-Disposition: attachment; filename="r.csv"`
  (MinIO, LocalStack, Azurite). Um hook `supportsPresignDownloadName()` pula na OCI.
- Mock de GCS: o query param entra na assinatura.
- OCI: lança `UnsupportedOperationException`.
- Core: nomes com `"`, `\` ou não-ASCII são rejeitados.

#### N6. `bytes=0-` responde 200

`ByteRange.from(0)` é igual a `all()`, e o HTTP permite servir o objeto inteiro com 200.
Recomendação: não mudar. O Javadoc de `ByteRange` e o README já documentam. Se um cliente
exigir 206, a separação custa um terceiro estado em `ByteRange` ("faixa desde 0"), que vaza
para `resolve`, `isAll` e os quatro adapters.

---

### G7: pipeline de artefato (item 1 do ADR-001 do consumidor)

Origem: `docs/adr/001-extracao-seletiva-do-modulo-reports-para-bibliotecas.md` no
`oobj-ms-dfe-relatorios`. O upload do consumidor já passa por `MultipartOutputStream.upload`; o que
sobra de genérico lá é o codec gzip, o TTL da URL de download e o caminho de download por stream
(`StreamDispositionHandler`, `ArtifactResource`, `StreamConcurrencyGuard`).

Decisões (30/09/2026):

| # | Pergunta | Decisão |
|---|---|---|
| E1 | Onde fica o código web | Módulo novo `storage-spring-web`, só `spring-web`, sem autoconfigure. O starter não passa a trazer `spring-web`. |
| E2 | MVC e WebFlux | Só MVC (`ResponseEntity<Resource>`). WebFlux quando alguém pedir. |
| E3 | Handler de `416` | A lib não registra `@ControllerAdvice`. Entrega o valor do `Content-Range` e o consumidor mapeia. |
| E4 | Gzip | Na lib, como decorador de corpo (`ObjectBody.gzipped`), não como flag de `MultipartConfig`. É o formato do arquivo, não `Content-Encoding`. |
| E5 | Tipo do corpo | `MultipartOutputStream.Body` vira a interface própria `ObjectBody<T>`; o consumidor apaga `ArtifactBody` e usa `ObjectBody<Long>`. Quebra de API aceita: 0.x e um consumidor. |
| E6 | TTL proporcional | Na lib, `PresignTtl`: `size / bytesPerSecond` limitado a `[min, max]`. |
| E7 | Tamanho no `416` | `ObjectResponses.attachment` recebe o `ObjectInfo` do `head`; a `RangeNotSatisfiableException` ganha `totalSize()` opcional. |

Itens:

| # | Entrega | Módulo | MR |
|---|---|---|---|
| P1 | `ObjectBody<T>` com `gzipped(body)` (buffer de 64 KiB); `upload(...)` passa a recebê-lo | core | 1 |
| P2 | `PresignTtl(assumedBytesPerSecond, min, max).forSize(size)`, truncado em segundos | core | 1 |
| P3 | `ByteRange.parseHttpOrAll(header)`: `null`, malformado ou várias faixas viram `all()` (RFC 9110 §14.2) | core | 1 |
| P4 | `RangeNotSatisfiableException.totalSize()` (`OptionalLong`) e `withTotalSize(size)` | core | 1 |
| P5 | N5: `presignGet(key, ttl, downloadName)` nos adapters | core + adapters | 2 |
| P6 | `StreamLimiter` com `Permit` idempotente; `Attachment`; `ObjectResponses.attachment(storage, head, rangeHeader, attachment, permit)` e `unsatisfiedContentRange(e)` | `storage-spring-web` | 3 |
| P7 | Consumidor: apaga `ArtifactBody`, `encoderFor`, `ArtifactResource`, `ReleaseOnCloseInputStream`, `clampExpiresIn`; `416` com `Content-Range: bytes */N` | consumidor | 4 |

Os MRs 1 e 2 são independentes; o 3 depende do 1; o 4 depende dos três.

Registro do MR 1 (30/09/2026): `ObjectBody` (interface própria; `MultipartOutputStream.Body` removido),
`PresignTtl`, `ByteRange.parseHttpOrAll`, `RangeNotSatisfiableException.totalSize()`/`withTotalSize`.
`ByteRange.resolve` e `ObjectContent.fromHttp` já preenchem o tamanho, porque o conhecem. Testes: 5
novos em `CoreModelTest`, 1 novo e 1 migrado para `ObjectBody.gzipped` em `MultipartOutputStreamTest`.

Testes:

- P1: o objeto descomprime para o original; digest e `bytesWritten` contam os bytes comprimidos;
  exceção no corpo aborta.
- P2: pequeno fica no `min`, grande no `max`, intermediário proporcional; validação do construtor.
- P3: `null`, malformado e várias faixas viram `all()`; faixa válida igual a `parseHttp`.
- P4: sem tamanho, `totalSize()` vazio; `withTotalSize` preserva mensagem e causa.
- P6: `200`/`206` com headers; faixa malformada responde `200`; nome com aspas e acentos escapado
  (RFC 6266, `ContentDisposition` do Spring); falha na leitura e `close()` do resource soltam o
  permit; `close()` duplo solta uma vez; `416` com o tamanho do `head`. Contra `InMemoryObjectStorage`.

## Decisões

| # | Pergunta | Recomendação |
|---|---|---|
| D1 | GroupId e pacote definitivos | Decidido em 28/09/2026: fica `com.example` enquanto a lib estiver em concepção. |
| D2 | Onde fica o repositório | Decidido em 28/09/2026: continua no GitHub (`sartrebrasil/storage-library`), sem publicação em repositório Maven. |
| D3 | Versão mínima do AWS SDK suportada | `2.31.78`, a do consumidor, até ele subir de versão. Já verificada em G0. |

## Registro de G0

Verificação feita em 28/09/2026 sobre o commit `cfbc0cf`.

| Item | Veredito | Observações |
|---|---|---|
| B2 | Confirmado | `MultipartOutputStream.close()` chama `abort()` quando o estado é `OPEN`; não há visão que ignore `close()`. |
| B3 | Confirmado | O construtor de `MultipartConfig` rejeita `maxInFlight < 1` e exige `executor` não nulo. |
| B4 | Confirmado | GroupId `com.example`, sem `distributionManagement`; o remoto é `github.com/sartrebrasil/storage-library`. |
| B5 | Ajustado: não bloqueia | `mvn -o -pl storage-s3 -am -Daws.sdk.version=2.31.78 test`: 26 testes verdes (`S3ObjectStorageTest` 7, `S3MinioContractTest` 19), com `dependency:list` confirmando `s3` e `sdk-core` em 2.31.78. Resta só o job de CI. |
| L2 | Confirmado | Nenhum limite de tamanho no core nem nos adapters. |
| L3 | Confirmado | `ChecksumAlgorithm.CRC32` fixo em `put`, `initiateMultipart` e `S3MultipartSession.uploadPart`; o starter não expõe opção. |
| L4 | Confirmado | Os quatro adapters obtêm tamanho e faixa numa chamada só: S3/OCI pela resposta do `getObject`, GCS pelo `Blob` que `open` já busca, Azure pelas propriedades do `BlobInputStream`. |
| L5 | Ajustado | Sufixo nativo em S3 e OCI (`Range(null, n)`); GCS resolve com o tamanho que já tem; só o Azure precisa de `head` extra. |
| L6 | Ajustado | `fromHttpStatus` não mapeia 416. GCS e Azure leem sob demanda e só falhariam no consumo; precisam validar o offset antes de devolver o stream. |
| L7 | Confirmado | Nenhum health indicator no starter; o starter não depende do Actuator. |
| L8 | Confirmado | `credentials(s3)` é chamado separadamente para cliente e presigner, sem fallback para a cadeia padrão. |
| L10 | Ajustado | As cinco sessões já guardam `uploadId` (Azure: sintético, usado nos block ids). O método pode ser abstrato, sem `Optional`. |
| L11 | Ajustado em G3 | Reproduzido: `PutObject` e `UploadPart` de corpo vazio falham com checksum CRC32, com qualquer tipo de corpo (`fromContentProvider`, `fromInputStream`, `fromBytes`, `empty()`), no SDK 2.55.6 e no 2.31.78. A causa é o checksum, não o corpo; a correção é L3. |
