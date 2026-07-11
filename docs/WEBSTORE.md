# WebStore

Esta página resume a integração WebStore do EasyVip e aponta para a documentação operacional correta.

## Visão geral

O projeto possui dois fluxos separados:

- **Sync**: `EasyVip -> Rails`
- **Fulfillment**: polling HTTPS do EasyVip para o Rails

O EasyVip não expõe listener HTTP de fulfillment e não usa RCON nesse fluxo.

## Sync

O sync envia presença observada do jogador para o Rails e continua compatível com o fluxo de `/link`.
O payload atual inclui:

- `minecraft_uuid`
- `username`
- `canonical_username`
- `server_id`
- `identity_status`

Documentação:

- [`docs/integrations/webstore-fulfillment.md`](integrations/webstore-fulfillment.md)
- [`docs/integrations/multi-server-webstore.md`](integrations/multi-server-webstore.md)
- [`docs/operations/webstore-fulfillment-runbook.md`](operations/webstore-fulfillment-runbook.md)

## Fulfillment

O fulfillment é orientado por:

- `server_id`
- `key_id`
- `claim_token`
- `lease_expires_at`
- SQL local por servidor
- `key_prefix` por servidor

Consulte:

- [`docs/integrations/webstore-fulfillment.md`](integrations/webstore-fulfillment.md)
- [`docs/integrations/multi-server-webstore.md`](integrations/multi-server-webstore.md)
- [`docs/operations/webstore-fulfillment-runbook.md`](operations/webstore-fulfillment-runbook.md)

