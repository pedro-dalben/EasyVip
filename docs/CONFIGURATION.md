# EasyVip Configuration

## Implemented

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
- `variant_selection_timeout_seconds` controls how long a package variant choice stays pending.
- `notify_pending_variant_on_login` only toggles the login reminder; expiration cleanup still runs.
- `item_key_marker` is required on the physical item payload so a generic item with NBT is not accepted.

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

## Reserved

- `sql_enabled`
- `sql_url`
- `sql_username`
- `sql_password`

These fields are kept for future work but do not enable SQL storage in the current release.
