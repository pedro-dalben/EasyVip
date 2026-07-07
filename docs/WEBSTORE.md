# Integracao Loja Web (WebStore Sync)

Este modulo envia dados do jogador para uma loja web (Rails) quando ele entra no servidor, permitindo que a loja crie ou atualize perfis automaticamente.

O fluxo de fulfillment por polling ficou separado em [`docs/integrations/webstore-fulfillment.md`](integrations/webstore-fulfillment.md).

---

## 1. Configuracao

O arquivo de configuracao e gerado automaticamente em:

```
config/easyvip/webstore.toml
```

```toml
enabled = false
api_url = "http://localhost:3000"
api_token = ""
sync_on_register = true
sync_on_login = true
sync_on_join = true
sync_on_nick_change = true
retry_max_attempts = 3
retry_delay_seconds = 5
```

### Campos

| Campo | Tipo | Padrao | Descricao |
|---|---|---|---|
| `enabled` | boolean | false | Liga/desliga a integracao |
| `api_url` | string | `http://localhost:3000` | URL base da loja (sem barra no final) |
| `api_token` | string | `""` | Token Bearer para autenticacao |
| `sync_on_register` | boolean | true | Sincroniza no registro do jogador |
| `sync_on_login` | boolean | true | Sincroniza no login |
| `sync_on_join` | boolean | true | Sincroniza ao entrar no servidor |
| `sync_on_nick_change` | boolean | true | Sincroniza na troca de nick |
| `retry_max_attempts` | int | 3 | Maximo de tentativas em caso de erro transiente |
| `retry_delay_seconds` | int | 5 | Delay inicial entre retries (exponential backoff) |

> **Importante:** `api_token` deve corresponder ao `MINECRAFT_API_TOKEN` configurado no Rails.

---

## 2. Log dedicado

Todas as operacoes de sync sao registradas em:

```
config/easyvip/data/webstore_sync.log
```

### Formato das linhas

```
[2026-06-30 12:00:00] <OPERACAO> | <dados>
```

### Operacoes

| Operacao | Significado |
|---|---|
| `SYNC_OK` | Sync realizado com sucesso (HTTP 200 ou 201) |
| `SYNC_FAIL` | Sync falhou apos todas as tentativas |
| `SYNC_ATTEMPT` | Tentativa individual de sync |
| `SYNC_RETRY` | Erro transiente (5xx) — vai tentar de novo |
| `SYNC_NET_ERR` | Erro de rede/timeout |
| `SYNC_401` | Token rejeitado pela loja |
| `SYNC_422` | Payload invalido |
| `SYNC_UNEXPECTED` | HTTP status code inesperado |
| `CHALLENGE_OK` | Challenge registrado com sucesso |
| `CHALLENGE_FAIL` | Falha ao registrar challenge |
| `CHALLENGE_401` | Token rejeitado no challenge |
| `CHALLENGE_ERR` | Erro inesperado no challenge |

### Exemplos

```
[2026-06-30 12:00:00] SYNC_OK | pedrops | e309ad92-e421-420a-8bf3-3df86db3e660 | HTTP 200
[2026-06-30 12:01:00] SYNC_FAIL | joao | a1b2c3d4-... | Sync failed after 3 tentativas. Ultimo erro: HTTP 502
[2026-06-30 12:02:00] SYNC_401 | admin | f1a2b3c4-... | Token invalido — verifique MINECRAFT_API_TOKEN
[2026-06-30 12:03:00] SYNC_RETRY | test | d4e5f6a7-... | HTTP 503 | attempt 1/3
[2026-06-30 12:04:00] CHALLENGE_OK | e309ad92-e421-420a-8bf3-3df86db3e660
```

Quando `debug = true` em `common.toml`, as mensagens tambem sao exibidas no console.

---

## 3. Como funciona

### Sincronizacao ao entrar

Quando um jogador entra no servidor, o mod dispara automaticamente:

```
POST /api/v1/minecraft/players/sync
Authorization: Bearer <api_token>
Content-Type: application/json

{
  "minecraft_uuid": "e309ad92-e421-420a-8bf3-3df86db3e660",
  "username": "pedrops",
  "ip_address": "203.0.113.10"
}
```

A chamada e **assincrona** — nao bloqueia o servidor nem o login do jogador.

### Tratamento de respostas

| HTTP | Acao |
|---|---|
| 200 / 201 | Sucesso. Log `SYNC_OK`. |
| 401 | Erro de configuracao. Log `SYNC_401`. Sem retry. |
| 422 | Erro de payload. Log `SYNC_422`. Sem retry. |
| 500 / 502 / 503 / 504 | Erro transiente. Log `SYNC_RETRY`. Tenta novamente com backoff. |
| Timeout / erro de rede | Log `SYNC_NET_ERR`. Tenta novamente com backoff. |

O retry usa exponential backoff: `delay * 2^(attempt-1)`.  
Exemplo com `retry_delay_seconds = 5`: tentativa 2 espera 10s, tentativa 3 espera 20s.

Erros graves (401, 422, falha apos todas as tentativas) tambem sao registrados no audit log do EasyVip (`PersistenceManager.log`).

---

## 4. Comando `/link`

Gera um codigo de 8 caracteres para vincular a conta web:

```
/link
```

O plugin:
1. Gera um codigo aleatorio (ex: `A7X2K9M1`)
2. Calcula o SHA-256 do codigo
3. Envia o digest para a loja via `POST /api/v1/minecraft/challenges`
4. Mostra o codigo na tela do jogador

O codigo puro nunca e persistido — apenas o hash vai para a loja.

---

## 5. Arquivos relacionados

| Arquivo | Funcao |
|---|---|
| `common/.../webstore/WebStoreConfig.java` | Modelo de configuracao |
| `common/.../webstore/WebStoreSyncService.java` | Servico HTTP assincrono com retry |
| `common/.../config/EasyVipConfig.java` | Carrega `webstore.toml` |
| `neoforge/.../NeoForgeEasyVipMod.java` | Hook do evento de login |
| `config/easyvip/webstore.toml` | Configuracao gerada |
| `config/easyvip/data/webstore_sync.log` | Log dedicado de sync |
| `config/easyvip/data/webstore_fulfillment.log` | Log dedicado de fulfillment |

---

## 6. Fluxo completo

```
Jogador entra no servidor
       │
       ▼
NeoForgeEasyVipMod.onPlayerLoggedIn()
       │
       ▼
WebStoreSyncService.syncPlayer(uuid, name, ip)  ← assíncrono
       │
       ├── HTTP 200/201 → log SYNC_OK
       ├── HTTP 401     → log SYNC_401, audit log
       ├── HTTP 422     → log SYNC_422, audit log
       ├── HTTP 5xx     → retry com backoff, depois SYNC_FAIL + audit log se esgotar
       └── timeout/rede → retry com backoff, depois SYNC_FAIL + audit log se esgotar
```
