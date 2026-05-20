# easyVip — Documento de Handoff para Continuação

> **Destinatário:** IA assistente sem contexto anterior  
> **Data de geração:** 2026-05-20  
> **Status geral:** Em desenvolvimento — Fases 1–3 concluídas, Fase 4 parcialmente concluída

---

## 1. O que é o easyVip?

**easyVip** é um mod para **Minecraft 1.21.1** escrito em **Java**, de uso **exclusivamente server-side** (o jogador com cliente vanilla consegue se conectar sem instalar o mod).

O mod implementa um sistema completo de **gerenciamento de VIPs** para servidores Minecraft:

- VIPs temporários ou permanentes com tiers configuráveis
- Ativação por chaves geradas pelo admin (semelhante a gift keys)
- Pacotes de recompensa com suporte a variantes (o jogador escolhe uma opção)
- Integração opcional com **FTB Ranks** e **LuckPerms** (detectados em runtime; sem dependência obrigatória)
- Configuração via arquivos TOML customizados (parser próprio, sem lib externa)
- Persistência assíncrona em JSON com escrita atômica e backup automático
- Comando `/easyvip` com subcomandos para jogadores e admins

---

## 2. Localização do Projeto

```
/home/pedro/Documentos/java/EasyVip/
```

---

## 3. Versões e Dependências

| Item | Versão |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.226 |
| Fabric Loader | 0.16.14 (placeholder por enquanto) |
| Fabric API | 0.116.11+1.21.1 (placeholder) |
| Parchment Mappings | 2024.11.17 (MC 1.21.1) |
| LuckPerms API | 5.4 (`compileOnly`) |
| FTB Ranks | CurseForge artifact `314905:6431744` (`compileOnly`) |
| FTB Library | CurseForge artifact `404465:7746959` (`compileOnly`) |
| Gradle Plugin | `net.neoforged.moddev` 2.0.141 |

**Regra crítica:** FTB Ranks e LuckPerms são `compileOnly`. Eles NUNCA devem ser dependência obrigatória em runtime. A detecção é feita via `Class.forName(...)` em `PermissionBridge.java`.

---

## 4. Estrutura de Módulos

```
EasyVip/
├── build.gradle           ← Root build com configuração de repositórios
├── settings.gradle        ← Inclui módulos :common, :neoforge, :fabric
├── gradle.properties      ← Versões e IDs do projeto
├── common/                ← Lógica compartilhada entre loaders
│   └── build.gradle       ← Plugin moddev, dependências compileOnly
├── neoforge/              ← Adapter NeoForge (FOCO ATUAL)
│   └── build.gradle       ← Inclui sourceSets do :common no JAR final
└── fabric/                ← Placeholder Fabric (implementar DEPOIS)
    └── build.gradle
```

O JAR final publicado é gerado **apenas pelo módulo `neoforge`**, que inclui os sources do `common` via:
```gradle
tasks.named('jar', Jar) {
    from project(':common').sourceSets.main.output
}
```

---

## 5. Pacote Java Base

```
br.com.pedrodalben.easyvip
```

Todos os arquivos do módulo `common` ficam em:
```
common/src/main/java/br/com/pedrodalben/easyvip/
```

---

## 6. O que já foi implementado (Fases 1–3 + parcial da 4)

### Fase 1 — Build & Estrutura ✅
- `build.gradle`, `settings.gradle`, `gradle.properties` (root)
- `common/build.gradle`
- `neoforge/build.gradle`
- `neoforge/src/main/templates/META-INF/neoforge.mods.toml`
- `fabric/build.gradle` (placeholder)
- `fabric/src/main/templates/fabric.mod.json` (placeholder)

---

### Fase 2 — Configuração & TOML Parser ✅

Todos os arquivos abaixo estão em `common/src/main/java/br/com/pedrodalben/easyvip/config/`:

#### `TomlParser.java`
Parser TOML leve e self-contained. Suporta apenas o subconjunto necessário para o easyVip (chave=valor, arrays, tabelas). **Nunca use biblioteca TOML externa** — isso causaria conflitos de classloading em modpacks como AllTheMods.

#### `TomlWriter.java`
Writer TOML simples. Gera arquivos de configuração padrão se não existirem.

#### `EasyVipConfig.java`
Gerenciador central de configurações. Carrega e expõe:

| Config file | Classe interna | Descrição |
|---|---|---|
| `common.toml` | `CommonConfig` | Comportamento geral (comprimento de chave, modos de ativação, allowlist de comandos, etc.) |
| `messages.toml` | `MessagesConfig` | Todas as mensagens do mod com suporte a `&` como cor |
| `tiers.toml` | `TiersConfig` + `VipTierDefinition` | Definição dos tiers VIP (prioridade, duração, ações ao ativar/expirar) |
| `packages.toml` | `PackagesConfig` + `PackageDefinition` | Pacotes de recompensa com variantes |
| `reward_keys.toml` | `RewardKeysConfig` + `RewardKeyDefinition` | Chaves de recompensa (não-VIP) |
| `integrations.toml` | `IntegrationsConfig` | Flags de FTB Ranks, LuckPerms, SQL |

**Método importante:** `EasyVipConfig.validate()` retorna `List<String>` com erros de configuração detectados (usado pelo comando `/easyvip config validate`).

---

### Fase 3 — Modelos de Domínio & Serviços ✅

#### Models (`model/`)

| Arquivo | Responsabilidade |
|---|---|
| `PlayerVipRecord.java` | Registro de um tier VIP para um jogador (tierId, startTime, expiryTime, active, pendingActivateActions) |
| `PlayerVipRegistry.java` | Mapa de todos os VIPs de um jogador (UUID → `PlayerVipRecord`) |
| `KeyRecord.java` | Dados de uma chave (code, type, tierId/rewardKeyId, maxUses, usedCount, usedBy, boundPlayer, expiryTime) |
| `PendingVariantSelection.java` | Seleção pendente de variante de pacote (playerUuid, packageId, variants) |
| `AuditLogRecord.java` | Entrada de log de auditoria admin |

#### Persistência (`persistence/`)

| Arquivo | Responsabilidade |
|---|---|
| `PersistenceManager.java` | Salva/carrega dados em JSON. Thread-safe com `ReentrantReadWriteLock`. Escrita assíncrona via `ExecutorService` dedicado. Escrita atômica com `.tmp` + `ATOMIC_MOVE`. Backup automático (`.bak`). Recuperação em caso de arquivo corrompido. |

**Arquivos JSON gerados em runtime:**
- `<configDir>/data/vips.json` + `vips.json.bak`
- `<configDir>/data/keys.json` + `keys.json.bak`
- `<configDir>/data/pending_variants.json` + `.bak`
- `<configDir>/data/audit_logs.json` + `.bak`

**API pública do PersistenceManager:**
- `getPlayerVips(UUID)`, `updatePlayerVips(UUID, registry)` 
- `getKey(code)`, `putKey(record)`, `removeKey(code)`, `getAllKeys()`
- `getPendingVariants(UUID)`, `addPendingVariant(...)`, `removePendingVariant(...)`
- `log(operator, action, details)`

> ⚠️ **Gap identificado:** `PersistenceManager` não possui método `getAuditLogs()`. Precisa ser adicionado para o comando `/easyvip admin audit` funcionar.

#### Executor de Ações (`action/`)

| Arquivo | Responsabilidade |
|---|---|
| `ActionExecutor.java` | Executa ações definidas em TOML (give_item, give_effect, give_experience, send_message, broadcast_message, run_server_command, run_player_command, give_package, set_scoreboard_tag, remove_scoreboard_tag, add_to_team, remove_from_team, give_permission_flag_internal, remove_permission_flag_internal, custom_event_hook). |

**Segurança crítica:** O tipo `run_server_command` verifica se o comando começa com algum prefixo da `commandAllowlist` em `common.toml`. Se `commandAllowlistEnabled = true` e o comando não estiver na lista, ele é **bloqueado com log de erro**.

`ActionExecutor.resolvePlaceholders(text, context)` substitui `{chave}` por valores e converte `&` em `§`.

#### Serviços (`service/`)

| Arquivo | Responsabilidade |
|---|---|
| `VipService.java` | `addVip(...)`, `removeVip(...)`, `setActiveVip(...)`, `evaluateActiveVip(...)`, `handlePlayerJoin(...)`, `checkExpirations(...)`. Lida com stacking, modo de ativação (extend/replace/stack/deny), e cap de duração. |
| `KeyService.java` | `generateVipKey(...)`, `generateRewardKey(...)`, `redeemKey(...)`, `confirmPending(...)`. Gerencia o fluxo de confirmação (`/easyvip confirm`) e tracking de `usedBy`. |
| `PackageService.java` | `givePackage(...)`, `chooseVariant(...)`. Dá pacotes imediatamente ou cria `PendingVariantSelection`. |
| `ExpirationService.java` | Scheduler via `ScheduledExecutorService`. Roda `VipService.checkExpirations(server)` na thread principal do servidor em intervalos configuráveis. |

**Como `VipService.parseDurationMillis(String)` funciona:**
- `"30d"` → 30 dias em ms
- `"1w"` → 1 semana
- `"permanent"` ou `null` → retorna `-1` (permanente)
- Suporta combinações: `"1d12h"`, `"2w3d"`, etc.

---

### Fase 4 — Platform Bridge & Comando ✅/⚠️

#### Platform (`platform/`)

| Arquivo | Responsabilidade |
|---|---|
| `PlatformBridge.java` | Interface: `hasPermission`, `setPermissionFlagInternal`, `fireCustomEventHook`. A implementação NeoForge ainda não foi criada. |
| `EconomyBridge.java` | Interface: `hasBalance`, `withdraw`, `deposit`. Ainda sem implementação. |
| `PermissionBridge.java` | Detecção runtime de LuckPerms/FTBRanks via `Class.forName`. Fallback para `player.hasPermissions(4)` (OP vanilla). |
| `LuckPermsWrapper.java` | Wrapper seguro para API LuckPerms (try/catch em tudo). |
| `FtbRanksWrapper.java` | Wrapper seguro para FTBRanksAPI (apenas leitura; FTB Ranks não suporta injeção de nodes runtime). |

#### Comandos (`command/`)

| Arquivo | Status |
|---|---|
| `EasyVipCommands.java` | **Escrita completa, mas com 1 pendência** (ver abaixo) |

**Árvore de comandos implementada:**
```
/easyvip
├── use <key>                              → jogador usa chave
├── confirm                                → confirma ativação de chave
├── info [player]                          → mostra VIPs ativos
├── select <tier>                          → escolhe tier ativo
├── variant choose <package> <variant>     → escolhe variante de pacote
└── admin                                  → requer easyvip.admin
│   ├── addvip <player> <tier> <duration>
│   ├── removevip <player> <tier>
│   ├── generate vip <tier> <duration> [max_uses] [bound_player]
│   ├── generate reward <reward_key_id> [max_uses] [bound_player]
│   ├── givepackage <player> <package_id>
│   └── audit [page]                       ← ⚠️ INCOMPLETO (ver pendências)
└── config
    ├── reload
    └── validate
```

**Nós de permissão usados:**
- `easyvip.use` — usar comandos de jogador
- `easyvip.admin` — usar comandos admin

---

## 7. O que FALTA implementar

### ⚠️ Pendências imediatas (antes de compilar)

#### 7.1 `PersistenceManager.getAuditLogs()` — FALTANDO

O campo `auditLogs` é `private static`. O método `executeAudit()` em `EasyVipCommands.java` tem um placeholder/stub vazio. É necessário adicionar ao `PersistenceManager`:

```java
public static List<AuditLogRecord> getAuditLogs() {
    LOCK.readLock().lock();
    try {
        return new ArrayList<>(auditLogs);
    } finally {
        LOCK.readLock().unlock();
    }
}
```

E implementar `executeAudit()` em `EasyVipCommands.java` usando esse método (paginação de 10 entradas por página).

#### 7.2 `EasyVipCommands.java` — Import de `AuditLogRecord` faltando

A classe já usa `AuditLogRecord` mas precisa verificar se o import está presente:
```java
import br.com.pedrodalben.easyvip.model.AuditLogRecord;
```

---

### 🔴 Fase 5 — NeoForge Loader Adapter (PRIORIDADE)

Estes arquivos precisam ser **criados em** `neoforge/src/main/java/br/com/pedrodalben/easyvip/`:

#### 5.1 `NeoForgeEasyVipMod.java`
Classe principal anotada com `@Mod("easyvip")`. Responsabilidades:
- Registrar o evento `RegisterCommandsEvent` → chamar `EasyVipCommands.register(...)`
- Registrar o evento `ServerStartingEvent` → chamar `EasyVipConfig.initialize(path)`, `EasyVipConfig.loadAll()`, `PersistenceManager.initialize(path)`, `ExpirationService.start(server)`
- Registrar o evento `ServerStoppingEvent` → chamar `ExpirationService.stop()`, `PersistenceManager.shutdown()`
- Registrar o evento `PlayerEvent.PlayerLoggedInEvent` → chamar `VipService.handlePlayerJoin(player)`
- Instanciar e injetar um `NeoForgePlatformBridge` no `ActionExecutor`

Exemplo mínimo de estrutura:
```java
@Mod("easyvip")
public class NeoForgeEasyVipMod {
    public NeoForgeEasyVipMod(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.register(this);
    }
    // handlers via @SubscribeEvent
}
```

#### 5.2 `NeoForgePlatformBridge.java`
Implementação de `PlatformBridge` para NeoForge. Delega para `PermissionBridge`.

```java
public class NeoForgePlatformBridge implements PlatformBridge {
    @Override
    public boolean hasPermission(ServerPlayer player, String permission) {
        return PermissionBridge.hasPermission(player, permission);
    }
    @Override
    public void setPermissionFlagInternal(ServerPlayer player, String permission, boolean active) {
        PermissionBridge.setPermission(player, permission, active);
    }
    @Override
    public void fireCustomEventHook(ServerPlayer player, String hook, Map<String, String> context) {
        // TODO: disparar evento customizado via NeoForge EVENT_BUS se necessário
    }
}
```

#### 5.3 Listener de Right-Click em Item Vanilla (Chave VIP física)

O mod suporta um item vanilla (ex.: `minecraft:tripwire_hook`) com `DataComponents.CUSTOM_DATA` contendo o código da chave. Ao clicar com botão direito, o jogador ativa a chave automaticamente.

Evento a usar: `PlayerInteractEvent.RightClickItem` (NeoForge event bus)

Lógica:
1. Obter `ItemStack` da mão do jogador
2. Verificar se o item possui `DataComponents.CUSTOM_DATA`
3. Extrair tag `"easyvip_key"` do CustomData
4. Se existir, chamar `KeyService.redeemKey(player, keyCode, false)`
5. Consumir o item se sucesso (`itemStack.shrink(1)`)

---

### 🟡 Fase 6 — Testes e Verificação

- Executar `./gradlew :neoforge:build` e confirmar compilação sem erros
- Corrigir quaisquer erros de import ou referências quebradas
- Testar in-game com servidor NeoForge 1.21.1

---

### 🟡 Fase 7 — Documentação (baixa prioridade)

- `README.md` explicando instalação, configurações e comandos
- Guia de configuração dos arquivos TOML

---

## 8. Decisões Técnicas Fixas (NÃO alterar)

| Decisão | Motivo |
|---|---|
| **TOML parser próprio** | Evitar conflitos de classloading em modpacks grandes (AllTheMods, ATM) |
| **Sem Fabric por enquanto** | Foco em NeoForge primeiro; estrutura Fabric é placeholder |
| **FTB Ranks e LuckPerms são compileOnly** | Nunca obrigatórios; detecção via `Class.forName` em runtime |
| **Server-side only** | Clientes vanilla devem conseguir conectar sem instalar o mod |
| **Persistência JSON** | Simples e portável; sem banco de dados por padrão |
| **Escrita atômica com ATOMIC_MOVE** | Evitar arquivos corrompidos em crash do servidor |
| **allowlist de comandos** | Prevenir execução de comandos perigosos via ações de tier |
| **`pendingActivateActions` em PlayerVipRecord** | Permite executar ações de ativação quando o jogador entra depois de receber o VIP offline |

---

## 9. Fluxo Principal de Uso (referência)

```
Admin gera chave → KeyRecord salvo em keys.json
Jogador usa /easyvip use EVIP-XXXX
  → KeyService.redeemKey() verifica validade
  → Se confirmBeforeUse=true → pede /easyvip confirm
  → Após confirmação → VipService.addVip()
    → Cria/estende PlayerVipRecord
    → Executa actionsOnActivate via ActionExecutor
    → Avalia tier ativo via evaluateActiveVip()
    → Salva em vips.json (async)
ExpirationService (scheduler) → checkExpirations()
  → Tiers expirados removidos
  → actionsOnExpire executadas
  → vips.json atualizado
```

---

## 10. Próximas ações recomendadas (em ordem de prioridade)

1. **Adicionar `getAuditLogs()` ao `PersistenceManager.java`**
2. **Implementar `executeAudit()` em `EasyVipCommands.java`**
3. **Criar `NeoForgePlatformBridge.java`** no módulo `neoforge`
4. **Criar `NeoForgeEasyVipMod.java`** com os event listeners descritos na seção 7.3
5. **Criar listener de right-click** para ativação física de chaves
6. **Executar `./gradlew :neoforge:build`** e corrigir erros de compilação
7. Testar em servidor de desenvolvimento NeoForge 1.21.1
