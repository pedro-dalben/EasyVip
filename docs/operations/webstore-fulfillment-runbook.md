# Runbook Operacional - WebStore Fulfillment

Este runbook é para operação do BigBangCraft com EasyVip em modo de polling.
O fluxo não abre porta HTTP de fulfillment, não usa RCON e não aceita callback direto do Rails.

## Objetivo operacional

- Habilitar ou desabilitar o fulfillment com segurança.
- Rotacionar token e HMAC.
- Pausar o processamento durante incidentes.
- Inspecionar status, logs e SQL.
- Tratar pedidos pagos sem chave, chave já criada, retries e falhas.

## Habilitar fulfillment

1. Edite `config/easyvip/webstore.toml`.
2. Configure:
   - `webstore.enabled = true` se o sync também for usado.
   - `[fulfillment].enabled = true`
   - `[fulfillment].server_id`
   - `[fulfillment].key_id`
   - `[fulfillment].key_prefix`
   - `token` ou `token_env`
   - segredo HMAC via `secret_env` ou `fulfillment.keys.<id>.secret`
3. Garanta `integrations.sql_enabled = true` em `config/easyvip/integrations.toml`.
4. Confirme que o banco local do servidor está separado do outro servidor.
5. Reinicie o servidor ou rode `/easyvip reload`.

## Desabilitar fulfillment

1. Edite `config/easyvip/webstore.toml`.
2. Defina `[fulfillment].enabled = false`.
3. Rode `/easyvip reload` ou reinicie.

Isso interrompe novos polls. Não apaga filas nem chaves já persistidas.

## Configurar segredo

Modelo recomendado:

```toml
[fulfillment]
secret_env = "EASYVIP_FULFILLMENT_ALLTHEMONS_SECRET"
token_env = "EASYVIP_FULFILLMENT_TOKEN"

[fulfillment.keys.easyvip-allthemons-v1]
secret_env = "EASYVIP_FULFILLMENT_ALLTHEMONS_SECRET"
```

Regras:

- `fulfillment.keys.<id>.secret` vence `secret_env`.
- `key_id` escolhe qual segredo é usado.
- O token Bearer pode vir de `token` ou `token_env`.

## Rotacionar token e HMAC

### Token

1. Gere o novo Bearer token no Rails.
2. Atualize `fulfillment.token` ou a variável apontada por `token_env`.
3. Atualize o Rails com o mesmo valor.
4. Rode `/easyvip reload`.

### HMAC

1. Adicione uma nova entrada em `[fulfillment.keys.<novo_id>]`.
2. Publique o novo segredo no ambiente.
3. Troque `fulfillment.key_id` para o novo id.
4. Atualize o Rails para aceitar o novo `key_id`.
5. Mantenha o segredo antigo enquanto houver fulfillments antigos em voo.

## Pausar em incidente

Ordem recomendada:

1. Defina `[fulfillment].enabled = false`.
2. Rode `/easyvip reload`.
3. Confirme `/easyvip admin webstore status`.
4. Se o incidente for no Rails, pause também a geração de novos fulfillments lá.

Não pause editando tabelas manualmente.

## Verificar status

Use:

```text
/easyvip admin webstore status
```

Interpretação prática:

- `state=running` ou `state=idle`: fluxo ativo.
- `state=empty`: nenhum fulfillment pendente no último ciclo.
- `state=retry`: houve erro transitório e o scheduler vai tentar de novo.
- `state=sql_unavailable`: banco local indisponível.
- `state=disabled`: configuração incompleta ou fulfillment desligado.
- `state=scheduler_error`: falha inesperada no loop.

O resumo também mostra `server_id`, `key_id`, timestamps e contadores.

## Consultar logs

### Fulfillment

Arquivo:

```text
config/easyvip/data/webstore_fulfillment.log
```

Exemplos de busca:

```bash
tail -f config/easyvip/data/webstore_fulfillment.log
grep -E 'COMPLETE_OK|FAIL_OK|FAIL_RETRY|RETRY|ERROR|UNAVAILABLE' config/easyvip/data/webstore_fulfillment.log
```

### Sync

Arquivo:

```text
config/easyvip/data/webstore_sync.log
```

### Auditoria SQL

Se SQL estiver ativo, eventos também podem aparecer em `easyvip_audit_logs`.

## Identificar erros

### Autenticação

Sinais:

- `auth_failed`, `invalid_response_signature`, `server_mismatch` ou `401` no Rails.
- Erros de assinatura no log do fulfillment.

Ação:

1. Verifique `token` e `token_env`.
2. Verifique `key_id`.
3. Verifique o segredo HMAC resolvido.
4. Verifique o relógio do servidor.

### SKU

Sinais:

- `unknown_sku`
- `invalid_kind`
- `missing_tier_id`
- `missing_reward_key_id`
- `unsupported_quantity`

Ação:

1. Confirme o SKU em `[products.<sku>]`.
2. Confirme o tipo do produto local.
3. Se for `unsupported_quantity`, corrija o pedido no Rails e expanda em múltiplos itens.

### SQL

Sinais:

- `sql=unavailable`
- `state=sql_unavailable`
- stacktrace de conexão

Ação:

1. Verifique `integrations.sql_enabled = true`.
2. Verifique `sql_url`, `sql_username` e `sql_password`.
3. Confirme conectividade com o banco.

## Pedido pago mas sem chave

1. Consulte `webstore_fulfillment.log`.
2. Verifique a linha de `webstore_fulfillments`.
3. Se o status for `awaiting_complete`, aguarde o próximo cycle.
4. Se houver `lease_expired`, deixe o EasyVip fazer novo claim.
5. Se o status for `invalid_sku` ou `unsupported_quantity`, corrija o catálogo ou o pedido no Rails.

SQL de apoio:

```sql
SELECT fulfillment_id, order_id, origin_server_id, status, failure_code, error_message, claim_token, lease_expires_at, claimed_at, completed_at, updated_at
FROM webstore_fulfillments
WHERE fulfillment_id = ?;
```

```sql
SELECT line_item_id, fulfillment_id, product_sku, quantity, key_code, key_fingerprint, status, created_at, updated_at
FROM webstore_fulfillment_items
WHERE fulfillment_id = ?;
```

## Chave criada mas ainda não entregue no Rails

Sinais:

- `webstore_fulfillment_items.key_code` já preenchido.
- `webstore_fulfillments.status = 'awaiting_complete'`.
- Ainda não houve `COMPLETE_OK`.

Ação:

1. Não gere outra chave.
2. Não edite a linha manualmente.
3. Deixe o scheduler reenviar o mesmo fulfillment.
4. Se o lease expirou, o Rails deve devolver `lease_expired` e o EasyVip fará novo claim com a mesma key local.

## Retries excessivos

Sinais:

- `state=retry`
- vários `RETRY` seguidos
- `last_error` mudando em sequência

Ação:

1. Descubra se é rede, auth, SQL ou contrato inválido.
2. Se for incidente externo, desabilite o fulfillment temporariamente.
3. Se for SKU ou quantidade, corrija o catálogo ou o pedido.
4. Se for assinatura, compare `key_id`, segredo e relógio.

## Rollback

1. Desative `fulfillment.enabled`.
2. Faça rollback do deploy do EasyVip.
3. Valide que `WebStoreFulfillmentService` não voltou a iniciar.
4. Confirme que os fulfillments já persistidos continuam no SQL.

## Abrir ticket / revisão manual

Abra revisão manual quando:

- o Rails marcou conflito de lease expirado repetidamente;
- há pedido pago sem fulfillment por mais de um ciclo;
- existe suspeita de SKU inválido ou catálogo divergente;
- há suspeita de replay ou assinatura quebrada.

Inclua no ticket:

- `server_id`
- `fulfillment_id`
- `line_item_id`
- `failure_code`
- horário do incidente
- trecho do log sem segredos

## Validar após deploy

Checklist:

- `state=running` ou `state=idle`
- `sql=healthy`
- claim assinado aceito pelo Rails
- `complete` devolvido com sucesso
- nenhuma activation key completa apareceu em log
- o mesmo fulfillment não gerou chave duplicada

## Suspeita de vazamento de segredo

1. Pause o fulfillment.
2. Rotacione o token Bearer.
3. Rotacione o HMAC com novo `key_id`.
4. Revogue o segredo anterior no Rails.
5. Revalide com um pedido de teste.

## O que nunca fazer manualmente

- Não inserir activation key direta na tabela sem passar pelo fluxo.
- Não editar `claim_token` para forçar sucesso.
- Não reprocessar pedidos alterando `fulfillment_id`.
- Não compartilhar um banco único entre servidores sem isolamento completo.
- Não reativar o listener HTTP legado.
- Não expor segredos, nonces completos ou signatures nos logs.

