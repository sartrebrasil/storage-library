# Métricas do starter

Referência de tudo que `storage-spring-boot-starter` expõe em métricas e tracing. O vocabulário abaixo
(nomes de meter, chaves de tag e valores de `operation` e `outcome`) é contrato:
`StorageMetricsContractTest` o fixa. Mudar qualquer item exige atualizar o teste e este documento.

## Quando existem

| Condição | Efeito |
|---|---|
| Há um `ObservationRegistry` no contexto (o Actuator cria um) | Todo `ObjectStorage` é decorado e emite `storage.operations`, `storage.multipart` e spans. |
| Há um `MeterRegistry` e o `micrometer-core` no classpath | As observações viram timers e `LongTaskTimer`s, e os meters `storage.bytes`, `storage.deleteAll.keys`, `storage.operations.ttfb` e `storage.multipart.parts` são registrados. |
| Há Micrometer Tracing | Cada observação vira também um span. |
| Sem `ObservationRegistry` | Nada é decorado. |
| Sem `micrometer-core` (só `micrometer-observation`) | As observações funcionam; os quatro meters acima não existem. |

O health check usa o `ObjectStorage` **sem** o decorador: os probes não geram métricas nem spans.
O decorador esconde o tipo concreto: `getBean(S3ObjectStorage.class)` não encontra o bean; injete `ObjectStorage`.

## Tags comuns

| Tag | Valores |
|---|---|
| `storage` | Nome do bean (`reportsObjectStorage`, `s3ObjectStorage`...). Um valor por bucket. |
| `provider` | `s3`, `gcs`, `azure`, `oci`, `sftp`, `filesystem` ou `unknown`. Deduzido da **classe do adapter**, então vale também para um adapter declarado pela aplicação. Implementação própria, `InMemoryObjectStorage` e mocks saem `unknown`. |

Todo meter `storage.*` leva as duas.

## Resumo dos meters

| Meter | Tipo | Tags (além de `storage` e `provider`) | Exige `micrometer-core` |
|---|---|---|---|
| `storage.operations` | Timer | `operation`, `outcome`, `error` | não |
| `storage.operations.active` | LongTaskTimer | `operation` | não |
| `storage.operations.ttfb` | Timer | `operation` (`open`, `read`) | sim |
| `storage.bytes` | DistributionSummary (`bytes`) | `operation`, `direction` | sim |
| `storage.deleteAll.keys` | Counter | `result` | sim |
| `storage.multipart` | Timer | `outcome`, `error` | não |
| `storage.multipart.active` | LongTaskTimer | nenhuma | não |
| `storage.multipart.parts` | Timer | `outcome` | sim |

Nomes no Prometheus seguem a convenção do Micrometer (`storage.operations` vira
`storage_operations_seconds_count`, `_sum`, `_max`; o `LongTaskTimer`, `storage_operations_active_seconds_*`;
o Counter, `storage_deleteAll_keys_total`). **Não foram testados contra um registry Prometheus real.**

## `storage.operations`: cada chamada ao storage

Timer, uma amostra por chamada. Também é o span `storage <operation>` (filho da observação corrente da
thread). Nenhum parâmetro de chamada (chave, prefixo) vira tag.

### Tag `operation`

| Valor | Origem | O que a duração cobre |
|---|---|---|
| `put` | `put` | A chamada inteira. |
| `head` | `head` | A chamada inteira. |
| `open` | `open` | **Até o fim do consumo** do stream: EOF, erro de leitura ou `close`. |
| `read` | `read` | Idem `open`. |
| `list` | `list` | Até o fim do consumo do stream (esgotar ou `close`). |
| `listDirectory` | `listDirectory` | Idem `list`. |
| `copy` | `copy` | A chamada inteira. |
| `delete` | `delete` | A chamada inteira. |
| `deleteAll` | `deleteAll` | A chamada inteira (lote). |
| `checkAccess` | `checkAccess` | A chamada inteira. |
| `getRetention`, `setRetention` | retenção por objeto | A chamada inteira. |
| `getLegalHold`, `setLegalHold` | legal hold | A chamada inteira. |
| `presignGet`, `presignPut` | URLs pré-assinadas | A chamada inteira; é só assinatura local, sem I/O. |
| `initiateMultipart` | `initiateMultipart` (as duas sobrecargas) | A chamada inteira. |
| `multipartComplete` | `MultipartSession.complete` | A chamada inteira. |
| `multipartAbort` | `MultipartSession.abort` | A chamada inteira; cada chamada conta, inclusive repetidas. |

Todo método do `ObjectStorage` é repassado ao adapter, inclusive os que têm default na interface
(`read`, `listDirectory`, `deleteAll`): os adapters os sobrescrevem com versões nativas.

### Tag `outcome`

| Valor | Quando | Span |
|---|---|---|
| `success` | Chamada ok. | normal |
| `not_found` | `ObjectNotFoundException`, **ou** `head` que devolve vazio. | normal |
| `unsupported` | `UnsupportedOperationException`: o provedor não tem o recurso (retenção e legal hold no OCI, filesystem e SFTP). | normal |
| `partial` | `deleteAll` em que ao menos uma chave falhou. A chamada não lança: a falha vem no `DeleteResult`. | normal |
| `retained` | `ObjectRetainedException` (mais específico que `access_denied`). | erro |
| `access_denied` | `AccessDeniedException`. | erro |
| `precondition_failed` | `PreconditionFailedException`. | erro |
| `too_large` | `ObjectTooLargeException`. | erro |
| `range_not_satisfiable` | `RangeNotSatisfiableException`. | erro |
| `error` | Qualquer outra falha. | erro |

Pontos de atenção:

- `getRetention` devolvendo vazio significa "sem retenção" e sai como `success`; objeto inexistente lança e sai `not_found`.
- Uma exceção lançada por **quem consome** um stream (`list`, `open`) não vira `outcome=error`: é bug do consumidor, não do storage.
- No OCI, um `head` que dá 404 chama `checkAccess()` internamente; só a primeira chamada aparece na métrica.

### Tag `error`

Nome simples da classe da exceção (`StorageException`, `AccessDeniedException`...), ou `none` em `success`,
`not_found`, `unsupported` e `partial`.

## `storage.operations.active`

`LongTaskTimer` mantido pelo handler de métricas do Boot enquanto uma observação está aberta. Tags
`operation`, `storage` e `provider`; **não** tem `outcome` nem `error`, que só existem no fim.

É o sinal de stream não fechado: `open`, `read`, `list` e `listDirectory` que ninguém fechou nem esgotou
nunca saem dele, e a duração da mais antiga só cresce. O starter não fecha o stream por você: a observação
vaza até o processo reiniciar. Alerta sugerido (ajuste o limite ao maior download legítimo):

```promql
storage_operations_active_seconds_max{operation=~"open|read|list|listDirectory"} > 600
```

## `storage.operations.ttfb`

Timer em `open` e `read`: o tempo até o adapter **devolver** o stream, só nas aberturas bem-sucedidas (falhas
já estão em `storage.operations`). Separa a lentidão do storage da do cliente, que está embutida na duração
de `storage.operations`. O que isso cobre depende do adapter: no S3, vai até os cabeçalhos da resposta.

## `storage.bytes`

`DistributionSummary` em bytes (`count`, `sum`, `max`).

| `operation` | `direction` | O que registra |
|---|---|---|
| `put` | `upload` | O `length` declarado, só se o `put` concluiu. |
| `open`, `read` | `download` | Os bytes lidos até o encerramento (EOF, erro ou `close`); um stream fechado antes do fim conta só o lido. |
| `uploadPart` | `upload` | O tamanho de cada parte multipart enviada com sucesso. Um retry de parte conta de novo. |

Não entram: `copy`, `presignPut` e o upload de URL pré-assinada (o tamanho não passa pelo decorador), e
`skip` num stream de leitura.

## `storage.deleteAll.keys`

`Counter` com a tag `result`: `ok` (chaves apagadas ou inexistentes) e `failed`. Só registra valores maiores
que zero, então `ok` pode não existir num lote totalmente falho. Complementa o `outcome=partial` de
`storage.operations`.

## Multipart

O ciclo de vida de um upload (`initiateMultipart` até `complete` ou `abort`) tem observação própria.

| Meter | Conteúdo |
|---|---|
| `storage.multipart` (Timer; span `storage multipart`) | Duração do upload. `outcome`: `completed`, `aborted`, ou o `outcome` da falha do `complete` (ex.: `precondition_failed`, `error`). `error`: classe da exceção ou `none`. |
| `storage.multipart.active` (LongTaskTimer) | Uploads abertos. Um que nunca foi concluído nem abortado (partes órfãs, cobradas pelo storage) fica aqui. |
| `storage.multipart.parts` (Timer; `outcome=success\|error`) | Cada tentativa de `uploadPart`: `count` é o número de partes e a duração é a de cada uma. |
| `storage.bytes` com `operation=uploadPart` | Bytes das partes enviadas com sucesso. |
| `storage.operations` com `multipartComplete` e `multipartAbort` | Latência das chamadas finais. |

Regras:

- Só o **primeiro** desfecho conta em `storage.multipart`: um `abort` depois de um `complete` falho (o que o
  `MultipartOutputStream` faz) não troca o desfecho `error` por `aborted`.
- Não há observação por parte, porque podem ser 10 mil: cada `uploadPart` vira uma amostra em
  `storage.multipart.parts`, sem span.
- `listParts` e `listCompletedParts` são repassados sem medição.

Upload órfão: `storage_multipart_active_seconds_max > 3600`.

## O que não é medido

- Retries e throttling do SDK (um 503 absorvido pelo SDK aparece só como latência alta).
- Estado de conexão do SFTP (conexões abertas, reconexões).
- Concorrência e fila do `ParallelPartUploader`.
- Health check (`/actuator/health`): expõe UP/DOWN, não métrica.
- O tamanho de `copy` e de `presignPut`.

## Configuração do consumidor

Percentis e SLOs são configuração do consumidor, sem código:

```properties
management.metrics.distribution.percentiles-histogram.storage.operations=true
management.metrics.distribution.slo.storage.operations=100ms,500ms,2s
```

Em outra thread (`@Async`, executor próprio), o span só continua o trace se o executor propagar o contexto
(`ContextExecutorService.wrap`, do context-propagation).

## Consultas úteis (PromQL)

```promql
# Taxa de erro por bucket (not_found, unsupported e partial não contam como falha)
sum by (storage) (rate(storage_operations_seconds_count{outcome!~"success|not_found|unsupported|partial"}[5m]))
  / sum by (storage) (rate(storage_operations_seconds_count[5m]))

# Taxa de erro por provider
sum by (provider) (rate(storage_operations_seconds_count{outcome!~"success|not_found|unsupported|partial"}[5m]))

# p95 por operação (precisa de percentiles-histogram)
histogram_quantile(0.95, sum by (le, operation) (rate(storage_operations_seconds_bucket[5m])))

# Vazão por bucket e direção, em bytes/s
sum by (storage, direction) (rate(storage_bytes_sum[5m]))

# Lentidão do storage, sem o cliente lento: média do ttfb
sum by (storage) (rate(storage_operations_ttfb_seconds_sum[5m]))
  / sum by (storage) (rate(storage_operations_ttfb_seconds_count[5m]))

# Taxa de falha de chaves em deleteAll
sum(rate(storage_deleteAll_keys_total{result="failed"}[5m])) / sum(rate(storage_deleteAll_keys_total[5m]))

# Acesso negado ou retenção bloqueando escritas
sum by (storage, outcome) (rate(storage_operations_seconds_count{outcome=~"access_denied|retained"}[5m]))

# Uploads multipart que terminaram abortados ou com erro, por bucket
sum by (storage, outcome) (rate(storage_multipart_seconds_count{outcome!="completed"}[5m]))
```
