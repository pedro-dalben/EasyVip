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

## WebStore

- [Documentação técnica do fulfillment](docs/integrations/webstore-fulfillment.md)
- [Runbook operacional do fulfillment](docs/operations/webstore-fulfillment-runbook.md)
- [Padrão multi-servidor](docs/integrations/multi-server-webstore.md)
- [Visão geral do sync WebStore](docs/WEBSTORE.md)

O fulfillment atual é somente saída: EasyVip faz polling HTTPS para o Rails e não abre listener HTTP nem usa RCON.

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

#### `/easyvip activate <key>`

Alias direto de `/easyvip use`.

#### `/activate <key>`

Alias direto de `/easyvip use`.

#### `/vip <key>`

Alias curto de `/easyvip use`.

### Admin

#### `/easyvip admin addvip <player> <tier> <duration>`

Adiciona ou estende um VIP para o jogador.

Exemplo:

```text
/easyvip admin addvip Pedro vip 30d
```

#### `/easyvip createvip <id> <display_name> [color]`

Cria uma nova definição de VIP sem alterar os tiers já existentes.
O novo VIP herda os defaults de `tiers.toml` e só grava no arquivo o que realmente foi alterado.

#### `/easyvip admin removevip <player> <tier>`

Remove um tier VIP do jogador.

#### `/easyvip savevipactivation <tier>`

Salva o inventário atual do jogador online em `config/easyvip/activation_items/<tier>.toml`.
Sempre que possível, os itens são gravados no formato simples `item` + `amount`.
Se o item tiver dados complexos demais, o mod ainda faz fallback para `stack_snbt`.
Cada item é gravado com `chance = 100` por padrão, então você pode ajustar manualmente os itens raros para `50`, `25` etc. sem mexer em mensagens ou comandos.
Esse comando precisa ser executado por um jogador online, porque ele lê o inventário atual.

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

#### `/easyvip reload`

Recarrega os arquivos TOML sem reiniciar o servidor.

Alias: `/easyvip config reload`.

O scheduler de expiração também é reiniciado, então `auto_expire_interval_seconds` passa a valer imediatamente.

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

- `command_cooldown_ticks` aplica cooldown por jogador em `/easyvip use`, `/easyvip activate`, `/usekey`, `/activate`, `/vip` e `/easyvip confirm`
- `allowed_dimensions` permite apenas as dimensões listadas
- `deny_dimensions` bloqueia as dimensões listadas e tem prioridade sobre `allowed_dimensions`
- `variant_selection_timeout_seconds` controla por quanto tempo a escolha de variante fica pendente
- `notify_pending_variant_on_login` só controla o aviso no login; a limpeza de expiradas continua ativa
- `item_key_marker` precisa bater com o marcador gravado no item físico
- `command_allowlist` já vem com `broadcast` por padrão para suportar anúncios de ativação nos VIPs

### `messages.toml`

Todas as mensagens do mod, com suporte a `&` para cores.
Os placeholders aceitam tanto `{placeholder}` quanto `%placeholder%`.

Idioma suportado:

- `en-us`
- `pt-br`

O idioma padrão é `en-us`.
O broadcast global de item raro usa `vip_lucky_item_broadcast`.

### Layout recomendado

Para deixar a configuração visualmente simples, use esta divisão:

- `tiers.toml` para definir os VIPs
- `activation_items/<vip>.toml` para definir o kit de ativação de cada VIP
- `pools.toml` para definir sorteios reutilizáveis

Exemplo enxuto dos 3 arquivos:

`config/easyvip/tiers.toml`

```toml
[defaults]
duration = "30d"
stacking = true
activation_mode = "extend"

[defaults.messages]
activated = "&a%player% ativou o VIP %vip_name% por %duration%."
expired = "&cSeu VIP %vip_name% expirou."
rare_item_broadcast = "&6%player% ganhou um item lendário ao ativar o VIP %vip_name%!"

[defaults.commands]
activate = ["broadcast %player% ativou o VIP %vip_name%"]
expire = []

[vips.pokeball]
display_name = "Pokeball"
color = "red"
priority = 10

[vips.ultraball]
display_name = "Ultra Ball"
color = "yellow"
priority = 20

[vips.masterball]
display_name = "Master Ball"
color = "light_purple"
priority = 30
```

`config/easyvip/activation_items/masterball.toml`

```toml
[[items]]
item = "minecraft:diamond"
amount = 16

[[items]]
item = "minecraft:nether_star"
amount = 1

[[items]]
item = "minecraft:diamond_pickaxe"
amount = 1
enchants = { efficiency = 10, fortune = 5, unbreaking = 10 }

[[items]]
item = "minecraft:elytra"
chance = 25
```

`config/easyvip/pools.toml`

```toml
[pools.shiny_pokemon]
values = ["Pikachu", "Bulbasaur", "Charmander", "Squirtle"]

[pools.vip_items]
values = ["diamond", "emerald", "nether_star"]

[pools.rare_rewards]
[[pools.rare_rewards.weighted]]
value = "Lucario"
weight = 50

[[pools.rare_rewards.weighted]]
value = "Garchomp"
weight = 25
```

Uso simples em comandos:

```toml
[vips.masterball.commands]
activate = [
  "$pokemon = %random(shiny_pokemon)%",
  "givepokemon %player% $pokemon shiny",
  "broadcast %player% recebeu um $pokemon shiny!"
]
```

### `tiers.toml`

Define os VIPs em um formato simples e legível.

Exemplo:

```toml
[defaults]
duration = "30d"
stacking = true
activation_mode = "extend"

[defaults.messages]
activated = "&a%player% ativou o VIP %vip_name% por %duration%."
expired = "&cSeu VIP %vip_name% expirou."
rare_item_broadcast = "&6%player% ganhou um item lendário ao ativar o VIP %vip_name%!"

[defaults.commands]
activate = ["broadcast %player% ativou o VIP %vip_name%"]
expire = []

[vips.pokeball]
display_name = "Pokeball"
color = "red"
```

Campos mais usados em `tiers.toml`:

- `defaults.duration`
- `defaults.stacking`
- `defaults.activation_mode`
- `defaults.messages.activated`
- `defaults.messages.expired`
- `defaults.messages.rare_item_broadcast`
- `defaults.commands.activate`
- `defaults.commands.expire`
- `vips.<id>.display_name`
- `vips.<id>.color`
- `vips.<id>.priority`

Os blocos antigos `actions_on_*` continuam sendo lidos por compatibilidade, mas o formato recomendado é o novo.

### `activation_items/<vip>.toml`

Cada VIP tem o seu próprio arquivo de kit de ativação.

Exemplo:

```toml
[[items]]
item = "cobblemon:poke_ball"
amount = 64

[[items]]
item = "cobblemon:exp_candy_xl"
amount = 64

[[items]]
stack_snbt = "{id:\"minecraft:enchanted_book\",Count:1b,...}"

[[items]]
item = "minecraft:diamond_pickaxe"
amount = 1

[items.enchants]
efficiency = 10
fortune = 5
unbreaking = 10
```

Campos mais usados em `activation_items/<vip>.toml`:

- `items`
- `items.item`
- `items.amount`
- `items.enchants`
- `items.chance`
- `items.stack_snbt`

`chance` é opcional e assume `100` por padrão.

### `pools.toml`

Define listas reutilizáveis para sorteios em comandos e mensagens.

Exemplo:

```toml
[pools.shiny_pokemon]
values = ["Pikachu", "Bulbasaur", "Charmander", "Squirtle"]

[pools.vip_items]
values = ["diamond", "emerald", "nether_star"]

[pools.rare_pokemon]
[[pools.rare_pokemon.weighted]]
value = "Lucario"
weight = 50

[[pools.rare_pokemon.weighted]]
value = "Garchomp"
weight = 50
```

Uso em comandos:

```toml
[vips.vip.commands]
activate = [
  "$pokemon = %random(shiny_pokemon)%",
  "givepokemon %player% $pokemon shiny",
  "broadcast %player% recebeu um $pokemon shiny!"
]
```

Regras:

- `%random(pool_name)%` sorteia um valor da pool
- `values = [...]` define uma pool simples com chance igual
- `weighted` define uma pool ponderada
- `weighted` usa `value` e `weight`
- variáveis temporárias começam com `$`, por exemplo `$pokemon = ...`
- uma variável pode ser reutilizada em linhas seguintes do mesmo comando

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
Na configuração nova, isso normalmente é feito em `commands.activate` e `commands.expire`, sem precisar usar `actions_on_*`.

Exemplo de allowlist:

```toml
[security]
command_allowlist_enabled = true
command_allowlist = ["ftbranks ", "team ", "effect ", "give "]
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
- `give_item_stack`
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
Na configuração nova, isso normalmente é feito em `commands.activate` e `commands.expire`, sem precisar usar `actions_on_*`.

Exemplo:

```toml
[vips.vip]
display_name = "VIP"

[vips.vip.commands]
activate = ["ftbranks add %player% vip"]
expire = ["ftbranks remove %player% vip"]
```

Os blocos antigos `actions_on_*` ainda funcionam por compatibilidade, mas o formato acima é o recomendado.

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
