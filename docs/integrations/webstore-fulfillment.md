# WebStore Fulfillment

Este documento descreve o fluxo operacional real do módulo de fulfillment do EasyVip.
Ele cobre o código atual, a persistência SQL, os comandos administrativos e os testes relevantes.

## Escopo e arquitetura

O projeto tem duas integrações WebStore separadas:

```text
WebStore Sync
EasyVip -> Rails
```

e

```text
WebStore Fulfillment
EasyVip -> Rails
```

No código, ambos os fluxos são cliente HTTP do Rails. O fulfillment não recebe chamadas externas.
O EasyVip não abre listener HTTP, não usa RCON e não expõe porta pública para esse fluxo.

Fluxo textual do fulfillment:

```text
Rails registra pagamento aprovado
        ↓
Rails cria fulfillment pendente com server_id
        ↓
EasyVip executa polling periódico
        ↓
EasyVip envia POST assinado em /api/v1/minecraft/fulfillments/claim
        ↓
Rails devolve fulfillments do server_id correto com claim_token e lease_expires_at
        ↓
EasyVip valida SKU, quantity e servidor
        ↓
EasyVip gera ou reaproveita a chave localmente
        ↓
EasyVip envia POST assinado em /api/v1/minecraft/fulfillments/:fulfillment_id/complete
        ↓
Rails entrega a chave ao comprador
```

### Separação com WebStore Sync

`WebStoreSyncService` só envia presença de jogador e challenge do `/link`:

- `POST /api/v1/minecraft/players/sync`
- `POST /api/v1/minecraft/challenges`

`WebStoreFulfillmentService` usa o contrato de fulfillment por polling:

- `POST /api/v1/minecraft/fulfillments/claim`
- `POST /api/v1/minecraft/fulfillments/:fulfillment_id/complete`
- `POST /api/v1/minecraft/fulfillments/:fulfillment_id/fail`

### Por que não há RCON nem callback HTTP de entrada

- O EasyVip funciona somente como cliente do Rails para fulfillment.
- O Rails não faz chamadas diretas para o Minecraft.
- Isso evita abrir portas adicionais no servidor de jogo.
- O contrato de entrega fica concentrado no Rails, que é o único serviço HTTP público.

## Implementação relevante

- `common/src/main/java/br/com/pedrodalben/easyvip/webstore/WebStoreFulfillmentService.java`
- `common/src/main/java/br/com/pedrodalben/easyvip/webstore/WebStoreSyncService.java`
- `common/src/main/java/br/com/pedrodalben/easyvip/config/EasyVipConfig.java`
- `common/src/main/java/br/com/pedrodalben/easyvip/persistence/SqlDatabaseManager.java`
- `common/src/main/java/br/com/pedrodalben/easyvip/webstore/model/FulfillmentRecord.java`
- `common/src/main/java/br/com/pedrodalben/easyvip/webstore/model/FulfillmentItemRecord.java`
- `common/src/main/java/br/com/pedrodalben/easyvip/command/EasyVipCommands.java`
- `neoforge/src/main/java/br/com/pedrodalben/easyvip/NeoForgeEasyVipMod.java`
- `fabric/src/main/java/br/com/pedrodalben/easyvip/fabric/EasyVipFabric.java`

## Configuração

O arquivo gerado é `config/easyvip/webstore.toml`.
Ele contém a configuração do sync na raiz e a configuração do fulfillment em `[fulfillment]`.

### Bloco do sync

Campos reais:

| Campo | Padrão | Obrigatório | Uso |
|---|---:|---:|---|
| `enabled` | `false` | não | Habilita sync e `/link`. |
| `api_url` | `http://localhost:3000` | sim quando habilitado | Base URL do Rails. |
| `api_token` | `""` | sim quando habilitado | Bearer token do sync e do `/link`. |
| `server_id` | `""` | recomendado | Identidade do servidor para o payload de sync. |
| `sync_on_register` | `true` | não | Sincroniza no registro. |
| `sync_on_login` | `true` | não | Sincroniza no login. |
| `sync_on_join` | `true` | não | Sincroniza ao entrar. |
| `sync_on_nick_change` | `true` | não | Sincroniza ao trocar nick. |
| `retry_max_attempts` | `3` | não | Tentativas em erros transitórios. |
| `retry_delay_seconds` | `5` | não | Base do backoff exponencial. |

O payload de sync hoje inclui:

```json
{
  "minecraft_uuid": "uuid",
  "username": "PedropsRei",
  "canonical_username": "pedropsrei",
  "server_id": "allthemons",
  "identity_status": "observed"
}
```

O campo `ip_address` continua opcional e só é enviado quando existe valor.

### Bloco `[fulfillment]`

Campos reais:

| Campo | Padrão | Obrigatório quando `enabled = true` | Uso |
|---|---:|---:|---|
| `enabled` | `false` | sim | Habilita o polling de fulfillment. |
| `server_id` | `""` | sim | Identidade do servidor. Deve bater com o Rails. |
| `key_id` | `""` | sim | Seleciona a chave HMAC. |
| `key_prefix` | `EVIP-` | sim | Prefixo dos activation keys gerados pelo fulfillment. |
| `secret_env` | `EASYVIP_FULFILLMENT_SECRET` | sim | Nome da variável de ambiente do segredo HMAC fallback. |
| `token_env` | `EASYVIP_FULFILLMENT_TOKEN` | sim | Nome da variável de ambiente do Bearer token. |
| `token` | `""` | sim | Bearer token inline, se usado. |
| `poll_interval_seconds` | `15` | sim | Intervalo de polling. Mínimo efetivo: 5. |
| `claim_limit` | `20` | sim | Tamanho do lote por claim. Entre 1 e 100. |
| `request_timeout_seconds` | `10` | sim | Timeout por request HTTP. |
| `timestamp_tolerance_seconds` | `60` | sim | Tolerância para timestamp assinado. |

Exemplo seguro:

```toml
[fulfillment]
enabled = true
server_id = "allthemons"
key_id = "easyvip-allthemons-v1"
key_prefix = "ATM-"
secret_env = "EASYVIP_FULFILLMENT_ALLTHEMONS_SECRET"
token_env = "EASYVIP_FULFILLMENT_TOKEN"
token = ""
poll_interval_seconds = 15
claim_limit = 20
request_timeout_seconds = 10
timestamp_tolerance_seconds = 60
```

O módulo só inicia quando:

- `fulfillment.enabled = true`
- `server_id` e `key_id` estão preenchidos
- token e segredo HMAC podem ser resolvidos
- SQL está saudável

Se SQL não estiver disponível, o serviço fica indisponível e não processa nada.

### Chaves HMAC

As chaves ficam em `[fulfillment.keys.<id>]`:

```toml
[fulfillment.keys.easyvip-allthemons-v1]
secret_env = "EASYVIP_FULFILLMENT_ALLTHEMONS_SECRET"

[fulfillment.keys.easyvip-allthemons-v2]
secret_env = "EASYVIP_FULFILLMENT_ALLTHEMONS_SECRET_V2"
```

Resolução do segredo:

1. `fulfillment.keys.<key_id>.secret`
2. `fulfillment.keys.<key_id>.secret_env`
3. `fulfillment.secret_env`

### Catálogo de produtos

Os SKUs autorizados ficam em `[products.<sku>]`.
O Rails não escolhe `commands`, `actions`, `NBT`, `item_id`, `tier_id`, `reward_key_id`, `duration`, `max_uses` ou `expiration`.
Isso é decidido localmente pelo EasyVip.

Campos aceitos:

| Campo | Uso |
|---|---|
| `type` | `vip` ou `reward`. |
| `kind` | Alias compatível com arquivos antigos. |
| `tier_id` | Obrigatório para `vip`. |
| `duration` | Obrigatório para `vip`. |
| `reward_key_id` | Obrigatório para `reward`. |
| `max_uses` | Vai para o `KeyRecord`. |
| `expires_after` | Expiração absoluta da key gerada. |
| `bind_to_player` | Vincula a key ao UUID recebido. |

`quantity_per_purchase` não faz parte do contrato atual do EasyVip e não é gerado pelo arquivo atual. O código espera `quantity = 1` por item, e compras maiores devem chegar do Rails já expandidas em múltiplos `FulfillmentItem`.

Exemplos:

```toml
[products.gems_50]
type = "reward"
reward_key_id = "gems_50"
max_uses = 1
expires_after = "365d"
bind_to_player = true

[products.vip_ultraball_30d]
type = "vip"
tier_id = "ultraball"
duration = "30d"
max_uses = 1
expires_after = "365d"
bind_to_player = true
```

SKU inválido:

```text
O SKU não existe em [products] -> o fulfillment falha com unknown_sku.
```

Produto desabilitado:

```text
Não há flag enabled por SKU no código.
Para desabilitar um produto, remova o SKU do catálogo local.
```

### Logs

Arquivos:

- `config/easyvip/data/webstore_fulfillment.log`
- `config/easyvip/data/webstore_sync.log`

Formato:

```text
[2026-07-07T12:00:00Z] MESSAGE
```

No fulfillment, os logs incluem `server_id`, estado do scheduler, erros e resumo de retry.
Activation keys inteiras, tokens, claim_token completo, signatures, nonces completos e segredos nunca devem aparecer em logs.
O código usa fingerprint SHA-256 e mascaramento para chaves quando precisa registrar algo identificável.

### Comando administrativo

O estado atual do fulfillment é exposto por:

```text
/easyvip admin webstore status
```

O resumo inclui:

- `server_id`
- `key_id`
- estado do loop
- estado do SQL
- timestamps dos últimos ciclos
- contadores de claim e processamento
- último código de erro

## Contrato HTTP

### Cabeçalhos usados pelo EasyVip

Request:

- `Authorization: Bearer <token>`
- `Content-Type: application/json`
- `X-EasyVip-Key-Id`
- `X-EasyVip-Timestamp`
- `X-EasyVip-Nonce`
- `X-EasyVip-Signature`

Response:

- `X-EasyVip-Response-Timestamp`
- `X-EasyVip-Response-Signature`

### Canonical request

O body é assinado como bytes brutos. Não reserializar JSON para validar assinatura.

Canonical do request:

```text
METHOD + "\n" +
PATH + "\n" +
TIMESTAMP + "\n" +
NONCE + "\n" +
SHA256(BODY_BRUTO)
```

Exemplo conceitual:

```text
POST
/api/v1/minecraft/fulfillments/claim
1720000000
3d8d2a18-0c93-4b52-a6b9-5bdaf7e0ef1a
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
```

Canonical da resposta:

```text
TIMESTAMP + "\n" +
NONCE + "\n" +
STATUS_CODE + "\n" +
SHA256(BODY_BRUTO)
```

### `POST /api/v1/minecraft/fulfillments/claim`

Objetivo:

- pedir ao Rails uma lista de fulfillments pendentes do `server_id` configurado.

Request:

```json
{
  "server_id": "allthemons",
  "limit": 20
}
```

Resposta esperada:

```json
{
  "server_id": "allthemons",
  "fulfillments": [
    {
      "fulfillment_id": "uuid-do-fulfillment",
      "order_id": "ORD-123",
      "minecraft_uuid": "uuid-do-jogador",
      "minecraft_username": "PedropsRei",
      "origin_server_id": "allthemons",
      "claim_token": "token-temporario",
      "lease_expires_at": "2026-07-07T12:10:00Z",
      "items": [
        {
          "line_item_id": "uuid-do-item",
          "product_sku": "gems_50",
          "quantity": 1
        }
      ]
    }
  ]
}
```

Regras reais do cliente:

- `server_id` do root e/ou do fulfillment precisa bater com o servidor configurado.
- `claim_token` é obrigatório.
- `lease_expires_at` é obrigatório.
- `quantity` precisa ser `1`.
- `line_item_id` identifica a unidade de fulfillment.

Se o Rails devolver `409` com `server_mismatch`, o item é rejeitado sem gerar chave.

### `POST /api/v1/minecraft/fulfillments/:fulfillment_id/complete`

Objetivo:

- confirmar ao Rails que as chaves foram geradas com sucesso.

Request:

```json
{
  "server_id": "allthemons",
  "claim_token": "token-temporario",
  "items": [
    {
      "line_item_id": "uuid-do-item",
      "product_sku": "gems_50",
      "activation_key": "ATM-ABCDEF123456",
      "key_fingerprint": "sha256:abcd1234ef567890"
    }
  ]
}
```

Respostas tratadas como sucesso:

- `204`
- `200`
- `409` com erro de idempotência já concluída

Respostas tratadas como retry:

- `409 lease_expired`
- `408`
- `429`
- `5xx`

Se o lease expirou, o EasyVip não gera outra chave. Ele faz novo claim e reaproveita a chave local do mesmo `line_item_id`.

### `POST /api/v1/minecraft/fulfillments/:fulfillment_id/fail`

Objetivo:

- notificar falha segura sem stack trace.

Request:

```json
{
  "server_id": "allthemons",
  "claim_token": "token-temporario",
  "error_code": "unsupported_quantity",
  "error_message": "quantity_must_be_1"
}
```

Uso típico:

- SKU inexistente -> `unknown_sku`
- tipo inválido -> `invalid_kind`
- quantity diferente de 1 -> `unsupported_quantity`
- servidor errado -> `server_mismatch`

## HMAC, autenticação e replay

- O token Bearer é separado do HMAC.
- `key_id` seleciona qual segredo HMAC usar.
- O timestamp e o nonce evitam replay.
- O cliente rejeita respostas com assinatura inválida.
- O body da resposta também é validado antes de ser processado.
- O body bruto do request é o que entra no hash. Não use JSON reserializado.
- Tolerância de timestamp: `timestamp_tolerance_seconds`.

Rotação de segredos:

1. Adicione nova entrada em `[fulfillment.keys.<novo_id>]`.
2. Publique o novo segredo no ambiente.
3. Troque `fulfillment.key_id`.
4. Atualize o Rails para aceitar o novo segredo.
5. Mantenha o segredo antigo até não haver fulfillments antigos em voo.

## Idempotência e recuperação

### Cenários cobertos pelo código

- EasyVip cria a key e cai antes de confirmar ao Rails:
  - a key já foi persistida no SQL.
  - o próximo ciclo reaproveita a mesma key.
- Rails responde timeout após a key ser gerada:
  - o item fica em `awaiting_complete`.
  - o retry reenviará a mesma key.
- O mesmo fulfillment é reenviado:
  - mesmo `fulfillment_id` e mesmo payload digest não criam outra key.
- `lease_expires_at` expira:
  - o cliente não cria nova key.
  - ele faz novo claim e reusa a key local do `line_item_id`.
- Scheduler reinicia:
  - o estado fica no SQL.
  - o próximo start retoma do ledger local.
- SKU não existe:
  - o fulfillment falha com `unknown_sku`.
- Já existe key para o `line_item_id`:
  - a mesma key é reenviada.
- Pedido processado novamente após retry:
  - não gera duplicata.

### Garantia principal

Um mesmo `fulfillment_id` e um mesmo `line_item_id` não devem gerar mais de uma key.
Essa garantia vem da persistência SQL e da busca por `line_item_id`.

## Persistência SQL

Tabelas usadas:

- `webstore_fulfillments`
- `webstore_fulfillment_items`
- `easyvip_keys`

Campos relevantes em `webstore_fulfillments`:

- `fulfillment_id`
- `order_id`
- `origin_server_id`
- `server_id`
- `claim_token`
- `lease_expires_at`
- `status`
- `failure_code`
- `error_message`

Campos relevantes em `webstore_fulfillment_items`:

- `line_item_id`
- `fulfillment_id`
- `product_sku`
- `quantity`
- `key_code`
- `key_fingerprint`
- `status`

Índices únicos:

- `webstore_fulfillments.fulfillment_id`
- `webstore_fulfillment_items.line_item_id`
- `webstore_fulfillment_items.key_code`

Transação real:

- o fulfillment é carregado e atualizado dentro de transação SQL.
- a key e o ledger são persistidos antes do `complete`.
- se houver crash antes do `complete`, a retomada encontra o mesmo `line_item_id`.

Modo JSON não é permitido para fulfillment.
Se SQL estiver indisponível, o módulo fica inativo.

Verificação de saúde:

- comando `/easyvip admin webstore status`
- `sql=healthy` no resumo
- `SqlDatabaseManager.isHealthy()` retorna `true`

## Testes e homologação

### Testes locais existentes

Executar:

```bash
./gradlew clean buildAll
./gradlew test
```

Cobertura relevante:

- `common/src/test/java/br/com/pedrodalben/easyvip/webstore/WebStoreFulfillmentServiceTest.java`
- `common/src/test/java/br/com/pedrodalben/easyvip/webstore/WebStoreSyncServiceTest.java`
- `common/src/test/java/br/com/pedrodalben/easyvip/webstore/WebStoreFulfillmentStagingTest.java`

O teste local usa servidor HTTP fake e H2.

### Guia prático de validação

1. Habilite em staging.
2. Crie um SKU de teste no catálogo local.
3. Gere um pedido pago de teste no Rails.
4. Confirme o claim pelo EasyVip.
5. Confirme a criação da key local.
6. Confirme o callback `complete`.
7. Valide a entrega no Rails.
8. Repita o mesmo fulfillment.
9. Simule timeout.
10. Simule reinício do EasyVip.
11. Verifique que não houve chave duplicada.

### Teste opcional de staging

O teste `WebStoreFulfillmentStagingTest` só roda quando as variáveis de ambiente de staging estão presentes.

Exemplo de variáveis:

```bash
export EASYVIP_WEBSTORE_STAGING_ENABLE=true
export EASYVIP_WEBSTORE_STAGING_URL="https://rails-staging.example.invalid"
export EASYVIP_WEBSTORE_STAGING_TOKEN="..."
export EASYVIP_WEBSTORE_STAGING_SECRET="..."
export EASYVIP_WEBSTORE_STAGING_SERVER_ID="allthemons"
export EASYVIP_WEBSTORE_STAGING_KEY_ID="easyvip-allthemons-v1"
export EASYVIP_WEBSTORE_STAGING_FULFILLMENT_ID="..."
export EASYVIP_WEBSTORE_STAGING_PRODUCT_TYPE="reward"
export EASYVIP_WEBSTORE_STAGING_SKU="gems_50"
export EASYVIP_WEBSTORE_STAGING_REWARD_KEY_ID="gems_50"
```

O teste não deve ser habilitado contra produção.
