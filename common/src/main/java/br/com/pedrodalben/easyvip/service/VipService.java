package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.action.ActionExecutor;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.*;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

public final class VipService {

    private VipService() {
    }

    public static long parseDurationMillis(String durationStr) {
        if (durationStr == null || durationStr.equalsIgnoreCase("permanent")) {
            return -1;
        }
        durationStr = durationStr.toLowerCase().trim();
        long millis = 0;
        StringBuilder num = new StringBuilder();
        for (int i = 0; i < durationStr.length(); i++) {
            char c = durationStr.charAt(i);
            if (Character.isDigit(c)) {
                num.append(c);
            } else {
                if (num.length() == 0) continue;
                long val = Long.parseLong(num.toString());
                num.setLength(0);
                switch (c) {
                    case 's': millis += val * 1000L; break;
                    case 'm': millis += val * 60L * 1000L; break;
                    case 'h': millis += val * 60L * 60L * 1000L; break;
                    case 'd': millis += val * 24L * 60L * 60L * 1000L; break;
                    case 'w': millis += val * 7L * 24L * 60L * 60L * 1000L; break;
                    case 'M': millis += val * 30L * 24L * 60L * 60L * 1000L; break;
                    case 'y': millis += val * 365L * 24L * 60L * 60L * 1000L; break;
                }
            }
        }
        if (num.length() > 0) {
            millis += Long.parseLong(num.toString()) * 24L * 60L * 60L * 1000L;
        }
        return millis;
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
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        boolean isOnline = player != null;

        Map<String, String> ctx = new HashMap<>();
        ctx.put("tier_id", tierId);
        ctx.put("tier_display", tierDef.displayName);
        ctx.put("duration", durationStr);

        if (record == null || record.isExpired()) {
            // New VIP tier activation
            long expiry = (duration == -1) ? -1 : now + duration;
            record = new PlayerVipRecord(tierId, now, expiry, false, !isOnline);
            registry.getVips().put(tierId, record);

            if (isOnline) {
                ActionExecutor.execute(player, tierDef.actionsOnActivate, ctx);
            }
        } else {
            // Extension or stack check
            if (!tierDef.allowStacking) {
                if (tierDef.activationMode.equalsIgnoreCase("replace")) {
                    long expiry = (duration == -1) ? -1 : now + duration;
                    record.setExpiryTime(expiry);
                    record.setStartTime(now);
                    if (isOnline) {
                        ActionExecutor.execute(player, tierDef.actionsOnActivate, ctx);
                    }
                } else {
                    return false; // Denied stacking
                }
            } else {
                // Stacking allowed
                if (record.getExpiryTime() == -1) {
                    // Already permanent
                    if (isOnline) {
                        ActionExecutor.execute(player, tierDef.actionsOnActivate, ctx);
                    }
                } else if (duration == -1) {
                    // Upgrading to permanent
                    record.setExpiryTime(-1);
                    if (isOnline) {
                        ActionExecutor.execute(player, tierDef.actionsOnActivate, ctx);
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

                    record.setExpiryTime(newExpiry);
                    if (isOnline) {
                        ActionExecutor.execute(player, tierDef.actionsOnActivate, ctx);
                    }
                }
            }
        }

        evaluateActiveVip(server, uuid, registry);
        PersistenceManager.updatePlayerVips(uuid, registry);

        String targetName = isOnline ? player.getGameProfile().getName() : uuid.toString();
        PersistenceManager.log(operator, "add_vip", "VIP tier " + tierId + " added to " + targetName + " with duration " + durationStr);

        return true;
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

        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        EasyVipConfig.VipTierDefinition tierDef = EasyVipConfig.tiers.list.get(tierId);

        if (player != null && tierDef != null) {
            Map<String, String> ctx = new HashMap<>();
            ctx.put("tier_id", tierId);
            ctx.put("tier_display", tierDef.displayName);

            // Execute unset if it was active
            if (record.isActive()) {
                ActionExecutor.execute(player, tierDef.actionsOnUnsetActive, ctx);
            }
            ActionExecutor.execute(player, tierDef.actionsOnRemove, ctx);
        }

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

        ServerPlayer player = server.getPlayerList().getPlayer(uuid);

        for (PlayerVipRecord record : registry.getVips().values()) {
            if (record.isActive() && !record.getTierId().equals(tierId)) {
                record.setActive(false);
                if (player != null) {
                    EasyVipConfig.VipTierDefinition oldDef = EasyVipConfig.tiers.list.get(record.getTierId());
                    if (oldDef != null) {
                        Map<String, String> ctx = new HashMap<>();
                        ctx.put("tier_id", record.getTierId());
                        ctx.put("tier_display", oldDef.displayName);
                        ActionExecutor.execute(player, oldDef.actionsOnUnsetActive, ctx);
                    }
                }
            }
        }

        if (!targetRecord.isActive()) {
            targetRecord.setActive(true);
            if (player != null) {
                EasyVipConfig.VipTierDefinition newDef = EasyVipConfig.tiers.list.get(tierId);
                if (newDef != null) {
                    Map<String, String> ctx = new HashMap<>();
                    ctx.put("tier_id", tierId);
                    ctx.put("tier_display", newDef.displayName);
                    ActionExecutor.execute(player, newDef.actionsOnSetActive, ctx);
                }
            }
        }

        PersistenceManager.updatePlayerVips(uuid, registry);
        PersistenceManager.log(operator, "change_active_vip", "Set active VIP tier " + tierId + " for " + uuid);
        return true;
    }

    public static void evaluateActiveVip(MinecraftServer server, UUID uuid, PlayerVipRegistry registry) {
        if (registry == null || registry.getVips().isEmpty()) {
            return;
        }

        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
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

        if (validVips.isEmpty()) {
            if (currentActive != null && player != null) {
                EasyVipConfig.VipTierDefinition oldDef = EasyVipConfig.tiers.list.get(currentActive.getTierId());
                if (oldDef != null) {
                    Map<String, String> ctx = new HashMap<>();
                    ctx.put("tier_id", currentActive.getTierId());
                    ctx.put("tier_display", oldDef.displayName);
                    ActionExecutor.execute(player, oldDef.actionsOnUnsetActive, ctx);
                }
            }
            return;
        }

        boolean forceHighest = EasyVipConfig.common.forceHighestPriorityAsActive;

        if (forceHighest || currentActive == null || currentActive.isExpired()) {
            PlayerVipRecord targetActive = (highestVip != null) ? highestVip : validVips.get(0);

            if (currentActive != targetActive) {
                // Deactivate old active
                if (currentActive != null) {
                    currentActive.setActive(false);
                    if (player != null) {
                        EasyVipConfig.VipTierDefinition oldDef = EasyVipConfig.tiers.list.get(currentActive.getTierId());
                        if (oldDef != null) {
                            Map<String, String> ctx = new HashMap<>();
                            ctx.put("tier_id", currentActive.getTierId());
                            ctx.put("tier_display", oldDef.displayName);
                            ActionExecutor.execute(player, oldDef.actionsOnUnsetActive, ctx);
                        }
                    }
                }

                // Activate new active
                targetActive.setActive(true);
                if (player != null) {
                    EasyVipConfig.VipTierDefinition newDef = EasyVipConfig.tiers.list.get(targetActive.getTierId());
                    if (newDef != null) {
                        Map<String, String> ctx = new HashMap<>();
                        ctx.put("tier_id", targetActive.getTierId());
                        ctx.put("tier_display", newDef.displayName);
                        ActionExecutor.execute(player, newDef.actionsOnSetActive, ctx);
                    }
                }
            }
        }
    }

    public static void handlePlayerJoin(ServerPlayer player) {
        UUID uuid = player.getUUID();
        PlayerVipRegistry registry = PersistenceManager.getPlayerVips(uuid);
        if (registry == null) {
            return;
        }

        MinecraftServer server = player.getServer();
        boolean changed = false;

        for (PlayerVipRecord record : registry.getVips().values()) {
            if (record.isPendingActivateActions()) {
                EasyVipConfig.VipTierDefinition tierDef = EasyVipConfig.tiers.list.get(record.getTierId());
                if (tierDef != null) {
                    Map<String, String> ctx = new HashMap<>();
                    ctx.put("tier_id", record.getTierId());
                    ctx.put("tier_display", tierDef.displayName);
                    ctx.put("duration", "activation");

                    ActionExecutor.execute(player, tierDef.actionsOnActivate, ctx);
                }
                record.setPendingActivateActions(false);
                changed = true;
            }
        }

        evaluateActiveVip(server, uuid, registry);
        PersistenceManager.updatePlayerVips(uuid, registry);
    }

    public static void checkExpirations(MinecraftServer server) {
        long now = System.currentTimeMillis();
        List<UUID> toSave = new ArrayList<>();

        for (UUID uuid : new ArrayList<>(server.getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID).toList())) {
            PlayerVipRegistry registry = PersistenceManager.getPlayerVips(uuid);
            if (registry != null) {
                boolean changed = false;
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);

                Iterator<Map.Entry<String, PlayerVipRecord>> it = registry.getVips().entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, PlayerVipRecord> entry = it.next();
                    PlayerVipRecord record = entry.getValue();

                    if (record.isExpired()) {
                        it.remove();
                        changed = true;

                        EasyVipConfig.VipTierDefinition tierDef = EasyVipConfig.tiers.list.get(record.getTierId());
                        if (player != null && tierDef != null) {
                            Map<String, String> ctx = new HashMap<>();
                            ctx.put("tier_id", record.getTierId());
                            ctx.put("tier_display", tierDef.displayName);

                            if (record.isActive()) {
                                ActionExecutor.execute(player, tierDef.actionsOnUnsetActive, ctx);
                            }
                            ActionExecutor.execute(player, tierDef.actionsOnExpire, ctx);
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.vipExpired, ctx)
                            ));
                        }

                        PersistenceManager.log("System", "vip_expired", "VIP tier " + record.getTierId() + " expired for " + uuid);
                    }
                }

                if (changed) {
                    evaluateActiveVip(server, uuid, registry);
                    PersistenceManager.updatePlayerVips(uuid, registry);
                }
            }
        }
    }
}
