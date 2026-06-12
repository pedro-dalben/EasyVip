# EasyVip Configuration

## Implemented

## Recommended layout

Use three separate files to keep the setup easy to read:

- `tiers.toml` for VIP definitions and shared defaults
- `activation_items/<vip>.toml` for activation kits
- `pools.toml` for random pools used by commands

Minimal example:

### `tiers.toml`

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
```

### `activation_items/pokeball.toml`

```toml
[[items]]
item = "minecraft:diamond"
amount = 16

[[items]]
item = "minecraft:diamond_pickaxe"
amount = 1
enchants = { efficiency = 10, fortune = 5, unbreaking = 10 }
```

### `pools.toml`

```toml
[pools.shiny_pokemon]
values = ["Pikachu", "Bulbasaur", "Charmander", "Squirtle"]

[pools.vip_items]
values = ["diamond", "emerald", "nether_star"]
```

### `common.toml`

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
- `command_allowlist_enabled`
- `command_allowlist`

### Behavior notes

- `command_cooldown_ticks` applies to `/easyvip use`, `/easyvip activate`, `/usekey`, `/activate`, `/vip` and `/easyvip confirm` per player.
- `allowed_dimensions` is a positive allowlist for reward/VIP use.
- `deny_dimensions` wins over `allowed_dimensions` if both match.
- `auto_expire_interval_seconds` controls the expiration scheduler interval and is reapplied on server start or `/easyvip reload`.
- `variant_selection_timeout_seconds` controls how long a package variant choice stays pending.
- `notify_pending_variant_on_login` only toggles the login reminder; expiration cleanup still runs.
- `item_key_marker` is required on the physical item payload so a generic item with NBT is not accepted.
- The default `command_allowlist` already includes `broadcast`, so VIP command lists can announce activations without extra setup.

### Language

- Supported values: `en-us`, `pt-br`
- Default: `en-us`
- The selected language is used as the base for generated `messages.toml`, `tiers.toml`, `packages.toml` and `reward_keys.toml`
- Existing generated TOML files are preserved; delete them if you want to regenerate the defaults in another language

### `packages.toml`

- `repeatable`
- `cooldown_seconds`

These are implemented and enforced during package delivery.

### `reward_keys.toml`

- `consume_on_use`
- `cooldown_seconds`
- `allowed_dimensions`

These are implemented and enforced during reward key redemption.

### `integrations.toml`

- `ftb_ranks_enabled`
- `luckperms_enabled`
- `primary_permission_bridge`
- `ftb_ranks_add_command`
- `ftb_ranks_remove_command`
- `ftb_ranks_set_command`

The FTB Ranks actions are command-template driven and still pass through the command allowlist.

### `messages.toml`

- `vip_lucky_item_broadcast`

This message is used when a VIP activation item with chance below `100` is awarded.

### `pools.toml`

- `pools.<id>.values`
- `pools.<id>.weighted`

`values` defines a simple random pool with equal odds.
`weighted` defines a weighted pool using `value` and `weight`.
The placeholder syntax is `%random(pool_name)%`.
Temporary variables can be assigned inside command lists with `$name = ...` and reused later in the same list.

### `tiers.toml`

The simplified VIP schema uses:

- `[defaults]` for shared duration, stacking and activation mode
- `[defaults.messages]` for the activated, expired and rare-item broadcast texts
- `[defaults.commands]` for shared activate/expire command lists
- `commands.activate` and `commands.expire` can use `%random(pool)%` and temporary variables like `$pokemon = ...`
- `[vips.<id>]` for the per-VIP display name and color
- `vips.<id>.priority`

### `activation_items/<vip>.toml`

Each VIP has its own activation kit file.

The file uses `[[items]]` entries with:

- `item`
- `amount`
- `enchants`
- `chance`
- `stack_snbt` for legacy or complex cases

`chance` is optional and defaults to `100`.
The legacy `actions_on_*` sections are still parsed for compatibility, but the simplified schema above is the recommended format.

## Reserved

- `sql_enabled`
- `sql_url`
- `sql_username`
- `sql_password`

These fields are kept for future work but do not enable SQL storage in the current release.
