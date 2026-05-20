# EasyVip

easyVip é um mod server-side para Minecraft 1.21.1 que implementa um sistema completo de VIPs, chaves e recompensas para servidores.

O projeto é escrito em Java e está estruturado para NeoForge primeiro, com um módulo Fabric placeholder já criado para evolução futura.

## Visão geral

- VIPs temporários ou permanentes com tiers configuráveis
- Ativação por chaves geradas pelo admin
- Confirmação opcional antes de consumir chaves
- Pacotes de recompensa com variantes
- Integração opcional com LuckPerms e FTB Ranks
- Persistência em JSON com escrita atômica e backup automático
- Configuração em TOML com parser próprio
- Comando `/easyvip` para jogadores e administradores
- Evento físico para usar chaves em item com `CUSTOM_DATA`

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
7. Um scheduler processa expiração de VIPs periodicamente.

### Chave física

O mod também lê `DataComponents.CUSTOM_DATA` em qualquer `ItemStack` e procura a tag `easyvip_key`.

Se o item contiver essa chave, o uso no clique direito ativa a mesma lógica de `/easyvip use`.

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

#### `/easyvip info [player]`

Mostra VIPs ativos e tempo restante.

- Sem argumento: mostra os VIPs do jogador atual
- Com argumento: mostra os VIPs de outro jogador, se o executor tiver permissão admin

#### `/easyvip select <tier>`

Seleciona o VIP ativo do jogador, quando a troca manual estiver permitida.

#### `/easyvip variant choose <package> <variant>`

Escolhe uma variante pendente de pacote.

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
- `log_to_file`
- `debug`
- `command_allowlist_enabled`
- `command_allowlist`

### `messages.toml`

Todas as mensagens do mod, com suporte a `&` para cores.

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

### `reward_keys.toml`

Define recompensas não-VIP geradas por chave.

### `integrations.toml`

Integrações opcionais:

- `ftb_ranks_enabled`
- `luckperms_enabled`
- `primary_permission_bridge`
- `sql_enabled`
- `sql_url`
- `sql_username`
- `sql_password`

## Dados persistidos

Os dados em runtime ficam em:

```text
config/easyvip/data/
```

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

- `run_server_command` usa allowlist configurável
- LuckPerms e FTB Ranks são `compileOnly`
- A integração é detectada em runtime com fallback seguro
- A persistência usa escrita atômica com `.tmp` e `.bak`
- O mod foi desenhado para funcionar sem cliente modded

## Integrações

### LuckPerms

Se habilitado em `integrations.toml`, o mod pode consultar permissões via LuckPerms.

### FTB Ranks

Se habilitado, o mod consulta permissões via FTB Ranks.

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
