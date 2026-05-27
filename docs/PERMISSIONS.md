# EasyVip Permissions

- `easyvip.use`
  - player-facing commands
- `easyvip.admin`
  - administrative commands, configuration, key management, packages and audit

## Notes

- Console access is allowed by default.
- LuckPerms and FTB Ranks are optional bridges.
- Bridge priority is FTB Ranks, then LuckPerms, then vanilla OP level.
- OP fallback is always checked after the permission bridges, so a server operator does not need a plugin-specific node just to use EasyVip commands.
- FTB Ranks is used through safe command templates when the mod is present; the mod does not require the API at runtime.
- Fallback permission behavior remains server-side and does not require Fabric or Bukkit APIs.
