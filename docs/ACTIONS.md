# EasyVip Actions

## Common actions

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
- `run_ftb_rank_command`
- `add_ftb_rank`
- `remove_ftb_rank`
- `set_ftb_rank`

## Security

- `run_server_command` and FTB rank command actions are blocked unless the final command matches the allowlist.
- The default allowlist is intentionally narrow.
- FTB Ranks actions no-op safely if FTB Ranks is not present.

