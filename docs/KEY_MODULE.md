# EasyVip Key Module

## Visão Geral

O módulo de chaves gerencia códigos alfanuméricos que jogadores trocam por recompensas (VIP, itens, comandos). Suporta múltiplos tipos de chave, cooldown por jogador, confirmação opcional, rastreamento de instância física e integração com webstore.

**Não há endpoints HTTP REST no mod.** A única comunicação HTTP é feita pelo `WebStoreSyncService` (outbound para loja web, documentado em `WEBSTORE.md`).

---

## Tipos de Chave

| Tipo | Descrição | Geração |
|---|---|---|
| `vip` | Ativa um tier VIP por uma duration | `/easyvip admin generate vip <tier> <duration> [maxUses] [boundPlayer]` |
| `reward` | Entrega recompensas definidas em `reward_keys.toml` (itens, comandos, pacotes) | `/easyvip admin generate reward <rewardKeyId> [maxUses] [boundPlayer]` |
| `custom` | Executa ações arbitrárias (itens, efeitos, comandos, permissões) | `/easyvip admin generate custom <maxUses> [boundPlayer]` |
| `command` | Chave tipo comando vinculada a actions | `/easyvip admin generate command <maxUses> [boundPlayer]` |
| `item` | Chave VIP vinculada a actions | `/easyvip admin generate item <maxUses> [boundPlayer]` |
| `itemstack` | Chave itemstack vinculada a actions | `/easyvip admin generate itemstack <maxUses> [boundPlayer]` |

---

## Ciclo de Vida

```
         gerar código único (SHA-256 + prefixo configurável)
                    │
                    ▼
         persistir (JSON ou SQL) com putKeyIfAbsent
                    │
                    ▼
         jogador usa chave (/easyvip use, /easyvip activate,
         /usekey, /activate, /vip, ou clique físico)
                    │
                    ▼
         preflight: expirado? usado? bound? instância consumida?
                    │
                    ▼
         [se confirmBeforeUse=true → aguarda /easyvip confirm]
                    │
                    ▼
         executar ações (VIP, reward, custom)
                    │
                    ▼
         consumir: ++usedCount + usedBy.add(uuid) + cooldown
                    │
                    ▼
         log de auditoria
```

---

## Comandos

### Player

| Comando | Descrição | Cooldown |
|---|---|---|
| `/easyvip use <code>` | Usa chave de código | sim |
| `/easyvip activate <code>` | Alias de use | sim |
| `/usekey <code>` | Alias | sim |
| `/activate <code>` | Alias | sim |
| `/vip <code>` | Alias | sim |
| `/easyvip confirm` | Confirma chave pendente | sim |
| `/link` | Gera código de 8 chars para vincular conta web | não (webstore) |

### Admin

| Comando | Descrição |
|---|---|
| `/easyvip key list` | Lista chaves registradas (código mascarado) |
| `/easyvip key info <code>` | Detalhes da chave (mascarado) |
| `/easyvip key info <code> reveal` | Detalhes com código revelado |
| `/easyvip key delete <code>` | Remove chave |
| `/easyvip admin generate vip <tier> <duration> [maxUses] [boundPlayer]` | Gera chave VIP |
| `/easyvip admin generate reward <rewardKeyId> [maxUses] [boundPlayer]` | Gera chave reward |
| `/easyvip admin generate custom <maxUses> [boundPlayer]` | Gera chave custom |
| `/easyvip admin giveitemkey <player> <code>` | Dá chave como item físico |
| `/easyvip admin audit [page]` | Log de auditoria |

---

## Segurança

### 1. Geração de Código

- `SecureRandom` (CSPRNG) para geração
- Prefixo configurável + charset + length customizáveis
- Loop com `putKeyIfAbsent` atômico (evita condição de corrida entre "existe?" e "inserir")
- Máximo de 1000 tentativas antes de erro

### 2. Concorrência

- `ConcurrentHashMap<String, Object>` como lock **por código** no `KeyService.redeemKey()`
- Evita race conditions sem usar `String.intern()` (risco de memory leak)
- `PersistenceManager.putKeyIfAbsent()` atômico no SQL (INSERT); JSON usa lock global

### 3. Mascaramento de Código

- `KeySecurity.maskKey()`: exibe apenas 4 primeiros caracteres + bullets
- `KeySecurity.fingerprintKey()`: SHA-256 dos 6 primeiros bytes (para logs)
- `KeySecurity.describeKeyForLog()`: máscara + fingerprint
- Todos os audit logs usam `describeKeyForLog`
- Todos os comandos de list/info usam `maskKey` por padrão; `reveal` opcional

### 4. Allowlist de Comandos

- `run_server_command` e `run_player_command` passam por:
  1. `sanitizeCommand()`: normaliza whitespace, rejeita `; & | \n \r`
  2. `isCommandAllowed()`: prefixos definidos em `common.toml > command_allowlist`
- Ações FTB Ranks usam templates configuráveis que passam pelo mesmo allowlist
- Por padrão: `broadcast` incluso para comandos de ativação de VIP

### 5. Chaves Físicas

- ItemStack com NBT: `easyvip_key` (código), `easyvip_key_instance` (UUID único), marker configurável
- `createPhysicalKeyItem()`: gera UUID aleatório para `easyvip_key_instance` no NBT
- Ao usar: `redeemPhysicalKey(player, code, instanceId)` verifica se instanceId já foi consumido
- Impede clone exploitation: mesmo que jogador copie o item, cada instância só pode ser usada uma vez
- Marker tag configurável evita que item genérico com NBT seja aceito

### 6. Confirmação Antes de Usar

- `confirmBeforeUse = true` em `common.toml`
- Primeiro `/easyvip use` retorna `CONFIRMATION_REQUIRED`, salva pending no mapa
- `/easyvip confirm` executa a chave (mesma thread segura)
- Timeout configurável via `confirmTimeoutSeconds`
- Confirm não consome na segunda chamada de `/use` (só a primeira `/use` que retorna CONFIRMATION_REQUIRED)

### 7. Cooldown

- `commandCooldownTicks` em `common.toml`
- Separado por tipo: USE e CONFIRM
- `isOnCooldown()` é read-only (não atualiza timestamp)
- `markCooldown()` chamado **após** sucesso (não na tentativa)
- Aplica a `/easyvip use`, `/usekey`, `/activate`, `/vip`, `/easyvip confirm`

### 8. Validações de Integridade

**Geração:**
- `maxUses <= 0` → rejeitado
- `tierId` vazio → rejeitado
- `duration` inválida → rejeitado
- `expiryTime < -1` → rejeitado
- `actions` vazias (custom) → rejeitado

**Resgate:**
- Chave não encontrada → `INVALID_KEY`
- Expirada → `EXPIRED`
- `usedCount >= maxUses` → `NO_USES_LEFT`
- Bound a outro player → `BOUND_TO_OTHER`
- Já usada pelo player (exceto reward sem consume) → `ALREADY_USED`
- Instância física já consumida → `ALREADY_USED`
- Dimensão não permitida → `ERROR` (sem consumir)
- Ações falham → `ERROR` (sem consumir)

### 9. Reward sem `consumeOnUse`

- Reward com `consumeOnUse = false` não incrementa `usedCount` nem adiciona `usedBy`
- Apenas atualiza `lastUsedAtBy` (para cooldown)
- Cooldown como única barreira de repetição
- `maxUses` não é consumido

### 10. Log de Auditoria

- Toda operação registrada: geração, resgate, falha, deleção
- Código mascarado + fingerprint (nunca raw code no log)
- Detalhes sanitizados via `KeySecurity.sanitizeAuditDetails()`
- Comando `/easyvip admin audit [page]` para visualização
- Eventos de webstore também logados no audit log

---

## Configuração (`common.toml`)

| Campo | Padrão | Descrição |
|---|---|---|
| `key_length` | 8 | Tamanho do código gerado (sem prefixo) |
| `key_prefix` | `"EVIP-"` | Prefixo do código |
| `key_charset` | `"ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"` | Caracteres permitidos |
| `case_sensitive_keys` | `false` | Se true, códigos diferenciam maiúscula/minúscula |
| `confirm_before_use` | `true` | Exige `/easyvip confirm` antes de executar |
| `confirm_timeout_seconds` | `60` | Tempo para confirmar |
| `command_cooldown_ticks` | `10` | Cooldown entre comandos (20 ticks = 1s) |
| `command_allowlist_enabled` | `true` | Habilita allowlist |
| `command_allowlist` | `["broadcast"]` | Prefixos de comando permitidos |
| `item_key_item_id` | `"minecraft:paper"` | Item usado para chave física |
| `item_key_marker` | `"easyvip_key"` | Tag NBT marker |

---

## Persistência

### JSON (padrão)

- Arquivos em `config/easyvip/data/`
- `keys/` (um arquivo por chave), `audit.json`
- `putKeyIfAbsent()`: verifica existência + atomicidade via `FileChannel.force(true)` + fallback `Files.move`
- `getKey()`: retorna `KeyRecord.copy()` (defensivo)
- `getAllKeys()`: retorna cópias defensivas
- Sem atomicidade verdadeira entre múltiplos arquivos

### SQL (configurável, em desenvolvimento)

- `sql_enabled`, `sql_url`, `sql_username`, `sql_password`
- `putKeyIfAbsent()` via INSERT SQL (atômico por constraint)
- Schema com coluna `consumed_instances_json`
- Migration automática na inicialização

---

## Rastreamento de Instância Física

1. `createPhysicalKeyItem()` gera UUID único em `easyvip_key_instance`
2. `redeemPhysicalKey(player, code, instanceId)` passa instanceId para o resgate
3. `preflightCheck()` verifica `record.isInstanceConsumed(instanceId)`
4. `consumeRecord()` chama `record.markInstanceConsumed(instanceId)`
5. Consumed instances persistidas em `consumedInstances` (Set<String>)

---

## Boas Práticas

- Mantenha `confirmBeforeUse = true` em servidores públicos
- Configure `command_allowlist` com prefixos mínimos necessários
- Use `key_prefix` para identificar visualmente chaves do seu servidor
- Para chaves físicas, configure `item_key_item_id` para um item não trivial
- Revise periodicamente o audit log (`/easyvip admin audit`)
- Nunca compartilhe raw codes em chat público
- Conceda `easyvip.admin` apenas para staff confiável
