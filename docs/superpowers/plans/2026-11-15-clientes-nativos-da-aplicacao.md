# Clientes nativos da aplicação: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** O `storage-spring-boot-starter` deixa de criar credenciais, clientes e presigner nos provedores com conexão (S3, GCS, Azure, OCI, SFTP); a aplicação declara o objeto raiz de cada um. O engine-tax ganha um `StorageConfig` compartilhado, e os exemplos de cada provedor ficam guardados como referência.

**Architecture:** Um helper `StorageClientBeans.require` resolve o bean do tipo (`@Primary` desempata) e falha com a mensagem do provedor. Cada `*StorageConfiguration` do `StorageAutoConfiguration` perde os `@Bean` de cliente e passa a resolver os beans dentro do `BucketStorageFactory`, na criação do primeiro `ObjectStorage`. As propriedades de conexão saem de `StorageProperties`, e uma verificação própria derruba o startup com mensagem de migração. No engine-tax, um módulo `platform/storage-support` com `StorageConfig` é importado pelos quatro módulos que usam S3.

**Tech Stack:** Java 21, Spring Boot (starter do storage-library, `ApplicationContextRunner`), AWS SDK v2, Azure Blob, Google Cloud Storage, OCI SDK, SSHJ, JUnit 5, AssertJ, Mockito, Testcontainers. Maven multi-módulo nos dois repositórios.

**Spec:** [`docs/superpowers/specs/2026-11-15-clientes-nativos-da-aplicacao-design.md`](../specs/2026-11-15-clientes-nativos-da-aplicacao-design.md). Precedente: `messaging-library/docs/adr/0011-clientes-nativos-da-aplicacao.md`.

## Global Constraints

- Os adapters (`storage-aws`, `-gcp`, `-azure`, `-oci`, `-sftp`, `-filesystem`) e a API `ObjectStorage` não mudam. Só o starter, a documentação e o engine-tax.
- O starter nunca cria nem fecha credencial, cliente ou presigner. As dependências dos SDKs no starter continuam `optional`.
- Mensagens de erro em português, com a chave ou o tipo completo. Falha de bean: `storage.provider=<provedor em minúsculas>: declare um bean <nome completo do tipo>`; vários beans sem `@Primary`: `storage.provider=<provedor>: há mais de um bean <tipo>; marque um com @Primary`.
- Objetos raiz: `s3` = `S3Client` + `S3Presigner`; `gcs` = `Storage` + `MultipartUploadClient`; `azure` = `BlobServiceClient`; `oci` = `com.oracle.bmc.objectstorage.ObjectStorage`; `sftp` = `SftpConnection`; `filesystem` = nada.
- Propriedades que ficam: `storage.provider`, `storage.bucket`, `storage.buckets`, `storage.s3.checksum`, `storage.oci.namespace`, `storage.sftp.root`, `storage.filesystem.root`. Nova: `storage.azure.sas` (`shared-key` ou `user-delegation`), obrigatória só quando o starter cria o `ObjectStorage` do Azure.
- Chaves removidas (derrubam o startup, sempre, mesmo com `storage.provider` vazio ou um `ObjectStorage` próprio): `storage.s3.{region,endpoint,path-style,access-key,secret-key,async-credential-update}`, `storage.gcs.*`, `storage.azure.{connection-string,endpoint}`, `storage.oci.{config-file,profile}`, `storage.sftp.{host,port,username,password,private-key-path,known-hosts,insecure-trust-all-hosts,keep-alive,max-channels}`. Todas numa mensagem só, `Configuração inválida de storage.*:` seguida de uma linha ` - <chave> foi removida: configure <o que declarar> na aplicação (veja "Migrando" no README)`.
- Exemplos: um por provedor em `storage-spring-boot-starter/src/test/java/com/example/storage/spring/exemplos/` (`S3ClientsExample`, `GcsClientsExample`, `AzureClientsExample`, `OciClientsExample`, `SftpClientsExample`). Cada um tem métodos estáticos que montam os objetos raiz (a credencial entra como argumento, para o teste passar uma nula) e uma `@Configuration` interna `Producao` que os chama com a cadeia padrão de credenciais. S3 e Azure têm também uma `@Configuration` interna `Emulador`. Os testes do starter usam essas classes.
- Comando de teste do starter, a partir da raiz do `storage-library`: `mvn -q -pl storage-spring-boot-starter -am test -Dtest='com.example.storage.spring.**.*Test' -Dsurefire.failIfNoSpecifiedTests=false`. O `SftpStorageAutoConfigurationTest` exige Docker.
- storage-library: commits em Conventional Commits, na branch `feat/clientes-nativos-da-aplicacao`, um por tarefa. engine-tax: **nenhum commit** (o `CLAUDE.md` pede o "pode commitar" do usuário); mensagens de commit futuras em inglês. Não incluir `.DS_Store`.
- Sem merge em `main`, sem push e sem mexer no pin do `storage-library` em `ops/install-libraries.sh` do engine-tax: isso espera o usuário pedir.

## Review Focus

1. **`ObjectStorage` da aplicação com `storage.provider=s3` e sem clientes:** não pode falhar (hoje não falha), porque o starter só resolve os clientes ao criar o `ObjectStorage` dele. Teste na Task 3.
2. **Chave antiga com `storage.provider` vazio:** `storage.s3.region` sozinha derruba o startup. Teste na Task 8, também na forma de variável de ambiente (`STORAGE_S3_REGION`).
3. **Dois `S3Client` sem `@Primary`:** falha com a mensagem do `@Primary`; com `@Primary`, vale o primário. Task 2 (helper) e Task 3 (contexto).
4. **Presigner e cliente com configuração diferente:** o starter não detecta; o exemplo do S3 monta os dois com o mesmo path-style e um teste prova a URL pré-assinada em path-style. Task 3.
5. **Testes de API do engine-tax com `storage.provider=` vazio:** o `StorageConfig` não pode criar cliente (sem região o SDK falha). Teste na Task 11, e os testes existentes na Task 12.

---

## Parte 1: storage-library

### Task 1: Commitar o design e o plano

**Files:**
- Create (já escritos, ainda sem commit): `docs/superpowers/specs/2026-11-15-clientes-nativos-da-aplicacao-design.md`, este plano

- [ ] **Step 1: Commit**

```bash
git add docs/superpowers
git commit -m "docs: design e plano dos clientes nativos fornecidos pela aplicação"
```

### Task 2: `StorageClientBeans`

**Files:**
- Create: `storage-spring-boot-starter/src/main/java/com/example/storage/spring/StorageClientBeans.java`
- Test: `storage-spring-boot-starter/src/test/java/com/example/storage/spring/StorageClientBeansTest.java`

**Interfaces:**
- Produces: `static <T> T StorageClientBeans.require(BeanFactory beans, Class<T> type, StorageProperties.Provider provider)`. Devolve o bean único ou o `@Primary`; sem bean lança `IllegalStateException("storage.provider=" + provider.name().toLowerCase() + ": declare um bean " + type.getName())`; com vários e nenhum primário, `IllegalStateException("storage.provider=" + ... + ": há mais de um bean " + type.getName() + "; marque um com @Primary")`.

- [ ] **Step 1: Testes que falham** (`DefaultListableBeanFactory` com `RootBeanDefinition(type, supplier)` e `setPrimary`): `bean faltando falha com a mensagem do provedor e do tipo` (assert com a mensagem exata para `S3Client` e `Provider.S3`); `dois beans sem primary falham com a mensagem do @Primary`; `o primary vence`; `um bean só é devolvido`.
- [ ] **Step 2: Rodar e ver falhar** (compilação: `StorageClientBeans` não existe). Comando do starter com `-Dtest=StorageClientBeansTest`.
- [ ] **Step 3: Implementar** com `beans.getBeanProvider(type).getIfAvailable()`, traduzindo `NoUniqueBeanDefinitionException` para a mensagem do `@Primary`.
- [ ] **Step 4: Rodar e ver passar.**
- [ ] **Step 5: Commit** `feat(starter): StorageClientBeans resolve o bean raiz do provedor`

### Task 3: S3 só com beans da aplicação

**Files:**
- Modify: `storage-spring-boot-starter/src/main/java/com/example/storage/spring/StorageAutoConfiguration.java` (`S3StorageConfiguration`)
- Create: `storage-spring-boot-starter/src/test/java/com/example/storage/spring/exemplos/S3ClientsExample.java`
- Modify: `storage-spring-boot-starter/src/test/java/com/example/storage/spring/StorageAutoConfigurationTest.java`, `StorageHealthAutoConfigurationTest.java` (a linha 61 usa `storage.s3.*`)

**Interfaces:**
- Consumes: `StorageClientBeans.require` (Task 2).
- Produces: `S3ClientsExample.s3Client(AwsCredentialsProvider, Region, URI endpointOrNull, boolean pathStyle)` e `S3ClientsExample.s3Presigner(AwsCredentialsProvider, Region, URI endpointOrNull, boolean pathStyle)`, ambos estáticos; `S3ClientsExample.Producao` (cadeia padrão com `DefaultCredentialsProvider.builder().asyncCredentialUpdateEnabled(true)`, um provider só para cliente e presigner, fechado pelo Spring) e `S3ClientsExample.Emulador` (MinIO em `http://localhost:9000`, `minioadmin`, path-style).

- [ ] **Step 1: Testes que falham** em `StorageAutoConfigurationTest`, usando `.withUserConfiguration(S3ClientsExample.Emulador.class)`: `s3UsaClienteEPresignerDaAplicacao` (a URL de `presignGet("r.csv", 5 min)` começa com `http://localhost:9000/reports/r.csv?`, e o contexto tem um `S3Client` e um `S3Presigner` só); `s3SemClientesFalhaComAMensagemDoProvedor` (`storage.provider=s3`, `storage.bucket=reports`, sem beans: causa raiz com `storage.provider=s3: declare um bean software.amazon.awssdk.services.s3.S3Client`); `s3ComDoisClientesSemPrimaryFalha` e `s3ComPrimaryUsaOPrimario`; `objectStorageDaAplicacaoNaoExigeClientes` (`storage.provider=s3`, um `ObjectStorage` da aplicação, sem clientes: o contexto sobe); `providerVazioNaoCriaClienteNenhum` (sem `storage.provider`, com um `S3Client` e um `S3Presigner` da aplicação, e um contador no supplier: nenhum é criado se forem `lazy-init`, e o starter não registra `ObjectStorage`). Remover os testes que dependiam da criação (`s3CriaClienteEPresignerComAMesmaConfiguracao`, `s3UsaUmSoProviderDeCredenciaisNoClienteENoPresigner`, `s3SemAccessKey...`, `s3RenovacaoEmFundoPodeSerDesligada`, `s3ComChavesVaziasUsaCadeiaPadrao`, `s3ComSoUmaDasChavesFalhaComMensagemClara`, `s3ReaproveitaProviderDaAplicacao`); manter `s3ChecksumAceitaNoneERejeitaValorDesconhecido` com os beans do exemplo. O teste de saúde passa a usar os beans do exemplo.
- [ ] **Step 2: Rodar e ver falhar** (`-Dtest=StorageAutoConfigurationTest`): falham porque o starter ainda cria os clientes e porque `S3ClientsExample` não existe.
- [ ] **Step 3: Implementar.** Em `S3StorageConfiguration`, apagar os `@Bean` `s3CredentialsProvider`, `s3Client` e `s3Presigner` e os imports de credenciais; `s3BucketStorageFactory(BeanFactory beans, StorageProperties properties)` devolve `bucket -> new S3ObjectStorage(require(beans, S3Client.class, Provider.S3), require(beans, S3Presigner.class, Provider.S3), bucket, checksum)`. Escrever `S3ClientsExample` com o código que o starter tinha (path-style no cliente e na `S3Configuration` do presigner, `WHEN_REQUIRED` para checksum `none` fica fora: é do `storage.s3.checksum` do adapter).
- [ ] **Step 4: Rodar e ver passar**, sem `-Dtest` (a suíte do starter), exceto o que depende das tarefas seguintes: nesta tarefa os testes de GCS, Azure, OCI e SFTP ainda passam como estão.
- [ ] **Step 5: Commit** `feat(starter)!: S3 usa S3Client e S3Presigner da aplicação`

### Task 4: GCS só com beans da aplicação

**Files:**
- Modify: `StorageAutoConfiguration.java` (`GcsStorageConfiguration`), `StorageAutoConfigurationTest.java` (`gcsReaproveitaOpcoesDaAplicacao`)
- Create: `exemplos/GcsClientsExample.java`

**Interfaces:**
- Produces: `GcsClientsExample.options(Credentials credentials, String projectId)` (um `HttpStorageOptions`), `GcsClientsExample.storage(HttpStorageOptions)`, `GcsClientsExample.multipart(HttpStorageOptions)` (`MultipartUploadClient.create(MultipartUploadSettings.of(options))`), e a `Producao`, que usa `GoogleCredentials.getApplicationDefault()`.

- [ ] **Step 1: Testes que falham:** `gcsUsaStorageEMultipartDaAplicacao` (um contexto com as duas peças montadas pelo exemplo com `NoCredentials.getInstance()`: o `ObjectStorage` é `GcsObjectStorage`); `gcsSemBeansFalhaComAMensagemDoProvedor` (a mensagem cita `com.google.cloud.storage.Storage`).
- [ ] **Step 2: Rodar e ver falhar.**
- [ ] **Step 3: Implementar:** apagar `gcsStorageOptions`, `gcsStorage` e `gcsMultipartUploadClient`; a fábrica resolve `Storage` e `MultipartUploadClient` pelo helper com `Provider.GCS`. O exemplo recebe o código que o starter tinha.
- [ ] **Step 4: Rodar e ver passar.**
- [ ] **Step 5: Commit** `feat(starter)!: GCS usa Storage e MultipartUploadClient da aplicação`

### Task 5: Azure só com `BlobServiceClient` e `storage.azure.sas`

**Files:**
- Modify: `StorageAutoConfiguration.java` (`AzureStorageConfiguration`), `StorageProperties.java` (acrescenta `Azure.sas`, ainda sem remover campos), `storage-spring-boot-starter/pom.xml` (`azure-identity` vira `<scope>test</scope>`), `StorageAutoConfigurationTest.java`
- Create: `exemplos/AzureClientsExample.java`

**Interfaces:**
- Produces: `StorageProperties.Azure.Sas { SHARED_KEY, USER_DELEGATION }` e o componente `Sas sas` em `Azure` (sem `@DefaultValue`, `null` quando ausente); `AzureClientsExample.blobServiceClientFromConnectionString(String)` e `blobServiceClientFromEndpoint(String endpoint, TokenCredential)`, `AzureClientsExample.Producao` (endpoint + `DefaultAzureCredentialBuilder`) e `Emulador` (Azurite, `UseDevelopmentStorage=true`).

- [ ] **Step 1: Testes que falham:** `azureUsaBlobServiceClientDaAplicacao` (com `Emulador` e `storage.azure.sas=shared-key`: `AzureBlobObjectStorage`, URL assinada com a chave da conta); `azureComUserDelegationUsaSasPorDelegacao` (`storage.azure.sas=user-delegation`); `azureSemSasFalha` (`storage.azure.sas é obrigatório quando storage.provider=azure (shared-key ou user-delegation)`); `azureSemSasComObjectStorageDaAplicacaoSobe`; `azureSemBlobServiceClientFalhaCitandoOContainerClient` (a mensagem contém `com.azure.storage.blob.BlobServiceClient` e `BlobContainerClient`); `azureVariosContainersDerivamDoServiceClient` (ajustado para o exemplo). Remover os testes de connection string, endpoint e `azure-identity` do starter.
- [ ] **Step 2: Rodar e ver falhar.**
- [ ] **Step 3: Implementar:** apagar `azureBlobServiceClient` e `azureBlobContainerClient`; a fábrica e o bean `ObjectStorage` derivam `blobServiceClient.getBlobContainerClient(bucket)` e escolhem `withSharedKey` ou `withUserDelegation` por `properties.azure().sas()`. O bean do `BlobServiceClient` é resolvido pelo helper, e a falta dele lança a mensagem do helper acrescida de `; o starter deriva o BlobContainerClient por dentro, e um BlobContainerClient da aplicação não é mais usado`.
- [ ] **Step 4: Rodar e ver passar.**
- [ ] **Step 5: Commit** `feat(starter)!: Azure usa o BlobServiceClient da aplicação e storage.azure.sas`

### Task 6: OCI só com o cliente da aplicação

**Files:**
- Modify: `StorageAutoConfiguration.java` (`OciStorageConfiguration`), `StorageAutoConfigurationTest.java` (`ociConsultaNamespaceQuandoNaoConfigurado`, `ociUsaNamespaceDaConfiguracao`)
- Create: `exemplos/OciClientsExample.java`

**Interfaces:**
- Produces: `OciClientsExample.objectStorageClient(BasicAuthenticationDetailsProvider auth)` (`ObjectStorageClient.builder().build(auth)`) e a `Producao`, com `ConfigFileAuthenticationDetailsProvider` (`~/.oci/config`, perfil `DEFAULT`).

- [ ] **Step 1: Testes que falham:** o dois testes existentes passam a declarar o cliente com um mock (`com.oracle.bmc.objectstorage.ObjectStorage`); `ociSemClienteFalhaComAMensagemDoProvedor` (cita `com.oracle.bmc.objectstorage.ObjectStorage`).
- [ ] **Step 2: Rodar e ver falhar.**
- [ ] **Step 3: Implementar:** apagar `ociObjectStorageClient`; a fábrica resolve `com.oracle.bmc.objectstorage.ObjectStorage` pelo helper com `Provider.OCI` e mantém a consulta de namespace.
- [ ] **Step 4: Rodar e ver passar.**
- [ ] **Step 5: Commit** `feat(starter)!: OCI usa o cliente da aplicação`

### Task 7: SFTP só com `SftpConnection`

**Files:**
- Modify: `StorageAutoConfiguration.java` (`SftpStorageConfiguration`), `StorageAutoConfigurationTest.java` (os 4 testes `sftp*` de propriedades obrigatórias), `SftpStorageAutoConfigurationTest.java`
- Create: `exemplos/SftpClientsExample.java`

**Interfaces:**
- Produces: `SftpClientsExample.connection(String host, int port, String username, String password, String knownHostsOrNull, boolean trustAllHosts, Duration keepAlive, int maxChannels)` (a verificação de host key e a autenticação que o starter tinha, devolvendo `SftpConnection.reconnecting(...)`), e a `Producao`, com `known_hosts`.

- [ ] **Step 1: Testes que falham:** `sftpSemConnectionFalhaComAMensagemDoProvedor` (cita `com.example.storage.sftp.SftpConnection`); `sftpUsaConnectionDaAplicacao` (um `SftpConnection` mockado, `storage.sftp.root=/reports`: `SftpObjectStorage`); o `SftpStorageAutoConfigurationTest` passa a conectar no `atmoz/sftp` pelo exemplo (`insecure` só no teste) e a gravar e ler `a.txt`. Remover os testes de `host`, `username`, `password` e `root` antes de conectar que validavam properties.
- [ ] **Step 2: Rodar e ver falhar.**
- [ ] **Step 3: Implementar:** apagar `sftpConnection`, `connect`, `configureHostKeyVerification` e `closeQuietly` do starter; a fábrica resolve `SftpConnection` pelo helper com `Provider.SFTP` e mantém `requireSftpRoot`. O código apagado vai para o exemplo.
- [ ] **Step 4: Rodar e ver passar** (com Docker).
- [ ] **Step 5: Commit** `feat(starter)!: SFTP usa a SftpConnection da aplicação`

### Task 8: Propriedades sem conexão e chaves removidas

**Files:**
- Modify: `StorageProperties.java` (esvazia `S3`, remove `Gcs`, `Azure` com só `sas`, `Oci` com só `namespace`, `Sftp` com só `root`, remove `mask` e os `toString`), `StorageAutoConfiguration.java`
- Create: `storage-spring-boot-starter/src/main/java/com/example/storage/spring/RemovedKeys.java`
- Test: `storage-spring-boot-starter/src/test/java/com/example/storage/spring/RemovedKeysTest.java`; remover `toStringDasPropertiesNaoExpoeSegredos` de `StorageAutoConfigurationTest`

**Interfaces:**
- Produces: `static void RemovedKeys.check(Environment environment)`: compara as chaves ligadas (`Binder.get(environment).bind("storage.<provedor>", Bindable.mapOf(String.class, Object.class))`, sem hífen e em minúsculas, para valer na forma `STORAGE_S3_REGION`) com as listas das Global Constraints e lança `IllegalStateException("Configuração inválida de storage.*:\n - " + String.join("\n - ", problemas))`. Chamado por um `ImportBeanDefinitionRegistrar` interno `StorageAutoConfiguration.RemovedKeysRegistrar`, importado ao lado do `BucketsRegistrar`, para valer sempre.
- Consumes: as Global Constraints de mensagem; o que declarar por provedor: S3 `os beans S3Client e S3Presigner`, GCS `os beans Storage e MultipartUploadClient`, Azure `o bean BlobServiceClient`, OCI `o bean com.oracle.bmc.objectstorage.ObjectStorage`, SFTP `o bean SftpConnection`.

- [ ] **Step 1: Testes que falham** em `RemovedKeysTest` (`ApplicationContextRunner` com `StorageAutoConfiguration`): `chaveRemovidaDerrubaOStartupComProviderVazio` (só `storage.s3.region=us-east-1`: causa raiz contém `storage.s3.region foi removida: configure os beans S3Client e S3Presigner na aplicação`); `chaveRemovidaEmVariavelDeAmbiente` (`SystemEnvironmentPropertySource` com `STORAGE_S3_REGION`: mesma chave na mensagem, formatada como `storage.s3.region`); `todasAsChavesRemovidasNaMesmaMensagem` (`storage.s3.endpoint`, `storage.sftp.host`, `storage.azure.connection-string`, `storage.gcs.project-id`, `storage.oci.profile`: as cinco aparecem); `chavesQueFicamNaoFalham` (`storage.s3.checksum=none`, `storage.oci.namespace=ns`, `storage.sftp.root=/r`, `storage.filesystem.root=/tmp`, `storage.azure.sas=shared-key`); `chaveRemovidaComObjectStorageDaAplicacaoFalha`.
- [ ] **Step 2: Rodar e ver falhar.**
- [ ] **Step 3: Implementar** `RemovedKeys` e o registrar; esvaziar os records conforme as Global Constraints, ajustando as referências restantes no starter e nos testes.
- [ ] **Step 4: Rodar a suíte inteira do starter** e ver passar.
- [ ] **Step 5: Commit** `feat(starter)!: propriedades sem conexão; chaves removidas derrubam o startup`

### Task 9: README e plano do storage-library

**Files:**
- Modify: `README.md` (seção Spring Boot a partir de "Spring Boot"; trocar o YAML e remover os trechos de `AwsCredentialsProvider`, clientes reaproveitados e `connection-string`), `docs/plan.md` (linha da decisão e da fase)

- [ ] **Step 1: Escrever** no README: o YAML novo (só as propriedades que ficam, com `storage.azure.sas`); "Clientes nativos por provedor" com a tabela das Global Constraints, o exemplo do S3 inline e links para os cinco exemplos em `exemplos/`; "Migrando" com cada chave removida e o que a substitui, e a regra do Azure (um `BlobContainerClient` da aplicação não é mais usado) e do SFTP (`SSHClient` virou `SftpConnection`); "Limites conhecidos": o starter não detecta presigner e cliente com configuração diferente.
- [ ] **Step 2: Rodar a suíte do starter** (nada de código mudou) e conferir que os nomes citados no README existem: `grep` de cada classe de exemplo e de cada mensagem.
- [ ] **Step 3: Commit** `docs: clientes nativos por provedor, migração e limites conhecidos`

---

## Parte 2: engine-tax (sem commits)

Nas tarefas desta parte, o ledger é escrito à mão (o intervalo de commits do `task-done` é do `storage-library`). As Tasks 10 a 13 deixam tudo no working tree.

### Task 10: Tarefa T-173 e a cadeia isolada

**Files:**
- Modify: `engine-tax/docs/tasks.md` (nova seção "### 17. Clientes nativos do storage" depois da 16, com `T-173` em `[~]`, descrevendo o `StorageConfig`, os quatro módulos e o que não foi verificado)
- Create (fora do repositório, no scratchpad): `et-storage/install.sh`, cópia de `ops/install-libraries.sh` com o pin da `storage-library` trocado pelo commit da ponta da branch, e `et-storage/libs/storage-library`, um clone local do repositório

- [ ] **Step 1: Registrar a T-173** no `docs/tasks.md` no formato das outras (pedido do usuário 2026-11-15; "Pronto quando": `mvn test` verde nos quatro módulos e no novo; `Não verificado`: LocalStack ao vivo e `mvn verify` completo).
- [ ] **Step 2: Montar a cadeia:** `git clone <caminho local do storage-library> et-storage/libs/storage-library` e `sh et-storage/install.sh` com `LIBRARIES_DIR=et-storage/libs` e `MAVEN_ARGS=-Dmaven.repo.local=et-storage/m2`. Esperado: `exit=0`, sem tocar no `~/.m2`.

### Task 11: Módulo `platform/storage-support` e `StorageConfig`

**Files:**
- Create: `engine-tax/platform/storage-support/pom.xml`, `.../src/main/java/com/example/enginetax/platform/storage/StorageConfig.java`, `.../src/test/java/com/example/enginetax/platform/storage/StorageConfigTest.java`
- Modify: `engine-tax/pom.xml` (`<module>platform/storage-support</module>` antes de `registry-api`, e a entrada em `dependencyManagement` ao lado de `platform-support`)

**Interfaces:**
- Produces: `public class StorageConfig` (`@Configuration(proxyBeanMethods = false)`, `@ConditionalOnProperty(prefix = "storage", name = "provider", havingValue = "s3")`) com os beans `AwsCredentialsProvider storageCredentialsProvider()` (`DefaultCredentialsProvider.builder().asyncCredentialUpdateEnabled(true).build()`), `S3Client s3Client(AwsCredentialsProvider, @Value("${platform.storage.path-style:false}") boolean pathStyle)` e `S3Presigner s3Presigner(AwsCredentialsProvider, boolean pathStyle)`. Sem região nem endpoint explícitos (vêm de `AWS_REGION`, `AWS_ENDPOINT_URL_S3`); o presigner leva `S3Configuration.builder().pathStyleAccessEnabled(pathStyle)`, e o cliente `forcePathStyle(pathStyle)`.

- [ ] **Step 1: Testes que falham** (`ApplicationContextRunner` com `StorageConfig`; propriedades de sistema `aws.region`, `aws.accessKeyId`, `aws.secretAccessKey`, `aws.endpointUrlS3=http://localhost:4566`, restauradas em `finally`): `storageProviderS3CriaClienteEPresignerComOMesmoProvider` (`S3Client.serviceClientConfiguration().credentialsProvider()` é o bean `AwsCredentialsProvider`); `pathStyleChegaAoPresigner` (`platform.storage.path-style=true`: a URL pré-assinada de `bucket/chave` começa com `http://localhost:4566/bucket/chave?`; com `false`, o host começa com `bucket.`); `providerVazioNaoCriaClienteNenhum` (`storage.provider=`: nenhum `S3Client` nem `S3Presigner`).
- [ ] **Step 2: Rodar e ver falhar:** `mvn -B -Dmaven.repo.local=<m2 isolado> -pl platform/storage-support -am test` (compilação: classe inexistente).
- [ ] **Step 3: Implementar** o módulo: `pom.xml` no molde do `platform/platform-support/pom.xml`, com `storage-aws`, `spring-boot-autoconfigure`, `apache-client` e os testes; a classe e o registro no `pom.xml` raiz.
- [ ] **Step 4: Rodar e ver passar.**

### Task 12: Os quatro módulos usam o `StorageConfig`

**Files:**
- Modify: `registry-api/pom.xml`, `signer-api/pom.xml`, `storage-api/pom.xml`, `validation-api/pom.xml` (dependência `storage-support`); `registry-api/.../config/StorageConfiguration.java`, `signer-api/.../config/StorageConfiguration.java`, `validation-api/.../config/StorageConfiguration.java`, `storage-api/.../config/StorageApiConfiguration.java` (`@Import(StorageConfig.class)`); os quatro `src/main/resources/application.yaml` (`storage.s3.path-style: ${STORAGE_PATH_STYLE:false}` vira `platform.storage.path-style: ${STORAGE_PATH_STYLE:false}`, e o comentário diz que região, endpoint e credenciais vêm da cadeia padrão); `storage-api/src/test/java/com/example/enginetax/storage/StorageApiTest.java` (`storage.s3.path-style=true` vira `platform.storage.path-style=true`)

- [ ] **Step 1: Rodar os testes dos quatro módulos** contra a cadeia isolada da Task 10, antes de mexer: `mvn -B -Dmaven.repo.local=<m2> -pl registry-api,signer-api,storage-api,validation-api -am test`. Esperado: o `StorageApiTest` e os testes que usam o S3 falham, porque o starter novo exige o `S3Client` e o `S3Presigner`; os que desligam o storage (`storage.provider=`) passam.
- [ ] **Step 2: Implementar** as dependências, os `@Import` e os YAMLs.
- [ ] **Step 3: Rodar de novo** o mesmo comando. Esperado: `BUILD SUCCESS`, `StorageApiTest` incluído (LocalStack com Docker).

### Task 13: Verificação final e parada

- [ ] **Step 1:** No `storage-library`, rodar a suíte do starter inteira e conferir `git status` limpo e `git log main..HEAD` com os commits das Tasks 1 a 9.
- [ ] **Step 2:** No engine-tax, `mvn -B -Dmaven.repo.local=<m2> -pl platform/storage-support,registry-api,signer-api,storage-api,validation-api,platform/platform-support,numbering-api,outcome-api,lifecycle-worker -am test`. Esperado: `BUILD SUCCESS`. Anotar a contagem de testes.
- [ ] **Step 3:** Atualizar a T-173 em `docs/tasks.md` com o que foi conferido e o que não foi, e parar: o merge em `main`, o push do `storage-library` e a troca do pin em `ops/install-libraries.sh` esperam o pedido do usuário.
