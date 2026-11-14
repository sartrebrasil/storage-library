# Plano: evolução das métricas (`storage.operations`)

- **Status:** Fases 0 a 7 e 9 concluídas; Fase 8 adiada (só com dor real)
- **Criado em:** 13/11/2026
- **Origem:** revisão das métricas do starter (`ObjectStorageMetrics`, `StorageMetricsAutoConfiguration`,
  health indicator e README). Os adapters não foram lidos: as lacunas marcadas "a confirmar" são
  inferidas do decorador e precisam da Fase 0.

## Objetivo

Ampliar o que `storage.operations` expõe (desfechos, bytes, falha parcial, streams abandonados,
multipart, provider) sem quebrar os nomes de meter e tags que já existem.

## Estado atual

Um único ponto de instrumentação: o decorador `ObjectStorageMetrics` abre uma `Observation`
`storage.operations` por chamada, com tags `operation`, `storage` (nome do bean), `outcome`
(`success`, `not_found`, `error`) e `error` (classe da exceção, ou `none`). Vira timer com
`MeterRegistry` e span com Micrometer Tracing. `open`, `read`, `list` e `listDirectory` medem até o
fim do consumo. Fora de escopo hoje: `MultipartSession.uploadPart` (e `complete`/`abort`).

## Lacunas

1. Sem métrica de bytes transferidos.
2. `open` e `read` medem o consumo inteiro: um cliente lento parece lentidão do storage.
3. Stream abandonado nunca encerra a observação, sem nenhum sinal.
4. `head` devolve `Optional`: `not_found` nunca é registrado (confirmado nos 7 adapters).
5. Multipart cego: parts, aborts, tamanho e duração.
6. Sem retry/throttling do SDK (a confirmar por adapter).
7. `storage` é o nome do bean, não o tipo de provider.
8. `deleteAll` com falha parcial sai como `success`.
9. Nenhum teste fixa os nomes de meter, tags e valores de `outcome`.

## Fase 0: confirmar premissas ✔ (13/11/2026)
- [x] **T0.1** Lacuna 4 **confirmada só para `head`**: os 7 adapters (S3, GCS, Azure, OCI, SFTP,
  FileSystem, InMemory) devolvem `Optional.empty()` para objeto inexistente, então `head` nunca
  registra `not_found`. **`getRetention` é diferente:** `Optional.empty()` quer dizer "sem retenção";
  objeto inexistente lança `ObjectNotFoundException` (S3, e o contrato do `ObjectStorage`). Logo
  `getRetention` já mede `not_found` e **não** recebe a tag `found` (T1.2 corrigida abaixo).
  Extra: o `head` do OCI chama `checkAccess()` num 404, ou seja, um miss custa 2 requisições e só a
  primeira aparece na métrica.
- [x] **T0.2** Hierarquia: `AccessDeniedException` ← `ObjectRetainedException`; `StorageException`
  ← `ObjectNotFound`, `ObjectTooLarge`, `PreconditionFailed`, `RangeNotSatisfiable`. O mapeamento do
  `outcome` precisa testar `ObjectRetained` **antes** de `AccessDenied`. Novo: OCI, filesystem e SFTP
  lançam `UnsupportedOperationException` em retenção/legal hold/presign com override; hoje vira
  `outcome=error` e marca o span como erro. Candidato a `outcome=unsupported` (entra em T1.1).
- [x] **T0.3** `StorageMetricsAutoConfigurationTest` passa (`mvn -o -pl storage-spring-boot-starter -am`).

## Fase 1: ganhos baratos (sem meter novo)
- [x] **T1.1** `outcome` refinado em `ObjectStorageMetrics.stop`: `retained` (testado antes de
  `access_denied`), `access_denied`, `precondition_failed`, `too_large`, `range_not_satisfiable`,
  `unsupported` (não marca o span como erro, como `not_found`), `error` para o resto. Teste
  `cadaExcecaoTipadaTemSeuOutcome` cobre os 7.
- [x] **T1.2** Mudou de desenho: em vez da tag `found` em `head`, um `head` vazio vira
  `outcome=not_found`. O motivo é que o Prometheus rejeita meters do mesmo nome com conjuntos de tags
  diferentes, e `found` só existiria em `head`. **Muda o comportamento:** quem alertava ou somava
  `outcome="success"` em `head` passa a ver os misses em `not_found`. Teste
  `headDeObjetoInexistenteRegistraNotFoundEExistenteSuccess`.
- [x] **T1.3** Javadoc de `ObjectStorageMetrics` e tabela de `outcome` no README.

## Fase 2: volume de dados
- [x] **T2.1** `storage.bytes` (`DistributionSummary`, `bytes`; tags `operation`, `storage`,
  `direction`). `put` registra `length` após o sucesso; `open`/`read` somam os bytes lidos no
  `ObservedInputStream` e registram no encerramento (fim, erro ou `close`). `copy` ficou fora (o
  decorador não sabe o tamanho), assim como `presignPut` e multipart. `skip` não conta.
  Desenho: `micrometer-core` virou dependência **opcional de compile** (era só de teste); o
  decorador fala com a interface `ByteRecorder` e `MeterByteRecorder` (o único que toca o
  `MeterRegistry`) só entra num `@ConditionalOnClass(MeterRegistry.class)`.
- [x] **T2.2** Testes: put + open + read de N bytes; stream fechado após 3 de 10 bytes; `put` com
  falha não registra; sem `micrometer-core` no classpath (`FilteredClassLoader`) o decorador funciona e
  não há `ByteRecorder`.
- [x] **T2.3** README.

## Fase 3: falha parcial em lote
- [x] **T3.1** `Counter` `storage.deleteAll.keys` (tags `storage`, `result=ok|failed`), a partir do
  `DeleteResult`; só registra o que for > 0. Testes: lote parcial e lote totalmente falho. A
  interface `ByteRecorder` virou `ExtraMeters` (`bytes` e `deletedKeys`) e `MeterByteRecorder`,
  `MeterExtraMeters`.
- [x] **T3.2** Decisão: `deleteAll` com falha parcial vira `outcome=partial` (novo valor, span sem
  erro, porque a chamada não lançou). `success` seria enganoso para SLO; `error` dispararia alertas
  de falha total. `timed` agora recebe uma função que dá o outcome de um retorno normal.

## Fase 4: stream abandonado
- [x] **T4.1** Decisão: `LongTaskTimer`, **sem código novo**. O `DefaultMeterObservationHandler`
  (confirmado com `javap` no `micrometer-core` 1.17.1) já registra `storage.operations.active` com
  as tags de baixa cardinalidade do início (`operation`, `storage`) enquanto a observação está
  aberta. O `Cleaner` ficou de fora: só adicionaria o fechamento do vazamento, ao custo de
  referência fantasma por stream e de um `outcome` novo (`abandoned`); reabrir se o vazamento
  doer.
- [x] **T4.2** Teste `streamAbertoENaoFechadoFicaVisivelEmOperationsActive`: aberto = 1 tarefa
  ativa, fechado = 0. README com o alerta sugerido (`..._active_seconds_max`) e o aviso de que a
  observação vaza até reiniciar. Javadoc ajustado.

## Fase 5: TTFB
- [x] **T5.1** `Timer` `storage.operations.ttfb` (`operation`, `storage`) em `open` e `read`: mede
  a chamada ao adapter até devolver o stream, só no sucesso (falhas já estão em `storage.operations`).
  `ExtraMeters.ttfb`; `storage.operations` segue como duração total. O "primeiro byte" real depende do
  adapter (S3: cabeçalhos); não li os outros para dizer o que cada um cobre.
- [x] **T5.2** `ttfbSeparaAAberturaDoConsumoLento`: consumo atrasado 100 ms, `ttfb` < 50 ms e total
  ≥ 100 ms; abertura de objeto inexistente não conta.

## Fase 6: multipart (decidir antes)
- [x] **T6.1** Escopo completo, escolhido em 13/11/2026: decorar `MultipartSession` (parts,
  `complete`, `abort`).
- [x] **T6.2** Desenho diferente do esboço: em vez de um `storage.multipart.outcome` (que repetiria
  `storage.operations`), uma observação `storage.multipart` por upload, aberta no `initiateMultipart` e
  encerrada no `complete` (`completed` ou o outcome da falha) ou `abort` (`aborted`), o que dá a duração
  do upload e o `LongTaskTimer` `storage.multipart.active` (upload órfão). Partes: `Timer`
  `storage.multipart.parts` (`outcome` success/error) e `storage.bytes` com `operation=uploadPart`; sem
  observação por parte (até 10 mil). `complete`/`abort` entram em `storage.operations`
  (`multipartComplete`, `multipartAbort`); `listParts`/`listCompletedParts` só repassados.
- [x] **T6.3** Testes: upload completo (partes, bytes, ativo 1→0), abortado duas vezes (idempotente),
  falha de parte e de `complete` seguida de `abort` (o desfecho não é trocado) e upload nunca
  concluído (fica em `active`).

## Fase 7: provider
- [x] **T7.1** Tag `provider` em todo meter `storage.*` (operações, bytes, ttfb, deleteAll, multipart).
  Mudou de fonte: em vez do `Provider` de `StorageProperties`, deduzida da **classe do adapter**
  (`StorageId.of`), por nome simples, sem referenciar os adapters (dependências opcionais). Assim vale
  também para beans da aplicação, e os valores seguem `s3`, `gcs`, `azure`, `oci`, `sftp`,
  `filesystem`; o resto é `unknown`. `ExtraMeters` passou a receber um `StorageId` (nome + provider).
- [x] **T7.2** Testes: todo meter de um fluxo com put/open/deleteAll/multipart tem `provider`; mapeamento
  por classe (s3, filesystem, desconhecido).

## Fase 8: adiado (só com dor real)
- [ ] **T8.1** Retries e throttling do SDK (`storage.retries`, `storage.throttled`); começar pelo S3.
- [ ] **T8.2** SFTP: conexões abertas, tempo de connect, reconexões.
- [ ] **T8.3** `ParallelPartUploader`: concorrência e fila.

## Fase 9: guarda de regressão
- [x] **T9.1** `StorageMetricsContractTest`: um fluxo que exercita as 19 `operation`, os 10 `outcome`
  e o multipart (completed/aborted/error) e compara com o esperado: o conjunto de meters `storage.*` e as
  chaves de tag de cada um, os valores de `operation`, `outcome`, `direction`, `result` e `provider`.
  Verificado por mutação: renomear `multipartAbort` no decorador quebra o teste.
- [x] **T9.2** Consultas PromQL no README (taxa de erro por bucket, p95, vazão, `ttfb` médio, acesso
  negado/retenção). Os nomes do Prometheus seguem a convenção do Micrometer e **não** foram testados num
  registry Prometheus.

## Ordem

T0 → T1 → T2 → T3 → T4. Fases 5 a 7 só depois de ver o uso real de 1 a 4. A fase 8 fica no backlog.
