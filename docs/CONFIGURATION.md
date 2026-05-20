# EasyVip Configuration

## Implemented

### `common.toml`

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
- `command_allowlist_enabled`
- `command_allowlist`

### `packages.toml`

- `repeatable`
- `cooldown_seconds`

### `reward_keys.toml`

- `consume_on_use`
- `cooldown_seconds`
- `allowed_dimensions`

### `integrations.toml`

- `ftb_ranks_enabled`
- `luckperms_enabled`
- `primary_permission_bridge`
- `ftb_ranks_add_command`
- `ftb_ranks_remove_command`
- `ftb_ranks_set_command`

## Reserved

- `sql_enabled`
- `sql_url`
- `sql_username`
- `sql_password`

These fields are kept for future work but do not enable SQL storage in the current release.

