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

## Admin

- `/easyvip admin addvip <player> <tier> <duration>`
- `/easyvip admin removevip <player> <tier>`
- `/easyvip admin savevipactivation <tier>`
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
