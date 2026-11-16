# Clientes nativos fornecidos pela aplicação: design

Data: 2026-11-15. Repositórios: `storage-library` (o starter) e `engine-tax` (o primeiro consumidor).
Precedente: ADR-0011 da messaging-library (`docs/adr/0011-clientes-nativos-da-aplicacao.md`), que fez o mesmo no starter dela.

## Intenção e porquê

Hoje o `storage-spring-boot-starter` cria os clientes nativos de cada provedor a partir de `storage.*`: o
`AwsCredentialsProvider`, o `S3Client` e o `S3Presigner` (S3); o `HttpStorageOptions`, o `Storage` e o
`MultipartUploadClient` (GCS); o `BlobServiceClient` com `DefaultAzureCredential` (Azure); o `ObjectStorageClient` com
`ConfigFileAuthenticationDetailsProvider` (OCI); o `SSHClient` com host key, keepalive e autenticação (SFTP). A aplicação
já pode sobrescrever (`@ConditionalOnMissingBean`), então há dois donos para a mesma conexão e uma tabela de
propriedades por provedor só para configurá-los.

O pedido do usuário (2026-11-15): o starter não cria mais credenciais, clientes nem presigner, em nenhum dos quatro
provedores com cliente (S3, GCS, Azure, OCI) nem no SFTP; a aplicação os cria e os declara, "igual ao messaging". Região,
endpoint, credencial e timeout são infraestrutura de runtime da aplicação, como os buckets já são do Terraform. O
engine-tax, que usa só o S3 e só `path-style` em `storage.s3`, ganha uma configuração própria dos clientes. E os exemplos
de como criar os componentes de cada provedor ficam guardados num lugar só, como referência.

## Objetivos, não objetivos e anti-objetivos

**Objetivos**
- O starter nunca cria nem fecha credencial, cliente ou presigner. Ele continua montando o `ObjectStorage` de cada bucket
  (`storage.bucket`, `storage.buckets`) sobre o objeto raiz que a aplicação declara, mais health e métricas.
- Uma regra única para os cinco provedores com conexão: bean do tipo, `@Primary` desempata, bean faltando falha o
  startup com uma mensagem que diz qual declarar.
- Um exemplo compilável e testado por provedor, num lugar só, mais um trecho no README.
- O engine-tax declara `S3Client` e `S3Presigner` num `StorageConfig` compartilhado pelos quatro módulos que usam S3.

**Não objetivos**
- Não mexer nos adapters (`storage-aws`, `-gcp`, `-azure`, `-oci`, `-sftp`, `-filesystem`) nem na API `ObjectStorage`.
  Eles já recebem os clientes prontos.
- Não criar buckets, contêineres nem permissões (Terraform).
- O `filesystem` não muda: não tem cliente.
- Vários clientes do mesmo tipo (duas contas) continuam fora do starter; quem precisa declara um `ObjectStorage` por
  bucket, como o README já diz.

**Anti-objetivos**
- Nenhuma configuração antiga pode ser ignorada em silêncio: `region`, `endpoint` e `access-key` descartados fariam uma
  aplicação falar com a AWS real em vez do LocalStack. Chave removida derruba o startup.
- O starter não pode fechar nada que a aplicação declarou.

## Restrições

- Java 21. Os adapters continuam sem Spring; as dependências dos SDKs no starter continuam `optional`.
- Mensagens de erro do starter em português, com a chave ou o tipo completo.
- No engine-tax, o `CLAUDE.md` vale: trabalho novo entra em `docs/tasks.md` antes de começar, e só se commita com o
  "pode commitar" do usuário. O pin da `storage-library` em `ops/install-libraries.sh` precisa subir depois.

## Abordagens

**Onde ficam os exemplos de referência.** (A, escolhida) Uma `@Configuration` por provedor em `src/test` do starter, usada
pelos próprios testes de auto-configuração, com o README apontando para elas e mostrando o S3 inline. Compilam e rodam,
então não envelhecem; o custo é ficarem em código de teste. (B) Só trechos no README: simples, mas ninguém avisa quando
quebram. (C) Um módulo `storage-examples` no build: o mais visível, mas pesa o build.

**Onde fica o `StorageConfig` do engine-tax.** (B, escolhida) Um módulo compartilhado `platform/storage-support` com uma
classe `StorageConfig`, importada por `@Import` nos quatro módulos, como o `KeyZoneConfiguration` já é. Evita repetir o
path-style do presigner quatro vezes. (A) Um `StorageConfig` por módulo: cópias pequenas, sem módulo novo, mas com risco
de divergir.

**Descartada:** tirar o starter e deixar a aplicação montar o `ObjectStorage`. Perde `storage.buckets`, o health e o
decorador de métricas, que são o valor do starter.

## Design

### Objetos raiz por provedor

A aplicação declara o objeto raiz; o starter deriva o resto. A granularidade segue o que os adapters recebem.

| `storage.provider` | A aplicação declara | O starter deriva |
|---|---|---|
| `s3` | `S3Client` e `S3Presigner` | um `S3ObjectStorage` por bucket (`checksum` do adapter) |
| `gcs` | `Storage` e `MultipartUploadClient` | um `GcsObjectStorage` por bucket |
| `azure` | `BlobServiceClient` | um `BlobContainerClient` e um `AzureBlobObjectStorage` por container |
| `oci` | `com.oracle.bmc.objectstorage.ObjectStorage` | um `OciObjectStorage` por bucket (namespace) |
| `sftp` | `SftpConnection` | um `SftpObjectStorage` por subpasta |
| `filesystem` | nada | um `FileSystemObjectStorage` por subpasta |

- No Azure, a aplicação declara só o `BlobServiceClient`: o starter deriva os `BlobContainerClient` por dentro e deixa de
  publicar um bean `BlobContainerClient` e de aceitar um da aplicação. Quem tinha um recebe, no startup, a mensagem do
  `BlobServiceClient` faltando, que cita isso, e a seção "Migrando" repete.
- O bean é procurado pelo tipo; com mais de um, vale o `@Primary`. Sem o bean, o startup falha dizendo o provedor e o tipo
  (por exemplo, `storage.provider=s3: declare um bean software.amazon.awssdk.services.s3.S3Client`). Os beans são
  resolvidos quando o starter cria o primeiro `ObjectStorage` do provedor.
- As credenciais e o `AwsCredentialsProvider` deixam de existir no starter: ficam dentro do `S3Client` e do `S3Presigner`
  que a aplicação monta. O `S3Presigner` não herda endpoint nem path-style do `S3Client`; os dois precisam ser montados
  com a mesma configuração, e o exemplo do S3 mostra isso.
- No SFTP, o `SSHClient` da aplicação deixa de ser aceito no lugar do `SftpConnection`. A aplicação declara o
  `SftpConnection`, criado com `SftpConnection.reconnecting(...)` ou `SftpConnection.of(sshClient)`. Verificação de host
  key, keepalive e autenticação vão para o exemplo.
- O starter nunca fecha o que a aplicação declarou. O `destroyMethod = "close"` do `SftpConnection` passa à aplicação.

### Propriedades

Ficam: `storage.provider`, `storage.bucket`, `storage.buckets`, `storage.s3.checksum` (opção do adapter),
`storage.oci.namespace`, `storage.sftp.root`, `storage.filesystem.root`.

Saem, e cada uma derruba o startup com uma mensagem de migração (todas juntas, numa só): `storage.s3.region`, `endpoint`,
`path-style`, `access-key`, `secret-key`, `async-credential-update`; `storage.gcs.project-id`; `storage.azure.connection-string`
e `endpoint`; `storage.oci.config-file` e `profile`; `storage.sftp.host`, `port`, `username`, `password`,
`private-key-path`, `known-hosts`, `insecure-trust-all-hosts`, `keep-alive` e `max-channels`. Falham sempre, mesmo com
`storage.provider` vazio ou com um `ObjectStorage` próprio. O Spring Boot ignora chave desconhecida por padrão, então a
detecção é código novo, como no messaging.

**Nova: `storage.azure.sas`** (`shared-key` ou `user-delegation`). Hoje o tipo da SAS segue a autenticação (connection
string implica chave da conta). Sem a connection string no starter, ele não tem como saber. É obrigatória quando o starter
cria o `ObjectStorage` do Azure (não quando a aplicação declara o seu), sem padrão, para a escolha ser explícita (um padrão errado só apareceria na hora de assinar uma URL).

### Exemplos de referência

Em `storage-spring-boot-starter/src/test/java/com/example/storage/spring/exemplos/`, uma classe por provedor
(`S3ClientsExample`, `GcsClientsExample`, `AzureClientsExample`, `OciClientsExample`, `SftpClientsExample`), cada uma com o
jeito de produção (cadeia padrão de credenciais, identidade gerenciada, `known_hosts`) e a variante de emulador onde
existir: LocalStack ou MinIO no S3 e Azurite no Azure. O GCS e o OCI não têm emulador nos testes de hoje, então os
exemplos deles só têm a forma de produção, ligada num contexto de teste com credencial nula, sem rede. Os testes de
auto-configuração usam as classes, então o que está no README compila e, nos contextos de teste, sobe. O README ganha a seção "Clientes nativos por provedor", com a tabela acima, o S3 inline e links
para as demais, e a seção "Migrando" com cada chave removida e o que a substitui. `docs/plan.md` ganha a linha da decisão.

### engine-tax

- Novo módulo `platform/storage-support` com a classe pública `StorageConfig` (`@Configuration(proxyBeanMethods = false)`),
  condicionada a `storage.provider=s3` para os testes de API que desligam o storage (`storage.provider=`) continuarem
  sem criar cliente. Declara `S3Client` e `S3Presigner` com região, endpoint e credenciais da cadeia padrão do SDK
  (`AWS_REGION`, `AWS_ENDPOINT_URL_S3`, `AWS_*`), e path-style vindo de uma propriedade do próprio engine-tax. Não define
  endpoint: conferi que o cliente e o presigner do SDK respeitam `AWS_ENDPOINT_URL_S3` sozinhos (rodado fora do
  repositório), que é como o engine-tax os usa hoje. Mantém o que o starter fazia com as credenciais: um só
  `AwsCredentialsProvider` da cadeia padrão com renovação em segundo plano (`asyncCredentialUpdateEnabled`), compartilhado
  pelo cliente e pelo presigner e fechado pelo Spring.
- `registry-api`, `signer-api`, `storage-api` e `validation-api` ganham a dependência e um `@Import(StorageConfig.class)`.
- Nos quatro `application.yaml`, `storage.s3.path-style: ${STORAGE_PATH_STYLE:false}` vira a propriedade do engine-tax
  com o mesmo valor (a variável de ambiente `STORAGE_PATH_STYLE` não muda, então `k8s`, `devspace` e `local.env`
  ficam como estão).
- Os testes do engine-tax que desligam o storage (`storage.provider=`) seguem como estão. O `StorageApiTest`, que usa
  `storage.s3.path-style=true` e dois buckets, passa à propriedade nova e ao `StorageConfig`.
- A tarefa entra em `docs/tasks.md` antes de começar, e o pin da `storage-library` em `ops/install-libraries.sh` sobe
  depois, no mesmo estilo da T-172.

### Fases e fluxo

Duas fases, com uma parada entre elas. Fase 1, no `storage-library`: numa branch (`feat/clientes-nativos-da-aplicacao`),
starter, exemplos, README e testes. Fase 2, no `engine-tax`: tarefa em `docs/tasks.md`, `platform/storage-support`, os
quatro módulos e os testes, tudo sem commitar. A fase 2 se verifica antes de qualquer push: o `install-libraries.sh` aceita
`LIBRARIES_DIR` com clones já existentes, então a cadeia é instalada, num repositório Maven isolado, a partir de um clone
local da branch. O merge em `main` e o push do `storage-library` dependem de o usuário pedir, como no messaging. Só depois
do push o pin do `storage-library` no `install-libraries.sh` aponta para o commit publicado.

### Verificação

- Starter: os testes de auto-configuração passam a declarar os clientes pelas classes de exemplo; novos testes para o bean
  faltando em cada provedor, `@Primary`, cada chave removida e `storage.azure.sas` ausente. Os adapters seguem verdes.
- Teste de regressão no starter, como o do messaging (`ApplicationContextRunner`): uma aplicação com `S3Client` e
  `S3Presigner` próprios usa os dois, e com `storage.provider` vazio nenhum cliente é criado. No engine-tax, um teste do
  `StorageConfig` em `platform/storage-support` prova a condição `storage.provider=s3` e os dois beans.
- engine-tax: `mvn test` nos quatro módulos mais o novo, contra um repositório Maven isolado, com a cadeia de bibliotecas
  instalada pelo script, sem tocar no `~/.m2`.

## O que fica para quem constrói

- Os textos exatos das mensagens, os nomes dos arquivos de exemplo e como detectar as chaves removidas.
- O nome da propriedade do engine-tax para o path-style.
- Se o `StorageConfig` expõe também um `S3Client` sem checksum flexível para backends antigos (o `checksum: none` hoje
  configura o cliente e o adapter; só a parte do adapter fica no starter).
