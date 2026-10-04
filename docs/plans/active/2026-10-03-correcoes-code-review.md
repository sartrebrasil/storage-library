# Plano: correções do code review completo

- **Status:** ativo; R0 a R5 concluídas; próxima: R6
- **Criado em:** 03/10/2026
- **Origem:** code review completo dos 11 módulos (commit `f0884ec`). Itens marcados com ✔ foram
  confirmados no código ou nos fontes dos SDKs em `~/.m2` (AWS 2.55, GCS 2.74, Azure Blob 12.35,
  OCI 3.97, Spring 6.2 / Boot 3.5, sshj 0.41). Os demais vêm da leitura do código e precisam de um
  teste que reproduza a falha antes da correção.

## Objetivo

Eliminar os bugs de perda de dados, de contrato e de observabilidade encontrados no review, depois
reduzir a duplicação entre adapters e fechar as lacunas de teste que deixaram esses bugs passar.

## Regras que valem para todas as fases

- Toda correção começa por um teste que falha (unitário ou no `ObjectStorageContract`, quando o
  comportamento é portável).
- Correção de comportamento portável vale para todos os adapters afetados na mesma fase.
- API pública só muda de forma aditiva. Exceção: R8 (`ObjectMetadata` na leitura), que pode exigir
  uma factory nova.
- Uma fase termina com `mvn install` verde, incluindo os contratos em MinIO, Azurite e SFTP.
- README e `docs/plan.md` são atualizados na mesma mudança que altera comportamento documentado.
- Um PR por item crítico; itens pequenos da mesma fase e do mesmo módulo podem ir juntos.

## Fases

| Fase | Entrega | Itens | Depende de | Status |
|---|---|---|---|---|
| R0 | Críticos: perda de dados e recursos quebrados | C1–C6 | — | Concluída |
| R1 | Core e contrato de exceções | A1–A3 | — | Concluída |
| R2 | Integridade em filesystem e SFTP | A5–A8, M7–M9 | R1 (A1) | Concluída |
| R3 | Adapters de nuvem | A4, A10, A11, M3–M6 | R1 (A3) | Concluída |
| R4 | SFTP operacional | A9, M10 | R2 | Concluída |
| R5 | Spring: robustez e observabilidade | M1, M2, B-Spring | R0 (C2, C3) | Concluída |
| R6 | Baixa severidade restante | B-* | R1–R5 | Pendente |
| R7 | Refatoração: duplicação para o core | F1–F7 | R2, R3 | Pendente |
| R8 | Lacunas de teste | T1–T4 | paralela a todas | Pendente |

## Andamento por item

| Item | Situação | Commit |
|---|---|---|
| C1 | Feito | `3d45be8` |
| C2 | Feito | `6d6d130` |
| C3 | Feito | `80126ed` |
| C4 | Feito | `4e03ca7` |
| C5 | Feito | `1ac834f` |
| C6 | Feito (teste fixa a classe do corpo, sem MockMvc) | `3ca1261` |
| A1 | Feito | `57aca1b` |
| A2 | Feito (metadata do provedor mantida como veio) | `57dc26c` |
| A3 | Feito | `c741371` |
| A4 | Feito | `8001267` |
| A5 | Feito | `0d7181f` (filesystem), `b0a4e09` (SFTP) |
| A6 | Feito | `0d7181f` (filesystem); SFTP já em `3d45be8` |
| A7 | Feito | `0d7181f`, `b0a4e09` |
| A8 | Feito | `0d7181f`, `b0a4e09` |
| A9 | Feito | `bff769e` |
| A10 | Feito | `85b77f6` |
| A11 | Feito | `80cf5d6` |
| M3 | Feito na OCI; S3 e GCS cobertos por `checkAccess` | `27eea07` |
| M4 | Feito em S3, Azure e OCI; GCS não verificado, mantido | `85b77f6`, `8001267`, `27eea07` |
| M5 | Feito | `8001267` |
| M6 | Feito | `85b77f6`, `27eea07` |
| M7 | Feito (teste só roda em POSIX) | `0d7181f` |
| M8 | Feito | `b0a4e09` |
| M9 | Feito (sem teste) | `b0a4e09` |
| M10 | Feito | `b6f626c` |
| Fora do plano | Corrida no `mkdirs` do SFTP com partes em paralelo | `5b452b2` |
| M1 | Feito | `6a3df1c` |
| M2 | Feito como documentação no javadoc de `list`/`listDirectory` | `4f909f0` |
| B-Spring | Feito: `Error`, `AtomicBoolean`, `SIZED`, `toString`, `azure-identity` (`4f909f0`); `ETag`/`If-Range` (`6a3df1c`) | `4f909f0`, `6a3df1c` |
| R6, R7, R8 | Pendente | |

## R0 — Críticos

| ID | Módulo | Problema | Correção | Teste |
|---|---|---|---|---|
| C1 ✔ | sftp | `copy(k, k)` apaga o objeto: `copyRemoteFile` abre o destino com `TRUNC` antes de ler a origem (`SftpObjectStorage.java:316`). | Copiar para `.pending-*` e renomear (resolve também A6); no mínimo, retornar cedo quando `source.equals(target)`. | Contrato: `copySobreSiMesmoPreservaConteudo`. |
| C2 ✔ | starter | Métricas/tracing inativos em app real: `after` não inclui `ObservationAutoConfiguration` (ordem alfabética faz `@ConditionalOnBean` falhar) e o BeanPostProcessor injeta o `ObservationRegistry` antes dos handlers (`StorageMetricsAutoConfiguration.java:20,30`). | `after` com `ObservationAutoConfiguration` por nome; `ObjectProvider<ObservationRegistry>` resolvido dentro de `postProcessAfterInitialization`. | Teste com `ObservationAutoConfiguration`, `MetricsAutoConfiguration` e `SimpleMetricsExportAutoConfiguration` reais, sem registry do usuário. |
| C3 ✔ | starter | `ObjectStorageMetrics` não repassa `read`, `listDirectory` e `deleteAll`: os defaults substituem as versões nativas (HEAD + GET, scan completo, N deletes). | Overrides observados dos três; `read` observa até o `ObjectContent` fechar. Corrigir o javadoc. | Teste por método verificando que o delegate recebe a chamada. |
| C4 ✔ | gcs | `deleteAll` trata erro por item (403/429/503) como sucesso: `storage.delete(List)` devolve `false` para erro e para inexistente (`GcsObjectStorage.java:229`). | `storage.batch()` com `BatchResult.Callback` por chave; só 404 conta como sucesso. | Unitário com falha de um item no lote. |
| C5 ✔ | oci | Prefixo `opc-meta-` duplicado: o `ObjectMetadataInterceptor` do SDK já prefixa (`OciObjectStorage.java:113,135`). | Enviar `userMetadata` sem prefixo em `put` e `initiateMultipart`; manter o prefixo só nos cabeçalhos crus de `presignPut`; `unprefixed` continua tirando o prefixo extra dos objetos legados. Corrigir os mocks que afirmam o comportamento errado (`OciObjectStorageTest:58`, `OciOperationsTest:193,207`). | Unitário passando pelo interceptor real. |
| C6 | spring-web | `ObjectContentResource extends InputStreamResource` faz o Spring reprocessar o `Range`: multi-faixa sai `206 multipart/byteranges` com `Content-Length` errado; malformado sai 416 com corpo inteiro (`ObjectResponses.java:76,118`). | Usar `new InputStreamResource(body)` e remover `ObjectContentResource`. | MockMvc: Range malformado, multi-faixa, `bytes=0-`. |

## R1 — Core e contrato de exceções

| ID | Módulo | Problema | Correção | Teste |
|---|---|---|---|---|
| A1 ✔ | core | Overflow em `ByteRange.resolve`: `bytes=5-9223372036854775807` num objeto de 100 bytes devolve faixa sem corte (reproduzido no jshell). Afeta GCS, Azure, FS, SFTP, InMemory e o `read` default. | `long end = toEnd() \|\| length >= size - offset ? size : offset + length;` | `CoreModelTest` e contrato com a faixa acima. |
| A2 | todos | `head()` lança `IllegalArgumentException` com metadata gravada por outras ferramentas (`goog-reserved-file-mtime`, `s3cmd-attrs`, maiúsculas no Azure), porque o construtor de `ObjectMetadata` valida também na leitura. | Validar só na escrita: factory sem validação (ex.: `ObjectMetadata.fromProvider`) usada no `head` de todos os adapters. Decidir se chaves inválidas são mantidas ou descartadas. | Unitário por adapter com metadata estrangeira. |
| A3 | todos | Os multipart sessions lançam `new StorageException(...)` em vez do `translate` do adapter: 403 nunca vira `AccessDeniedException` (S3, GCS, Azure, OCI). | Usar `translate` do adapter em `uploadPart`, `complete`, `abort` e `listParts`. | Unitário com 403 e 404 em `uploadPart` e `complete`. |

## R2 — Integridade em filesystem e SFTP

| ID | Problema | Correção |
|---|---|---|
| A5 | Dados e sidecar não são gravados de forma atômica, e o sidecar é sobrescrito no lugar. Leitura concorrente vê metadata vazia; um crash entre os passos deixa versão antiga com conteúdo novo e quebra `ifVersionMatches`. | Sidecar via temporário + rename atômico; gravar tamanho e mtime no sidecar e ignorá-lo quando não baterem com o arquivo. |
| A6 | `copy` escreve direto no destino (FS:167, SF:316): leitores veem objeto pela metade. | Passar pelo mesmo caminho temporário + rename de `writeData`. |
| A7 | Chaves reservadas aceitas: `put("a.txt.objmeta")` sobrescreve a metadata de `a.txt`; `.pending-*` some do `list`. | Rejeitar em `resolve` segmentos terminados em `META_SUFFIX` ou iniciados por `TEMP_PREFIX`. |
| A8 | A versão do `ifVersionMatches` difere da de `head`/`list` para arquivo sem sidecar (`"v"+mtime`). No SFTP, sidecar órfão passa a condição sem o arquivo existir. | Uma função única `versionOf(props, mtime)`; no SFTP, exigir que o arquivo de dados exista. |
| M7 | FS: `Files.createTempFile` grava com `rw-------`; o multipart respeita a umask. | `Files.createFile(parent.resolve(TEMP_PREFIX + UUID + ".tmp"))`. |
| M8 | SFTP: `.pending-*` vaza quando o stream de entrada falha (SF:253-280). | `rmQuietly` em qualquer exceção. |
| M9 | SFTP: com `ifNotExists`, qualquer falha no rename vira `PreconditionFailed` (SF:268). | Em falha, `stat` do destino; só é precondição se ele existir. |

## R3 — Adapters de nuvem

| ID | Módulo | Problema | Correção |
|---|---|---|---|
| A4 | azure | `copy`: timeout, interrupção e erro de polling escapam como `RuntimeException` crua; o flag de interrupção se perde e a cópia segue no servidor (`AzureBlobObjectStorage.java:232`). | Capturar `RuntimeException` em `waitForCompletion`, desembrulhar a causa (`AzureException` vira `translate`, `InterruptedException` reinterrompe), `abortCopyFromUrl` em timeout ou interrupção. |
| A10 | s3 | Cópia multipart acima de 5 GiB sem `copySourceIfMatch`: origem sobrescrita no meio mistura versões (`S3ObjectStorage.java:353`). Também: parte fixa de 512 MiB passa de 10.000 partes acima de ~4,88 TiB; falha do abort substitui a exceção original; metadata além de content-type, disposition e user metadata se perde. | `copySourceIfMatch(source.version())`; `partSize = max(512 MiB, ceil(size / 10_000))`; abort com `addSuppressed`; documentar o que a cópia em partes preserva. |
| A11 | gcs | `put` ignora `length` (stream maior grava extra, menor trunca) e aloca buffer de 15 MiB por chamada (`GcsObjectStorage.java:98`). | Limitar o stream a `length` e conferir a contagem; buffer `clamp(length, 256 KiB, 15 MiB)` ou `storage.create` com bytes para objetos pequenos. |
| M3 | oci, s3, gcs | `head` com bucket inexistente devolve `Optional.empty()` (HEAD sem corpo, serviceCode `"Unknown"` no OCI); `copy` reporta `ObjectNotFoundException`. | No OCI, `headBucket` após 404 para distinguir; nos demais, documentar que `checkAccess` cobre o caso. |
| M4 | todos | 404 com `ifVersionMatches` vira `PreconditionFailed` mesmo com bucket ou container inexistente. | Checar bucket ausente antes do atalho. |
| M5 | azure | Multipart concorrente na mesma chave descarta os blocos do outro upload (`InvalidBlockList`). | Documentar no javadoc e na tabela do README; mensagem clara para `InvalidBlockList`. |
| M6 | s3, oci | `read` vaza o stream e lança exceção não-`StorageException` com `Content-Range` malformado ou `contentLength` nulo. | Envolver o parse, abortar o stream e traduzir; no OCI reaproveitar `ObjectContent.fromHttp`. |

## R4 — SFTP operacional

| ID | Problema | Correção |
|---|---|---|
| A9 | Um canal por operação, sem limite: o 11º stream simultâneo falha com `MaxSessions 10` do OpenSSH. Sem keepalive nem reconexão. | Pool pequeno de `SFTPClient` (ou semáforo abaixo de `MaxSessions`), um cliente por `put`/`copy`, keepalive e reconexão sob lock quando `!isConnected()`. |
| M10 | `checkAccess` sem override percorre a árvore inteira; o `list` do SFTP lê o sidecar de cada arquivo antes de filtrar o prefixo. | `checkAccess` com `stat(root)`; filtrar prefixo antes de `loadMetadata`; iniciar o walk no diretório mais profundo do prefixo. FS: `Files.isDirectory(root)`. |

## R5 — Spring

| ID | Problema | Correção |
|---|---|---|
| M1 | `ObjectResponses.java:67-77`: falha em `parseMediaType` depois do `read` vaza o stream e o permit do `StreamLimiter`. | Montar os cabeçalhos antes do `read`, ou try/catch que fecha o conteúdo e libera o permit. |
| M2 | Observação de `list` só termina com o stream fechado ou consumido; `list(p).findFirst()` deixa o LongTaskTimer ativo para sempre. | Documentar que o stream de `list` deve ser fechado (javadoc do core) ou observar só a primeira página. |
| B-Spring | `Error` registrado como `outcome=success`; `stopped` não atômico; spliterator mantém `SIZED`; `toString()` de `StorageProperties` expõe `secretKey`, `connectionString` e `password`; sem `ETag`/`If-Range` no `ObjectResponses`; Azure com `endpoint` sem `azure-identity` dá `NoClassDefFoundError`. | Capturar `Throwable`; `AtomicBoolean`; limpar `SIZED \| SUBSIZED`; mascarar segredos no `toString`; `ETag`, `Last-Modified` e `If-Range`; checar a classe com `ClassUtils.isPresent`. |

## R6 — Baixa severidade restante

- `BoundedInputStream.skip` ignora o limite da faixa (FS e SFTP).
- `ObjectContent.fromHttp`: `NumberFormatException` não capturada e stream não fechado.
- GCS: presign com credencial de usuário (ADC) lança `IllegalStateException`; `presignPut` não valida a generation.
- S3: erros por chave de `deleteAll` sem tipo; `abort`/`listParts` dependem de `NoSuchUploadException` (usar status 404).
- Azure: `abort()` não marca a sessão (`listParts` e `complete` continuam funcionando); `delete` com container inexistente passa em silêncio; `listParts` trata container ausente como lista vazia; leitura de sufixo pode lançar `PreconditionFailed`.
- OCI: `region()` calculado a cada `copy` e lança `IllegalStateException` com endpoint customizado.
- FS: chaves `a/../b`, `a//b` e `a/` normalizadas para outro objeto (usar a validação do SFTP); symlinks seguidos; lock cobre só `put`; `nio.AccessDeniedException` não mapeada; `open` em diretório não lança `ObjectNotFoundException`.
- FS e SFTP: diretórios vazios nunca removidos (`put("a")` falha depois de `delete("a/b")`); `list` falha se um arquivo some durante o walk; `complete` apaga as partes mesmo quando falha; SFTP com `root="/"` gera chaves erradas.
- Core: `DeleteResult` perde a ordem (`Map.copyOf`); README diz que o `deleteAll` default é paralelo, mas é sequencial.
- InMemory: `complete` com parte inexistente ou após `abort` dá NPE; `put` lança `UncheckedIOException`.

## R7 — Refatoração

| ID | Entrega |
|---|---|
| F1 | Extrair para o core: `BoundedInputStream` (idêntica no FS e no SFTP), validação de chave, codec do sidecar com `versionOf`. |
| F2 | Helpers de adapter no core: MD5 base64, regra "IfVersionMatches + 404", esqueleto de lote do `deleteAll`, `Condition` para cabeçalhos de presign, `closeQuietly`. |
| F3 | Ordenar as partes em `MultipartOutputStream` antes de `complete`, removendo a ordenação dos quatro adapters. |
| F4 | Avaliar inverter o default: `open(k, r) = read(k, r).stream()` aparece em quatro adapters. |
| F5 | OCI `listDirectory`: trocar o `HashSet` de pastas vistas pela comparação com a última pasta (como o core). |
| F6 | S3: `checksumOf` por switch em vez de reflexão; enxugar o enum `Checksum` (MD5, XXHASH*, SHA512 sem uso). |
| F7 | Build: versão do BOM derivada do parent ou checada no release; `junit-bom`; `micrometer-core` em escopo de teste no starter; fixar a tag do Azurite. |

## R8 — Lacunas de teste

| ID | Entrega |
|---|---|
| T1 | `ObjectStorageContract`: chaves com espaço, unicode, `+` e `%`; `ifNotExists` concorrente; `copy` sobre si mesmo e sobrescrevendo destino; metadata estrangeira no `head`; `presignPut` com condição; range com overflow; limpeza dos objetos criados. |
| T2 | Contrato opt-in contra bucket real para GCS e OCI, habilitado por variáveis de ambiente (os mocks esconderam C5 e M3). |
| T3 | Spring: teste com a `ObservationAutoConfiguration` real; MockMvc do `ObjectResponses` (Range malformado, multi-faixa, HEAD, content-type inválido liberando o permit). |
| T4 | Azure: `listCount` acima de 1000 no Azurite para exercitar paginação e deduplicação de pastas; testes de `copy` com FAILED, timeout e interrupção. |

## Revisões

- **04/10/2026 — R0 concluída.** C1–C6 entraram em `main`, um commit por item, cada um com um
  teste que reproduzia a falha antes da correção. Desvio: C6 não ganhou teste MockMvc, que exigiria
  `spring-webmvc`, `spring-test` e a API de Servlet como dependências de teste; o teste fixa a classe
  exata `InputStreamResource` do corpo, que é a condição que o Spring MVC verifica. Observado no build
  completo: `SftpContractTest.multipartMontaOObjetoCompleto` falhou uma vez com `SFTPException: Failure`
  numa parte em paralelo contra o atmoz/sftp em Docker e passou ao repetir; acompanhar em A9.
- **04/10/2026 — A2 decidido.** Na leitura, a metadata do provedor é devolvida como veio, mesmo fora
  das regras da lib (`goog-reserved-file-mtime`, `s3cmd-attrs`, maiúsculas no Azure). A validação
  continua só na escrita.
- **04/10/2026 — R1 concluída.** A1: o construtor de `ByteRange` corta a faixa no maior fim representável,
  único ponto por onde passam todos os chamadores; teste de contrato com `ByteRange.of(5, Long.MAX_VALUE)`.
  A2: a validação saiu do construtor de `ObjectMetadata` para `requireWritable()`, chamado por `PutOptions`
  e pelo `initiateMultipart` de cada adapter; teste de contrato garante a falha antes do envio. A3: as
  sessões multipart de S3, GCS, Azure e OCI usam o `translate` do adapter.
- **04/10/2026 — R2 concluída.** Nos dois adapters, dados e sidecar passam por um único `publish`
  (temporário dos dados, sidecar num temporário, renomeação dos dados e depois do sidecar), usado por
  `put`, `copy` e `complete`. O sidecar guarda tamanho e data de modificação do arquivo e é ignorado
  quando não bate; sidecars antigos, sem esses campos, valem como estão. No SFTP a granularidade da data
  é de 1 segundo: um arquivo trocado por fora com o mesmo tamanho no mesmo segundo não é detectado.
  M7 não tem teste rodando no Windows (o teste é só POSIX). M9 não tem teste: exigiria provocar uma
  falha de rename sem o destino existir. A falha intermitente de
  `SftpContractTest.multipartMontaOObjetoCompleto` era um bug: partes em paralelo criando o mesmo
  `.uploads/<id>` disputavam o `SFTPClient.mkdirs`. Corrigido com criação componente a componente e
  um teste de contrato que a reproduzia.
- **04/10/2026 — R3 concluída.** Uma branch por adapter. Desvios: M3 só mudou na OCI (um `head` de objeto
  inexistente faz agora um HeadBucket a mais); no S3 e no GCS o caso fica coberto por `checkAccess`. M4
  não foi aplicado ao GCS: `generationMatch` em objeto inexistente responde 412, então o 404 do `put`
  condicional chega quase só de bucket inexistente, mas sem contrato GCS isso não foi verificado. A11
  troca o `createFrom` sem buffer pelo com `bufferSize = min(length, 15 MiB)` e um stream que exige
  exatamente `length` bytes.
- **04/10/2026 — R4 concluída.** A9 virou a classe pública `SftpConnection`: semáforo justo de canais
  (8 por padrão, espera de até 60 s), reconexão via `Connector` quando o cliente não está mais conectado,
  e o starter expõe um bean `SftpConnection` no lugar do `SSHClient`, compartilhado entre os buckets, com
  `storage.sftp.keep-alive` (30s) e `storage.sftp.max-channels` (8). Mudança visível: quem injetava o
  `SSHClient` do starter passa a injetar `SftpConnection`; um `SSHClient` da aplicação continua aceito,
  sem reconexão. Limites: operações em voo na conexão que caiu falham (não há retry); a reconexão só
  acontece quando o sshj já marcou a conexão como desconectada, o que o keepalive acelera. M10: `checkAccess`
  é um `stat` do root, e `list` começa na pasta mais funda do prefixo; um prefixo com `..` ou nome
  reservado na parte de pasta passa a ser rejeitado como chave.
- **04/10/2026 — R5 concluída.** M2 ficou como documentação: encerrar a observação de `list` antes do
  fim ou do `close` mudaria o que o timer mede, e um stream não fechado já é vazamento no adapter. O
  `If-Range` entrou como método novo, `ObjectResponses.attachmentForRequest(storage, head, HttpHeaders,
  attachment, permit)`, e não como sobrecarga de `attachment`: com `null` no lugar do `Range`, a
  sobrecarga deixaria ambíguas chamadas que já existem. Todas as respostas passam a ter `ETag` e
  `Last-Modified`.
