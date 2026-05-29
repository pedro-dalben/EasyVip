package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.action.ActionContext;
import br.com.pedrodalben.easyvip.action.ActionExecutor;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.*;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import br.com.pedrodalben.easyvip.util.DurationParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

public final class VipService {

    private VipService() {
    }

    public static long parseDurationMillis(String durationStr) {
        return DurationParser.parseDurationMillis(durationStr);
    }

    public static boolean addVip(MinecraftServer server, UUID uuid, String tierId, String durationStr, String operator) {
        EasyVipConfig.VipTierDefinition tierDef = EasyVipConfig.tiers.list.get(tierId);
        if (tierDef == null) {
            return false;
        }

        PlayerVipRegistry registry = PersistenceManager.getPlayerVips(uuid);
        if (registry == null) {
            registry = new PlayerVipRegistry(uuid);
        }

        long duration = parseDurationMillis(durationStr);
        long now = System.currentTimeMillis();

        PlayerVipRecord record = registry.getVips().get(tierId);
        ServerPlayer player = getOnlinePlayer(server, uuid);
        boolean isOnline = player != null;
        String targetName = resolvePlayerName(server, uuid);
        if (isOnline) {
            targetName = player.getGameProfile().getName();
        }

        Map<String, String> ctx = new HashMap<>();
        ctx.put("tier_id", tierId);
        ctx.put("tier_display", tierDef.displayName);
        ctx.put("duration", DurationParser.formatDuration(duration));
        ctx.put("player", targetName);
        ctx.put("player_uuid", uuid.toString());

        if (record == null || record.isExpired()) {
            // New VIP tier activation
            long expiry = (duration == -1) ? -1 : now + duration;
            record = new PlayerVipRecord(tierId, now, expiry, false, !isOnline);
            registry.getVips().put(tierId, record);

            if (isOnline) {
                executeTierActions(server, uuid, targetName, player, tierDef.actionsOnActivate, ctx, "vip_activate");
                player.sendSystemMessage(Component.literal(
                        ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.vipActivated, ctx)
                ));
                broadcastVipActivation(server, targetName, tierDef.displayName);
            } else {
                executeTierActions(server, uuid, targetName, null, tierDef.actionsOnActivate, ctx, "vip_activate_offline");
            }
        } else {
            // Extension or stack check
            if (!tierDef.allowStacking) {
                if (tierDef.activationMode.equalsIgnoreCase("replace")) {
                    long expiry = (duration == -1) ? -1 : now + duration;
                    record.setExpiryTime(expiry);
                    record.setStartTime(now);
                    executeTierActions(server, uuid, targetName, player, tierDef.actionsOnActivate, ctx, "vip_replace");
                    if (isOnline) {
                        player.sendSystemMessage(Component.literal(
                                ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.vipActivated, ctx)
                        ));
                        broadcastVipActivation(server, targetName, tierDef.displayName);
                    } else {
                        record.setPendingActivateActions(true);
                    }
                } else {
                    return false; // Denied stacking
                }
            } else {
                // Stacking allowed
                if (record.getExpiryTime() == -1) {
                    // Already permanent
                    executeTierActions(server, uuid, targetName, player, tierDef.actionsOnActivate, ctx, "vip_stack_perm");
                    if (isOnline) {
                        player.sendSystemMessage(Component.literal(
                                ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.vipExtended, ctx)
                        ));
                    }
                } else if (duration == -1) {
                    // Upgrading to permanent
                    record.setExpiryTime(-1);
                    executeTierActions(server, uuid, targetName, player, tierDef.actionsOnActivate, ctx, "vip_upgrade_perm");
                    if (isOnline) {
                        player.sendSystemMessage(Component.literal(
                                ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.vipExtended, ctx)
                        ));
                    }
                } else {
                    // Standard duration extension
                    long currentExpiry = record.getExpiryTime();
                    long newExpiry = currentExpiry + duration;

                    // Cap stack duration if maxStackDurationSeconds is configured
                    if (tierDef.maxStackDurationSeconds > 0) {
                        long maxExpiry = record.getStartTime() + (tierDef.maxStackDurationSeconds * 1000L);
                        if (newExpiry > maxExpiry) {
                            newExpiry = maxExpiry;
                        }
                    }

                    long addedDuration = newExpiry - currentExpiry;
                    ctx.put("duration", DurationParser.formatDuration(addedDuration));
                    record.setExpiryTime(newExpiry);
                    executeTierActions(server, uuid, targetName, player, tierDef.actionsOnActivate, ctx, "vip_extend");
                    if (isOnline) {
                        player.sendSystemMessage(Component.literal(
                                ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.vipExtended, ctx)
                        ));
                    }
                }
            }
        }

        registry.setPlayerName(targetName);
        evaluateActiveVip(server, uuid, registry);
        PersistenceManager.updatePlayerVips(uuid, registry);

        PersistenceManager.log(operator, "add_vip", "VIP tier " + tierId + " added to " + targetName + " with duration " + durationStr);

        return true;
    }

    private static void broadcastVipActivation(MinecraftServer server, String playerName, String tierDisplay) {
        if (server == null || server.getPlayerList() == null) {
            return;
        }
        Map<String, String> ctx = new HashMap<>();
        ctx.put("player", playerName);
        ctx.put("tier_display", tierDisplay);
        String message = ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.vipActivatedBroadcast, ctx);
        if (message != null && !message.isEmpty()) {
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal(message),
                    false
            );
        }
    }

    public static boolean removeVip(MinecraftServer server, UUID uuid, String tierId, String operator) {
        PlayerVipRegistry registry = PersistenceManager.getPlayerVips(uuid);
        if (registry == null) {
            return false;
        }

        PlayerVipRecord record = registry.getVips().remove(tierId);
        if (record == null) {
            return false;
        }

        ServerPlayer player = getOnlinePlayer(server, uuid);
        EasyVipConfig.VipTierDefinition tierDef = EasyVipConfig.tiers.list.get(tierId);

        if (player != null && tierDef != null) {
            Map<String, String> ctx = new HashMap<>();
            ctx.put("tier_id", tierId);
            ctx.put("tier_display", tierDef.displayName);
            ctx.put("player", resolvePlayerName(server, uuid));
            ctx.put("player_uuid", uuid.toString());

            if (record.isActive()) {
                executeTierActions(server, uuid, resolvePlayerName(server, uuid), player, tierDef.actionsOnUnsetActive, ctx, "vip_remove_unset_active");
            } else {
                executeTierActions(server, uuid, resolvePlayerName(server, uuid), player, tierDef.actionsOnUnsetActive, ctx, "vip_remove_unset_active_offline");
            }
            executeTierActions(server, uuid, resolvePlayerName(server, uuid), player, tierDef.actionsOnRemove, ctx, "vip_remove");
        } else if (tierDef != null) {
            Map<String, String> ctx = new HashMap<>();
            ctx.put("tier_id", tierId);
            ctx.put("tier_display", tierDef.displayName);
            ctx.put("player", resolvePlayerName(server, uuid));
            ctx.put("player_uuid", uuid.toString());
            executeTierActions(server, uuid, resolvePlayerName(server, uuid), null, tierDef.actionsOnUnsetActive, ctx, "vip_remove_offline_unset");
            executeTierActions(server, uuid, resolvePlayerName(server, uuid), null, tierDef.actionsOnRemove, ctx, "vip_remove_offline");
        }

        registry.setPlayerName(resolvePlayerName(server, uuid));
        evaluateActiveVip(server, uuid, registry);
        PersistenceManager.updatePlayerVips(uuid, registry);

        String targetName = (player != null) ? player.getGameProfile().getName() : uuid.toString();
        PersistenceManager.log(operator, "remove_vip", "VIP tier " + tierId + " removed from " + targetName);

        return true;
    }

    public static boolean setActiveVip(MinecraftServer server, UUID uuid, String tierId, String operator) {
        if (!EasyVipConfig.common.allowPlayerActiveSelection) {
            return false;
        }

        PlayerVipRegistry registry = PersistenceManager.getPlayerVips(uuid);
        if (registry == null) {
            return false;
        }

        PlayerVipRecord targetRecord = registry.getVips().get(tierId);
        if (targetRecord == null || targetRecord.isExpired()) {
            return false;
        }

        ServerPlayer player = getOnlinePlayer(server, uuid);

        for (PlayerVipRecord record : registry.getVips().values()) {
            if (record.isActive() && !record.getTierId().equals(tierId)) {
                record.setActive(false);
                EasyVipConfig.VipTierDefinition oldDef = EasyVipConfig.tiers.list.get(record.getTierId());
                if (oldDef != null) {
                    Map<String, String> ctx = new HashMap<>();
                    ctx.put("tier_id", record.getTierId());
                    ctx.put("tier_display", oldDef.displayName);
                    ctx.put("player", resolvePlayerName(server, uuid));
                    ctx.put("player_uuid", uuid.toString());
                    executeTierActions(server, uuid, resolvePlayerName(server, uuid), player, oldDef.actionsOnUnsetActive, ctx, "vip_active_unset");
                }
            }
        }

        if (!targetRecord.isActive()) {
            targetRecord.setActive(true);
            EasyVipConfig.VipTierDefinition newDef = EasyVipConfig.tiers.list.get(tierId);
            if (newDef != null) {
                Map<String, String> ctx = new HashMap<>();
                ctx.put("tier_id", tierId);
                ctx.put("tier_display", newDef.displayName);
                ctx.put("player", resolvePlayerName(server, uuid));
                ctx.put("player_uuid", uuid.toString());
                executeTierActions(server, uuid, resolvePlayerName(server, uuid), player, newDef.actionsOnSetActive, ctx, "vip_active_set");
            }
        }

        registry.setPlayerName(resolvePlayerName(server, uuid));
        PersistenceManager.updatePlayerVips(uuid, registry);
        PersistenceManager.log(operator, "change_active_vip", "Set active VIP tier " + tierId + " for " + uuid);
        return true;
    }

    public static void evaluateActiveVip(MinecraftServer server, UUID uuid, PlayerVipRegistry registry) {
        if (registry == null) {
            return;
        }

        ServerPlayer player = getOnlinePlayer(server, uuid);

        PlayerVipRecord highestVip = null;
        int highestPriority = -1;

        // Find the active non-expired VIPs
        List<PlayerVipRecord> validVips = new ArrayList<>();
        PlayerVipRecord currentActive = null;

        for (PlayerVipRecord record : registry.getVips().values()) {
            if (!record.isExpired()) {
                validVips.add(record);
                EasyVipConfig.VipTierDefinition def = EasyVipConfig.tiers.list.get(record.getTierId());
                int priority = (def != null) ? def.priority : 0;
                if (priority > highestPriority) {
                    highestPriority = priority;
                    highestVip = record;
                }
                if (record.isActive()) {
                    currentActive = record;
                }
            } else if (record.isActive()) {
                // If it's expired and active, mark it inactive
                record.setActive(false);
            }
        }

        PlayerVipRecord targetActive = null;
        if (!validVips.isEmpty()) {
            boolean forceHighest = EasyVipConfig.common.forceHighestPriorityAsActive;
            if (forceHighest || currentActive == null || currentActive.isExpired()) {
                targetActive = (highestVip != null) ? highestVip : validVips.get(0);
            } else {
                targetActive = currentActive;
            }
        }

        // Apply active/inactive flag updates
        if (currentActive != targetActive) {
            if (currentActive != null) {
                currentActive.setActive(false);
            }
            if (targetActive != null) {
                targetActive.setActive(true);
            }
        }

        // Sync transition actions online/offline
        String desiredActiveVip = (targetActive != null) ? targetActive.getTierId() : null;
        String lastObserved = registry.getLastObservedActiveVip();

        if (!Objects.equals(desiredActiveVip, lastObserved)) {
            // Run unset actions for lastObserved if it's not null
            if (lastObserved != null) {
                EasyVipConfig.VipTierDefinition oldDef = EasyVipConfig.tiers.list.get(lastObserved);
                if (oldDef != null) {
                    Map<String, String> ctx = new HashMap<>();
                    ctx.put("tier_id", lastObserved);
                    ctx.put("tier_display", oldDef.displayName);
                    ctx.put("player", resolvePlayerName(server, uuid));
                    ctx.put("player_uuid", uuid.toString());
                    executeTierActions(server, uuid, resolvePlayerName(server, uuid), player, oldDef.actionsOnUnsetActive, ctx, "vip_deactivate_old");
                }
            }

            // Run set actions for desiredActiveVip if it's not null
            if (desiredActiveVip != null) {
                EasyVipConfig.VipTierDefinition newDef = EasyVipConfig.tiers.list.get(desiredActiveVip);
                if (newDef != null) {
                    Map<String, String> ctx = new HashMap<>();
                    ctx.put("tier_id", desiredActiveVip);
                    ctx.put("tier_display", newDef.displayName);
                    ctx.put("player", resolvePlayerName(server, uuid));
                    ctx.put("player_uuid", uuid.toString());
                    executeTierActions(server, uuid, resolvePlayerName(server, uuid), player, newDef.actionsOnSetActive, ctx, "vip_activate_new");
                }
            }

            // Update observed state only if online
            if (player != null) {
                registry.setLastObservedActiveVip(desiredActiveVip);
            }
        }
    }

    public static int expireAllDueVips(MinecraftServer server) {
        int expiredCount = 0;
        for (Map.Entry<UUID, PlayerVipRegistry> entry : PersistenceManager.getAllPlayerVips().entrySet()) {
            expiredCount += expireDueVipsForPlayer(server, entry.getKey());
        }
        return expiredCount;
    }

    public static int expireDueVipsForPlayer(MinecraftServer server, UUID uuid) {
        return expireDueVipsForPlayer(server, uuid, resolvePlayerName(server, uuid), getOnlinePlayer(server, uuid));
    }

    static int expireDueVipsForTest(UUID uuid, String playerName) {
        return expireDueVipsForPlayer(null, uuid, playerName, null);
    }

    private static int expireDueVipsForPlayer(MinecraftServer server, UUID uuid, String playerName, ServerPlayer player) {
        PlayerVipRegistry registry = PersistenceManager.getPlayerVips(uuid);
        if (registry == null || registry.getVips().isEmpty()) {
            return 0;
        }

        registry.setPlayerName(playerName);

        boolean changed = false;
        int expiredCount = 0;
        Iterator<Map.Entry<String, PlayerVipRecord>> it = registry.getVips().entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PlayerVipRecord> entry = it.next();
            PlayerVipRecord record = entry.getValue();
            if (record.isExpired()) {
                it.remove();
                changed = true;
                expiredCount++;

                EasyVipConfig.VipTierDefinition tierDef = EasyVipConfig.tiers.list.get(record.getTierId());
                Map<String, String> ctx = new HashMap<>();
                ctx.put("tier_id", record.getTierId());
                ctx.put("tier_display", tierDef != null ? tierDef.displayName : record.getTierId());
                ctx.put("player", playerName);
                ctx.put("player_uuid", uuid.toString());

                if (tierDef != null) {
                    if (record.isActive()) {
                        executeTierActions(server, uuid, playerName, player, tierDef.actionsOnUnsetActive, ctx, "vip_expire_unset_active");
                    }
                    executeTierActions(server, uuid, playerName, player, tierDef.actionsOnExpire, ctx, "vip_expire");
                    if (player != null) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.vipExpired, ctx)
                        ));
                    }
                }

                PersistenceManager.log("System", "vip_expired", "VIP tier " + record.getTierId() + " expired for " + playerName);
            }
        }

        if (changed) {
            evaluateActiveVip(server, uuid, registry);
            PersistenceManager.updatePlayerVips(uuid, registry);
        }

        return expiredCount;
    }

    public static void checkExpirations(MinecraftServer server) {
        expireAllDueVips(server);
    }

    public static void handlePlayerJoin(ServerPlayer player) {
        UUID uuid = player.getUUID();
        PlayerVipRegistry registry = PersistenceManager.getPlayerVips(uuid);
        if (registry == null) {
            return;
        }

        MinecraftServer server = player.getServer();
        registry.setPlayerName(player.getGameProfile().getName());
        expireDueVipsForPlayer(server, uuid);

        for (PlayerVipRecord record : registry.getVips().values()) {
            if (record.isPendingActivateActions()) {
                EasyVipConfig.VipTierDefinition tierDef = EasyVipConfig.tiers.list.get(record.getTierId());
                if (tierDef != null) {
                    long remaining = record.getExpiryTime() == -1 ? -1 : (record.getExpiryTime() - record.getStartTime());
                    String formattedDuration = DurationParser.formatDuration(remaining);

                    Map<String, String> ctx = new HashMap<>();
                    ctx.put("tier_id", record.getTierId());
                    ctx.put("tier_display", tierDef.displayName);
                    ctx.put("duration", formattedDuration);
                    ctx.put("player", player.getGameProfile().getName());
                    ctx.put("player_uuid", uuid.toString());
                    executeTierActions(server, uuid, player.getGameProfile().getName(), player, tierDef.actionsOnActivate, ctx, "vip_pending_activate");

                    player.sendSystemMessage(Component.literal(
                            ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.vipActivated, ctx)
                    ));
                    broadcastVipActivation(server, player.getGameProfile().getName(), tierDef.displayName);
                }
                record.setPendingActivateActions(false);
            }
        }

        evaluateActiveVip(server, uuid, registry);
        PersistenceManager.updatePlayerVips(uuid, registry);
    }

    private static String resolvePlayerName(MinecraftServer server, UUID uuid) {
        if (server != null) {
            try {
                Optional<com.mojang.authlib.GameProfile> profile = server.getProfileCache().get(uuid);
                if (profile.isPresent() && profile.get().getName() != null) {
                    return profile.get().getName();
                }
            } catch (Exception ignored) {
            }
        }
        return uuid.toString();
    }

    private static ServerPlayer getOnlinePlayer(MinecraftServer server, UUID uuid) {
        if (server == null || server.getPlayerList() == null) {
            return null;
        }
        try {
            return server.getPlayerList().getPlayer(uuid);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean executeTierActions(MinecraftServer server, UUID uuid, String playerName, ServerPlayer onlinePlayer,
                                              List<Map<String, Object>> actions, Map<String, String> ctx, String source) {
        ActionContext actionContext = (onlinePlayer != null)
                ? ActionContext.online(onlinePlayer, source)
                : ActionContext.offline(server, uuid, playerName, source);
        return ActionExecutor.execute(actionContext, actions, ctx);
    }
}
