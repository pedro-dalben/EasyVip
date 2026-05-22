# EasyVip

easyVip é um mod server-side para Minecraft 1.21.1 que implementa um sistema completo de VIPs, chaves e recompensas para servidores.

O projeto é escrito em Java e está estruturado para NeoForge primeiro, com um módulo Fabric placeholder já criado para evolução futura.

## Visão geral

- VIPs temporários ou permanentes com tiers configuráveis
- Ativação por chaves geradas pelo admin
- Confirmação opcional antes de consumir chaves
- Pacotes de recompensa com variantes
- Integração opcional com LuckPerms e FTB Ranks via comandos seguros
- Persistência em JSON com escrita atômica e backup automático
- Configuração em TOML com parser próprio
- Comando `/easyvip` para jogadores e administradores
- Evento físico para usar chaves em item configurado com `CUSTOM_DATA` e marcador do mod

## Status atual

- Fase 1: build e estrutura concluídas
- Fase 2: configuração e parser TOML concluídos
- Fase 3: modelos de domínio e serviços concluídos
- Fase 4: bridge de plataforma e comandos concluídos
- NeoForge: implementado e compilando
- Fabric: placeholder

## Requisitos

- Java 21
- Minecraft 1.21.1
- NeoForge 21.1.226
- Gradle via wrapper do projeto

## Estrutura do projeto

```text
EasyVip/
├── common/      lógica compartilhada
├── neoforge/    loader principal e integração NeoForge
├── fabric/      placeholder para futuro suporte
├── docs/        handoff e documentação interna
└── build.gradle
```

## Instalação e build

Compilar o projeto completo:

```bash
./gradlew build
```

Compilar apenas o módulo NeoForge:

```bash
./gradlew :neoforge:build
```

Compilar só o Java do módulo NeoForge:

```bash
./gradlew :neoforge:compileJava
```

## Como funciona

### Fluxo principal

1. O admin gera uma chave.
2. A chave é salva em `config/easyvip/data/keys.json`.
3. O jogador usa `/easyvip use <chave>`.
4. Se `confirm_before_use = true`, o mod exige `/easyvip confirm`.
5. Ao confirmar, a chave aplica VIP ou recompensa.
6. VIPs e pendências são persistidos em JSON.
7. Um scheduler processa expiração de VIPs e limpeza de pendências periodicamente.
8. VIPs expirados também são limpos no login e na inicialização do servidor.

### Chave física

O mod lê `DataComponents.CUSTOM_DATA` apenas no item configurado em `common.toml`:

- `item_key_item_id`
- padrão: `minecraft:tripwire_hook`

O `ItemStack` precisa conter:

- `easyvip_item_key = true`
- `easyvip_key = <codigo>`

O marcador é configurável em `common.toml` via `item_key_marker`.

Isso evita aceitar qualquer item genérico com NBT solto.

## Comandos

### Permissões

- `easyvip.use`:
  - acesso aos comandos de jogador
- `easyvip.admin`:
  - acesso aos comandos administrativos e de configuração

### Jogador

#### `/easyvip use <key>`

Usa uma chave VIP ou de recompensa.

Exemplo:

```text
/easyvip use EVIP-ABC123
```

#### `/easyvip confirm`

Confirma a ativação de uma chave quando o modo de confirmação está habilitado.
O comando também respeita `command_cooldown_ticks`.

#### `/easyvip info [player]`

Mostra VIPs ativos e tempo restante.

- Sem argumento: mostra os VIPs do jogador atual
- Com argumento: mostra os VIPs de outro jogador, se o executor tiver permissão admin

#### `/easyvip select <tier>`

Seleciona o VIP ativo do jogador, quando a troca manual estiver permitida.

#### `/easyvip variant choose <package> <variant>`

Escolhe uma variante pendente de pacote.
Se a pendência expirou, o mod remove a seleção e retorna erro amigável.

#### `/easyvip variant pending [player]`

Lista pendências válidas de variante.

#### `/easyvip variant clear <player> [package_id]`

Remove pendências de variante de um jogador.

#### `/easyvip time [player]`

Alias de `/easyvip info`.

#### `/viptime [player]`

Alias direto do comando de tempo do VIP.

#### `/usekey <key>`

Alias direto de `/easyvip use`.

### Admin

#### `/easyvip admin addvip <player> <tier> <duration>`

Adiciona ou estende um VIP para o jogador.

Exemplo:

```text
/easyvip admin addvip Pedro vip 30d
```

#### `/easyvip admin removevip <player> <tier>`

Remove um tier VIP do jogador.

#### `/easyvip admin generate vip <tier> <duration> [max_uses] [bound_player]`

Gera uma chave VIP.

Exemplos:

```text
/easyvip admin generate vip vip 30d
/easyvip admin generate vip vip 30d 5
/easyvip admin generate vip vip 30d 1 Pedro
```

#### `/easyvip admin generate reward <reward_key_id> [max_uses] [bound_player]`

Gera uma chave de recompensa.

Exemplos:

```text
/easyvip admin generate reward welcome_pack
/easyvip admin generate reward welcome_pack 3
/easyvip admin generate reward welcome_pack 1 Pedro
```

#### `/easyvip admin givepackage <player> <package_id>`

Entrega um pacote de recompensa diretamente para um jogador online.

#### `/easyvip admin giveitemkey <player> <code>`

Entrega um item físico de key usando o item configurado em `item_key_item_id`.

#### `/easyvip key list`

Lista todas as keys cadastradas.

#### `/easyvip key info <code>`

Mostra detalhes de uma key.

#### `/easyvip key delete <code>`

Remove uma key.

#### `/easyvip package list`

Lista pacotes cadastrados.

#### `/easyvip package info <id>`

Mostra detalhes de um pacote.

#### `/easyvip active set <player> <tier>`

Seleciona manualmente o VIP ativo de um jogador.

#### `/easyvip admin audit [page]`

Mostra o log de auditoria administrativo.

### Config

#### `/easyvip config reload`

Recarrega os arquivos TOML sem reiniciar o servidor.

#### `/easyvip config validate`

Valida a configuração atual e mostra erros de consistência.

## Arquivos de configuração

Os arquivos ficam em:

```text
config/easyvip/
```

### `common.toml`

Configurações gerais:

- `language`
- `key_length`
- `key_prefix`
- `key_charset`
- `case_sensitive_keys`
- `confirm_before_use`
- `confirm_timeout_seconds`
- `command_cooldown_ticks`
- `allowed_dimensions`
- `deny_dimensions`
- `auto_expire_interval_seconds`
- `default_activation_mode`
- `force_highest_priority_as_active`
- `allow_player_active_selection`
- `variant_selection_timeout_seconds`
- `notify_pending_variant_on_login`
- `item_key_item_id`
- `item_key_marker`
- `log_to_file`
- `debug`
- `command_allowlist_enabled`
- `command_allowlist`

Notas importantes:

- `command_cooldown_ticks` aplica cooldown por jogador em `/easyvip use`, `/usekey` e `/easyvip confirm`
- `allowed_dimensions` permite apenas as dimensões listadas
- `deny_dimensions` bloqueia as dimensões listadas e tem prioridade sobre `allowed_dimensions`
- `variant_selection_timeout_seconds` controla por quanto tempo a escolha de variante fica pendente
- `notify_pending_variant_on_login` só controla o aviso no login; a limpeza de expiradas continua ativa
- `item_key_marker` precisa bater com o marcador gravado no item físico

### `messages.toml`

Todas as mensagens do mod, com suporte a `&` para cores.

Idioma suportado:

- `en-us`
- `pt-br`

O idioma padrão é `en-us`.

### `tiers.toml`

Define os tiers VIP:

- `display_name`
- `priority`
- `default_duration`
- `allow_stacking`
- `activation_mode`
- `max_stack_duration_seconds`
- `color`
- `actions_on_activate`
- `actions_on_expire`
- `actions_on_remove`
- `actions_on_set_active`
- `actions_on_unset_active`

### `packages.toml`

Define pacotes de recompensa e suas variantes.

- `repeatable`
- `cooldown_seconds`

Essas opções são aplicadas na entrega do pacote.

### `reward_keys.toml`

Define recompensas não-VIP geradas por chave.

- `consume_on_use`
- `cooldown_seconds`
- `allowed_dimensions`

Essas opções são aplicadas na ativação da reward key.

### `integrations.toml`

Integrações opcionais:

- `ftb_ranks_enabled`
- `luckperms_enabled`
- `primary_permission_bridge`
- `ftb_ranks_add_command`
- `ftb_ranks_remove_command`
- `ftb_ranks_set_command`
- `sql_enabled`
- `sql_url`
- `sql_username`
- `sql_password`

O fluxo FTB Ranks usa templates de comando e passa pela allowlist de comandos do mod.

## Integração FTB Ranks

O easyVip não depende da API interna do FTB Ranks para mutação de rank.

Ele usa actions seguras com allowlist:

```toml
[security]
command_allowlist_enabled = true
command_allowlist = ["ftbranks ", "team ", "effect ", "give "]
```

Exemplo:

```toml
[[tiers.vip.actions_on_activate]]
type = "add_ftb_rank"
rank = "vip"
```

As templates padrão ficam em `integrations.toml` e geram comandos como:

- `ftbranks add {player} {rank}`
- `ftbranks remove {player} {rank}`
- `ftbranks set {player} {rank}`

As actions `run_server_command` e `run_ftb_rank_command` podem executar mesmo com o jogador offline, desde que o nome do jogador esteja salvo.
Actions que exigem inventário ou interação direta continuam dependendo de um jogador online.

## Dados persistidos

Os dados em runtime ficam em:

```text
config/easyvip/data/
```

- `vips.json`
- `keys.json`
- `pending_variants.json`
- `package_usage.json`
- `audit_logs.json`

Arquivos gerados:

- `vips.json`
- `vips.json.bak`
- `keys.json`
- `keys.json.bak`
- `pending_variants.json`
- `pending_variants.json.bak`
- `audit_logs.json`
- `audit_logs.json.bak`

## Ações suportadas em tiers e recompensas

O executor de ações suporta os seguintes tipos:

- `give_item`
- `give_experience`
- `give_level`
- `give_effect`
- `send_message`
- `broadcast_message`
- `run_server_command`
- `run_player_command`
- `give_package`
- `set_scoreboard_tag`
- `remove_scoreboard_tag`
- `add_to_team`
- `remove_from_team`
- `give_permission_flag_internal`
- `remove_permission_flag_internal`
- `custom_event_hook`

## Segurança

- `run_server_command` e os comandos de FTB Ranks usam allowlist configurável
- A allowlist normaliza espaços, rejeita encadeamento com `;`, `&&` e `||` e compara sem diferenciar maiúsculas/minúsculas
- LuckPerms e FTB Ranks são `compileOnly`
- A integração é detectada em runtime com fallback seguro
- A persistência usa escrita atômica com `.tmp` e `.bak`
- O mod foi desenhado para funcionar sem cliente modded

## Integrações

### LuckPerms

Se habilitado em `integrations.toml`, o mod pode consultar permissões via LuckPerms.

### FTB Ranks

Se habilitado, o mod consulta permissões via FTB Ranks e pode aplicar/remover ranks por comando seguro.

### Fallback vanilla

Se nenhuma bridge estiver ativa, o mod usa permissão OP do servidor.

## Limitações atuais

- Fabric ainda não foi implementado de verdade
- `custom_event_hook` ainda não dispara evento NeoForge específico
- Economia (`EconomyBridge`) ainda é só interface

## Desenvolvimento

### Layout principal

- `common/src/main/java/br/com/pedrodalben/easyvip/`
- `neoforge/src/main/java/br/com/pedrodalben/easyvip/`

### Pontos de entrada

- `NeoForgeEasyVipMod`
- `EasyVipCommands`
- `EasyVipConfig`
- `PersistenceManager`
- `VipService`
- `KeyService`
- `PackageService`
- `ExpirationService`

## Licença

All Rights Reserved.
