# EasyVip Commands

## Player

- `/easyvip use <key>`
- `/easyvip activate <key>`
- `/easyvip confirm`
- `/easyvip info [player]`
- `/easyvip select <tier>`
- `/easyvip variant choose <package> <variant>`
- `/easyvip variant pending [player]`
- `/easyvip variant clear <player> [package_id]`
- `/easyvip time [player]`
- `/viptime [player]`
- `/usekey <key>`
- `/activate <key>`
- `/vip <key>`

## Notes

- `/easyvip use`, `/easyvip activate`, `/usekey`, `/activate` e `/vip` respeitam cooldown por jogador.
- `/easyvip confirm` também respeita cooldown por jogador.
- `/easyvip variant pending` mostra pendências válidas; pendências expiradas são limpas pelo login e pelo scheduler.
- `/easyvip variant clear` remove pendências manualmente.

## Config

- `/easyvip reload`
- `/easyvip config reload` (alias)
- `/easyvip config validate`

## Admin

- `/easyvip createvip <id> <display_name> [color]`
- `/easyvip admin addvip <player> <tier> <duration>`
- `/easyvip admin removevip <player> <tier>`
- `/easyvip savevipactivation <tier>`
- `/easyvip admin generate vip <tier> <duration> [max_uses] [bound_player]`
- `/easyvip admin generate reward <reward_key_id> [max_uses] [bound_player]`
- `/easyvip admin givepackage <player> <package_id>`
- `/easyvip admin giveitemkey <player> <code>`
- `/easyvip admin audit [page]`
- `/easyvip key list`
- `/easyvip key info <code>`
- `/easyvip key delete <code>`
- `/easyvip package list`
- `/easyvip package info <id>`
- `/easyvip active set <player> <tier>`

## Notes

- `/easyvip createvip` cria um VIP novo sem alterar os tiers já existentes.
- `/easyvip savevipactivation` grava o inventário atual em `config/easyvip/activation_items/<tier>.toml`; quando possível, ele usa `item` + `amount`, e a chance padrão é `100`, então você pode editar só os itens raros depois.
