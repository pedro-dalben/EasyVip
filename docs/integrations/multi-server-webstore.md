# WebStore Multi-Server

Este documento descreve o padrão oficial para operar a WebStore com dois servidores EasyVip independentes:

- `AllTheMons`
- `Cobbleverse`

O objetivo é separar identidade técnica, segredos e banco local por servidor, sem abrir listener de entrada.

## Topologia

```text
EasyVip AllTheMons  ─┐
                     ├── HTTPS + HMAC -> WebStore Rails
EasyVip Cobbleverse ─┘
```

Cada instância do EasyVip:

- faz polling HTTPS para o Rails;
- assina requests com o seu próprio `key_id` e segredo;
- usa `server_id` próprio;
- grava fulfillment em banco separado;
- gera keys com prefixo próprio;
- não compartilha ledger operacional com o outro servidor.

## Identidade técnica por servidor

### AllTheMons

```toml
[fulfillment]
enabled = true
server_id = "allthemons"
key_id = "easyvip-allthemons-v1"
key_prefix = "ATM-"
secret_env = "EASYVIP_FULFILLMENT_ALLTHEMONS_SECRET"
poll_interval_seconds = 15
claim_limit = 20
```

### Cobbleverse

```toml
[fulfillment]
enabled = true
server_id = "cobbleverse"
key_id = "easyvip-cobbleverse-v1"
key_prefix = "CV-"
secret_env = "EASYVIP_FULFILLMENT_COBBLEVERSE_SECRET"
poll_interval_seconds = 15
claim_limit = 20
```

## Regras obrigatórias

- `server_id` precisa ser único por instância.
- `key_id` precisa ser único por instância.
- O segredo HMAC deve ser específico por servidor.
- O Rails só pode devolver fulfillments do mesmo `server_id`.
- O EasyVip rejeita fulfillments de outro servidor.
- `quantity` precisa ser `1` por item.
- Uma key não representa múltiplas unidades de compra.

## Catálogo de produtos

O Rails expande compras maiores em múltiplos `FulfillmentItem`.
Exemplo: compra de `3 x gems_50` precisa virar três itens com `line_item_id` distintos.

O EasyVip recebe apenas:

- `fulfillment_id`
- `line_item_id`
- `product_sku`
- `quantity`
- `minecraft_uuid`
- `minecraft_username`
- `server_id`

O Rails não escolhe:

- `commands`
- `actions`
- `NBT`
- `item_id`
- `tier_id`
- `reward_key_id`
- `duration`
- `max_uses`
- `expiration`

## Sync multi-servidor

O sync de presença continua ativo e agora inclui:

```json
{
  "minecraft_uuid": "uuid-local-do-servidor",
  "username": "PedropsRei",
  "canonical_username": "pedropsrei",
  "server_id": "allthemons",
  "identity_status": "observed"
}
```

Regras:

- Esse payload é apenas presença observada.
- Ele não prova propriedade de conta.
- Ele não concede pedidos ou chaves.
- A confirmação de propriedade global é responsabilidade do EasyLogin após login ou registro.

## Banco de dados

Recomendação oficial:

```text
MySQL físico compartilhado permitido
database/schema separado por servidor
usuário SQL separado por servidor
```

Exemplo:

```text
easyvip_allthemons
easyvip_cobbleverse
```

Não use uma única tabela de fulfillments, keys e VIPs para os dois servidores sem namespace completo.
O ledger local precisa continuar separado por servidor.

## Persistência e isolamento

O fulfillment grava:

- `origin_server_id`
- `fulfillment_id`
- `line_item_id`
- `claim_token`
- `lease_expires_at`

Isso permite:

- retomar após restart;
- reaproveitar a mesma key quando o lease expira;
- evitar duplicação de key por `line_item_id`;
- auditar qual servidor originou o item.

## Operação do Rails

O Rails deve:

- filtrar por `server_id`;
- devolver `claim_token` e `lease_expires_at`;
- tratar quantity > 1 como múltiplos `FulfillmentItem`;
- aceitar callbacks `complete` e `fail` assinados;
- validar assinatura da resposta também.

## Troubleshooting rápido

### Fulfillment do servidor errado

Sinais:

- `server_mismatch`
- nada foi gravado como key nova

Ação:

- revisar `server_id` do Rails;
- revisar `server_id` do EasyVip;
- revisar a rota de claim da fila.

### Prefixo errado

Sinais:

- keys geradas fora do padrão esperado

Ação:

- revisar `[fulfillment].key_prefix`.

### Secrets misturados

Sinais:

- erro de assinatura ou `invalid_response_signature`

Ação:

- revisar `key_id`;
- revisar `secret_env`;
- revisar token do server correto.

## Validação depois do deploy

Checklist por servidor:

- `server_id` confere com o Rails.
- `key_id` confere com o ambiente.
- `key_prefix` confere com o servidor.
- `sql=healthy` no `/easyvip admin webstore status`.
- claim aceita no Rails.
- complete aceita no Rails.
- o mesmo fulfillment não gera duas keys.
