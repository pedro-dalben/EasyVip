# WebStore Fulfillment via Polling

Este documento descreve o fluxo de fulfillment seguro usado pelo EasyVip para converter compras aprovadas na WebStore Rails em chaves locais, sem expor endpoint HTTP de entrada no Minecraft.

## Arquitetura

```text
Mercado Pago -> Rails valida pagamento -> Rails cria fulfillment pendente
                                      ↓
EasyVip faz polling autenticado no Rails
                                      ↓
EasyVip recebe somente SKUs permitidos
                                      ↓
EasyVip gera chaves localmente e de forma idempotente
                                      ↓
EasyVip confirma as chaves emitidas ao Rails
                                      ↓
Rails entrega as chaves ao comprador
```

Regras centrais:

* sem RCON;
* sem endpoint HTTP inbound no Minecraft;
* sem `custom`, `command`, `itemstack`, NBT livre ou payload arbitrário vindo do Rails;
* `product_sku` precisa existir no `webstore.toml`;
* tier, reward key, duração, expiração e limite de uso vêm do EasyVip;
* toda chave gerada para fulfillment fica vinculada ao UUID recebido;
* o fluxo é durável em SQL e suporta retry após crash.

## Configuração

### `webstore.toml`

```toml
[fulfillment]
enabled = false
server_id = "allthemons-01"
poll_interval_seconds = 15
claim_limit = 20
lease_seconds = 120
request_timeout_seconds = 10
timestamp_tolerance_seconds = 60
key_id = "current"
secret_env = "EASYVIP_FULFILLMENT_SECRET"
token_env = "EASYVIP_FULFILLMENT_TOKEN"

[products.gems_50]
type = "reward"
reward_key_id = "gems_50"
quantity_per_purchase = 1
max_uses = 1
bind_to_player = true
expires_after = "365d"

[products.vip_ultraball_30d]
type = "vip"
tier_id = "ultraball"
duration = "30d"
quantity_per_purchase = 1
max_uses = 1
bind_to_player = true
expires_after = "365d"
```

### Requisitos de ambiente

* `fulfillment.enabled = true` exige SQL habilitado e saudável;
* a integração não sobe se o segredo ou token não estiverem resolvidos;
* o segredo pode vir de `secret_env` ou da chave inline em `[fulfillment.keys]`;
* o token pode vir de `token_env` ou do campo `token`.

## Tabelas SQL

O fluxo usa duas tabelas próprias:

* `webstore_fulfillments`
* `webstore_fulfillment_items`

Campos principais:

* `fulfillment_id` único;
* `order_id`;
* `server_id`;
* `minecraft_uuid`;
* `payload_digest`;
* `status`;
* `claimed_at`, `completed_at`, `failed_at`;
* `failure_code`, `error_message`;
* `line_item_id` único;
* `product_sku`;
* `quantity`;
* `key_code`;
* `key_fingerprint`;
* `status`.

`key_code` também é protegido por índice único.

## Contrato HTTP

### Claim

```http
POST /api/v1/minecraft/fulfillments/claim
Authorization: Bearer <fulfillment_token>
X-EasyVip-Key-Id: current
X-EasyVip-Timestamp: <unix_timestamp_seconds>
X-EasyVip-Nonce: <nonce>
X-EasyVip-Signature: v1=<hmac_hex>
Content-Type: application/json
```

Body:

```json
{
  "server_id": "allthemons-01",
  "limit": 20
}
```

Response:

```json
{
  "fulfillments": [
    {
      "fulfillment_id": "uuid",
      "order_id": "ORD-2026-000123",
      "minecraft_uuid": "uuid",
      "minecraft_username": "pedrops",
      "items": [
        {
          "line_item_id": "uuid",
          "product_sku": "gems_50",
          "quantity": 1
        }
      ]
    }
  ]
}
```

### Complete

```http
POST /api/v1/minecraft/fulfillments/{fulfillment_id}/complete
```

Body:

```json
{
  "server_id": "allthemons-01",
  "items": [
    {
      "line_item_id": "uuid",
      "product_sku": "gems_50",
      "activation_key": "BBC-GEMS-••••-••••-9Q4R",
      "key_fingerprint": "sha256:ab12cd34..."
    }
  ]
}
```

### Fail

```http
POST /api/v1/minecraft/fulfillments/{fulfillment_id}/fail
```

Body:

```json
{
  "server_id": "allthemons-01",
  "error_code": "unknown_sku",
  "error_message": "invalid product"
}
```

## Assinatura HMAC

Canonical request:

```text
METHOD + "\n" +
PATH + "\n" +
TIMESTAMP + "\n" +
NONCE + "\n" +
SHA256(RAW_REQUEST_BODY)
```

Regras:

* o body assinado é o corpo bruto;
* o nonce precisa ser criptograficamente forte;
* timestamp é em segundos Unix;
* comparação da assinatura é em tempo constante;
* replay é rejeitado.

## Assinatura de resposta

O Rails também assina a resposta para o claim com:

* `X-EasyVip-Response-Timestamp`
* `X-EasyVip-Response-Signature`

Canonical da resposta:

```text
RESPONSE_TIMESTAMP + "\n" +
ORIGINAL_NONCE + "\n" +
HTTP_STATUS + "\n" +
SHA256(RAW_RESPONSE_BODY)
```

O EasyVip valida a assinatura antes de processar o payload.

## Idempotência e recuperação

* o mesmo `fulfillment_id` e `line_item_id` nunca geram duas chaves;
* se o processo cair depois de criar a chave e antes de confirmar, o próximo polling reencontra a linha no SQL e reenviará a mesma confirmação;
* se o Rails reenviar o mesmo fulfillment, o EasyVip retorna o mesmo resultado local;
* se o payload mudar para o mesmo `fulfillment_id`, o pedido é marcado como conflito e a falha é comunicada ao Rails;
* o jogador não precisa estar online;
* o polling roda fora da thread principal.

## Erros comuns

| Código | Significado |
|---|---|
| `fulfillment_disabled` | fulfillment desativado |
| `sql_unavailable` | SQL não saudável ou não habilitado |
| `invalid_response_signature` | resposta Rails adulterada ou inválida |
| `unknown_sku` | SKU inexistente no `webstore.toml` |
| `missing_tier_id` | produto VIP sem tier |
| `unknown_tier` | tier inexistente |
| `missing_reward_key_id` | produto reward sem chave base |
| `unknown_reward_key` | reward key inexistente |
| `unsupported_quantity` | quantidade fora do contrato atual |
| `idempotency_conflict` | mesmo fulfillment com payload diferente |

## Troubleshooting

1. Verifique se `integrations.sql_enabled = true`.
2. Confirme se `webstore.fulfillment.enabled = true`.
3. Confirme se `secret_env` e `token_env` estão resolvidos.
4. Verifique `config/easyvip/data/webstore_fulfillment.log`.
5. Use `/easyvip admin webstore status` para ver o estado atual.
6. Se a assinatura falhar, valide o canonical string e o timestamp.

## Checklist de deploy

* SQL ativo e saudável;
* `webstore.toml` com `fulfillment.enabled = true`;
* `server_id` alinhado com o Rails;
* segredos configurados e rotacionados;
* SKUs cadastrados localmente;
* logs e auditoria sem chave completa;
* polling confirmado via `/easyvip admin webstore status`;
* testes `./gradlew test` e `./gradlew clean buildAll` verdes.
