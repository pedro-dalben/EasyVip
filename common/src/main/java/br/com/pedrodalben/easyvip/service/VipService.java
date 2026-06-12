package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.action.ActionContext;
import br.com.pedrodalben.easyvip.action.ActionExecutor;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.*;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import br.com.pedrodalben.easyvip.util.DurationParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VipService {

    private static final Pattern SCRIPT_VARIABLE_ASSIGNMENT = Pattern.compile("^\\$([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)$");

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

            enrichVipContext(ctx, uuid, targetName, tierDef, duration, now, expiry);
            if (isOnline) {
                executeVipActivationFlow(server, uuid, player, targetName, tierDef, ctx, "vip_activate", tierDef.messages.activated);
                broadcastVipActivation(server, targetName, tierDef.displayName);
            } else {
                record.setPendingActivateActions(true);
            }
        } else {
            // Extension or stack check
            if (!tierDef.allowStacking) {
                if (tierDef.activationMode.equalsIgnoreCase("replace")) {
                    long expiry = (duration == -1) ? -1 : now + duration;
                    record.setExpiryTime(expiry);
                    record.setStartTime(now);
                    enrichVipContext(ctx, uuid, targetName, tierDef, duration, now, expiry);
                    if (isOnline) {
                        executeVipActivationFlow(server, uuid, player, targetName, tierDef, ctx, "vip_replace", tierDef.messages.activated);
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
                    enrichVipContext(ctx, uuid, targetName, tierDef, duration, now, record.getExpiryTime());
                    if (isOnline) {
                        executeVipActivationFlow(server, uuid, player, targetName, tierDef, ctx, "vip_stack_perm", EasyVipConfig.messages.vipExtended);
                    } else {
                        record.setPendingActivateActions(true);
                    }
                } else if (duration == -1) {
                    // Upgrading to permanent
                    record.setExpiryTime(-1);
                    enrichVipContext(ctx, uuid, targetName, tierDef, duration, now, record.getExpiryTime());
                    if (isOnline) {
                        executeVipActivationFlow(server, uuid, player, targetName, tierDef, ctx, "vip_upgrade_perm", EasyVipConfig.messages.vipExtended);
                    } else {
                        record.setPendingActivateActions(true);
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
                    enrichVipContext(ctx, uuid, targetName, tierDef, addedDuration, now, newExpiry);
                    if (isOnline) {
                        executeVipActivationFlow(server, uuid, player, targetName, tierDef, ctx, "vip_extend", EasyVipConfig.messages.vipExtended);
                    } else {
                        record.setPendingActivateActions(true);
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

    private static void executeVipActivationFlow(MinecraftServer server, UUID uuid, ServerPlayer player, String playerName,
                                                 EasyVipConfig.VipTierDefinition tierDef, Map<String, String> ctx,
                                                 String source, String messageTemplate) {
        if (tierDef == null || player == null) {
            return;
        }

        if (tierDef.actionsOnActivate != null && !tierDef.actionsOnActivate.isEmpty()) {
            executeTierActions(server, uuid, playerName, player, tierDef.actionsOnActivate, ctx, source + "_legacy");
        }

        if (tierDef.commands != null && tierDef.commands.activate != null && !tierDef.commands.activate.isEmpty()) {
            executeServerCommandList(server, uuid, player, playerName, tierDef.commands.activate, ctx, source + "_commands");
        }

        if (messageTemplate != null && !messageTemplate.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + messageTemplate, ctx)
            ));
        }

        executeActivationItems(server, player, tierDef, ctx, source);
    }

    private static void executeVipExpireFlow(MinecraftServer server, UUID uuid, ServerPlayer player, String playerName,
                                             EasyVipConfig.VipTierDefinition tierDef, Map<String, String> ctx, String source) {
        if (tierDef == null) {
            return;
        }

        if (tierDef.actionsOnExpire != null && !tierDef.actionsOnExpire.isEmpty()) {
            executeTierActions(server, uuid, playerName, player, tierDef.actionsOnExpire, ctx, source + "_legacy");
        }

        if (tierDef.commands != null && tierDef.commands.expire != null && !tierDef.commands.expire.isEmpty()) {
            executeServerCommandList(server, uuid, player, playerName, tierDef.commands.expire, ctx, source + "_commands");
        }

        if (player != null && tierDef.messages != null && tierDef.messages.expired != null && !tierDef.messages.expired.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + tierDef.messages.expired, ctx)
            ));
        }
    }

    private static void executeActivationItems(MinecraftServer server, ServerPlayer player, EasyVipConfig.VipTierDefinition tierDef,
                                               Map<String, String> ctx, String source) {
        if (server == null || player == null || tierDef == null || tierDef.activationItems.isEmpty()) {
            return;
        }

        String broadcastTemplate = (tierDef.messages != null && tierDef.messages.rareItemBroadcast != null && !tierDef.messages.rareItemBroadcast.isEmpty())
                ? tierDef.messages.rareItemBroadcast
                : EasyVipConfig.messages.vipLuckyItemBroadcast;

        for (EasyVipConfig.VipActivationItemDefinition itemDef : tierDef.activationItems) {
            if (itemDef == null) {
                continue;
            }

            double chance = Math.max(0.0d, Math.min(100.0d, itemDef.chance));
            boolean awarded = chanceSucceeded(chance, ThreadLocalRandom.current().nextDouble(100.0d));
            if (!awarded) {
                continue;
            }

            ItemStack stack = buildActivationItemStack(server, itemDef);
            if (stack.isEmpty()) {
                continue;
            }

            player.getInventory().add(stack);

            if (chance < 100.0d && server.getPlayerList() != null) {
                Map<String, String> luckyCtx = new HashMap<>(ctx);
                luckyCtx.put("item_name", stack.getHoverName().getString());
                luckyCtx.put("chance", formatChance(chance));
                String message = ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + broadcastTemplate, luckyCtx);
                server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
            }
        }
    }

    static boolean chanceSucceeded(double chance, double roll) {
        if (chance >= 100.0d) {
            return true;
        }
        if (chance <= 0.0d) {
            return false;
        }
        return roll < chance;
    }

    private static ItemStack parseActivationItemStack(MinecraftServer server, String stackSnbt) {
        try {
            CompoundTag tag = NbtUtils.snbtToStructure(stackSnbt);
            ItemStack stack = ItemStack.parseOptional(server.registryAccess(), tag);
            return stack != null ? stack : ItemStack.EMPTY;
        } catch (Exception e) {
            if (EasyVipConfig.common.debug) {
                e.printStackTrace();
            }
            return ItemStack.EMPTY;
        }
    }

    private static ItemStack buildActivationItemStack(MinecraftServer server, EasyVipConfig.VipActivationItemDefinition itemDef) {
        if (server == null || itemDef == null) {
            return ItemStack.EMPTY;
        }

        if (itemDef.stackSnbt != null && !itemDef.stackSnbt.isBlank()) {
            return parseActivationItemStack(server, itemDef.stackSnbt);
        }

        if (itemDef.itemId == null || itemDef.itemId.isBlank()) {
            return ItemStack.EMPTY;
        }

        ResourceLocation itemId = normalizeResourceLocation(itemDef.itemId);
        if (itemId == null) {
            return ItemStack.EMPTY;
        }

        var item = BuiltInRegistries.ITEM.get(itemId);
        if (item == null) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = new ItemStack(item, Math.max(1, itemDef.amount));
        var enchantmentRegistry = server.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        for (Map.Entry<String, Integer> enchant : itemDef.enchants.entrySet()) {
            if (enchant.getKey() == null || enchant.getKey().isBlank() || enchant.getValue() == null || enchant.getValue() < 1) {
                continue;
            }
            ResourceLocation enchantId = normalizeResourceLocation(enchant.getKey());
            if (enchantId == null) {
                continue;
            }
            enchantmentRegistry.getHolder(enchantId).ifPresent(holder -> stack.enchant(holder, enchant.getValue()));
        }
        return stack;
    }

    private static ResourceLocation normalizeResourceLocation(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String normalized = id.trim();
        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized;
        }
        return ResourceLocation.tryParse(normalized);
    }

    private static void executeServerCommandList(MinecraftServer server, UUID uuid, ServerPlayer player, String playerName,
                                                 List<String> commands, Map<String, String> ctx, String source) {
        if (server == null || commands == null || commands.isEmpty()) {
            return;
        }

        Map<String, String> scriptContext = new HashMap<>(ctx);
        for (String command : commands) {
            if (command == null || command.trim().isEmpty()) {
                continue;
            }
            String trimmed = command.trim();
            Matcher matcher = SCRIPT_VARIABLE_ASSIGNMENT.matcher(trimmed);
            if (matcher.matches()) {
                String variableName = matcher.group(1);
                String valueExpression = matcher.group(2);
                String value = ActionExecutor.resolvePlaceholders(valueExpression, scriptContext);
                scriptContext.put("var." + variableName, value);
                continue;
            }

            Map<String, Object> action = new LinkedHashMap<>();
            action.put("type", "run_server_command");
            action.put("command", ActionExecutor.resolvePlaceholders(command, scriptContext));
            executeTierActions(server, uuid, playerName, player, List.of(action), scriptContext, source);
        }
    }

    private static String formatChance(double chance) {
        if (chance == Math.rint(chance)) {
            return String.valueOf((long) chance);
        }
        return String.valueOf(chance);
    }

    private static String formatTimestamp(long millis) {
        if (millis < 0) {
            return EasyVipConfig.messages.durationPermanent;
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(millis));
    }

    private static String formatDays(long millis) {
        if (millis < 0) {
            return EasyVipConfig.messages.durationPermanent;
        }
        long days = millis / (24L * 60L * 60L * 1000L);
        return String.valueOf(Math.max(0L, days));
    }

    private static void enrichVipContext(Map<String, String> ctx, UUID uuid, String playerName,
                                          EasyVipConfig.VipTierDefinition tierDef, long durationMillis,
                                          long startTime, long expiryTime) {
        if (ctx == null) {
            return;
        }

        ctx.put("uuid", uuid.toString());
        ctx.put("player_uuid", uuid.toString());
        ctx.put("player", playerName);
        if (tierDef != null) {
            ctx.put("vip_id", tierDef.id);
            ctx.put("vip_name", tierDef.displayName);
            ctx.put("tier_id", tierDef.id);
            ctx.put("tier_display", tierDef.displayName);
        }
        if (!ctx.containsKey("duration")) {
            ctx.put("duration", durationMillis == -1 ? EasyVipConfig.messages.durationPermanent : DurationParser.formatDuration(durationMillis));
        }
        ctx.put("days", formatDays(durationMillis));
        ctx.put("activation_date", formatTimestamp(startTime));
        ctx.put("expiration_date", formatTimestamp(expiryTime));
        long remainingMillis = expiryTime < 0 ? -1 : Math.max(0L, expiryTime - System.currentTimeMillis());
        ctx.put("remaining_days", formatDays(remainingMillis));
        ctx.put("remaining_time", remainingMillis < 0 ? EasyVipConfig.messages.durationPermanent : DurationParser.formatDuration(remainingMillis));
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
                executeUnsetActiveActions(server, uuid, player, tierId, tierDef, ctx, "vip_remove_unset_active");
            } else {
                executeUnsetActiveActions(server, uuid, player, tierId, tierDef, ctx, "vip_remove_unset_active_offline");
            }
            executeTierActions(server, uuid, resolvePlayerName(server, uuid), player, tierDef.actionsOnRemove, ctx, "vip_remove");
        } else if (tierDef != null) {
            Map<String, String> ctx = new HashMap<>();
            ctx.put("tier_id", tierId);
            ctx.put("tier_display", tierDef.displayName);
            ctx.put("player", resolvePlayerName(server, uuid));
            ctx.put("player_uuid", uuid.toString());
            executeUnsetActiveActions(server, uuid, null, tierId, tierDef, ctx, "vip_remove_offline_unset");
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
                    executeUnsetActiveActions(server, uuid, player, record.getTierId(), oldDef, ctx, "vip_active_unset");
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
                executeSetActiveActions(server, uuid, player, newDef, ctx, "vip_active_set");
            }
        }

        if (player != null) {
            registry.setLastObservedActiveVip(tierId);
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
                Map<String, String> ctx = new HashMap<>();
                ctx.put("tier_id", lastObserved);
                ctx.put("tier_display", oldDef != null ? oldDef.displayName : lastObserved);
                ctx.put("player", resolvePlayerName(server, uuid));
                ctx.put("player_uuid", uuid.toString());
                executeUnsetActiveActions(server, uuid, player, lastObserved, oldDef, ctx, "vip_deactivate_old");
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
                    executeSetActiveActions(server, uuid, player, newDef, ctx, "vip_activate_new");
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
                    long originalDuration = record.getExpiryTime() == -1 ? -1 : (record.getExpiryTime() - record.getStartTime());
                    enrichVipContext(ctx, uuid, playerName, tierDef, originalDuration, record.getStartTime(), record.getExpiryTime());
                    if (record.isActive()) {
                        executeUnsetActiveActions(server, uuid, player, record.getTierId(), tierDef, ctx, "vip_expire_unset_active");
                    }
                    executeVipExpireFlow(server, uuid, player, playerName, tierDef, ctx, "vip_expire");
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
                    enrichVipContext(ctx, uuid, player.getGameProfile().getName(), tierDef, remaining, record.getStartTime(), record.getExpiryTime());
                    executeVipActivationFlow(server, uuid, player, player.getGameProfile().getName(), tierDef, ctx, "vip_pending_activate", tierDef.messages.activated);
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

    private static void executeSetActiveActions(MinecraftServer server, UUID uuid, ServerPlayer player, EasyVipConfig.VipTierDefinition tierDef, Map<String, String> ctx, String source) {
        if (tierDef == null) return;
        List<Map<String, Object>> actions = new ArrayList<>();
        if (tierDef.actionsOnSetActive != null) {
            actions.addAll(tierDef.actionsOnSetActive);
        }
        if (EasyVipConfig.integrations.ftbRanksEnabled) {
            Map<String, Object> ftbAction = new HashMap<>();
            ftbAction.put("type", "add_ftb_rank");
            ftbAction.put("rank", tierDef.id);
            actions.add(ftbAction);
        }
        if (EasyVipConfig.integrations.luckpermsEnabled) {
            Map<String, Object> lpAction = new HashMap<>();
            lpAction.put("type", "add_luckperms_group");
            lpAction.put("group", tierDef.id);
            actions.add(lpAction);
        }
        executeTierActions(server, uuid, resolvePlayerName(server, uuid), player, actions, ctx, source);
    }

    private static void executeUnsetActiveActions(MinecraftServer server, UUID uuid, ServerPlayer player, String tierId, EasyVipConfig.VipTierDefinition tierDef, Map<String, String> ctx, String source) {
        List<Map<String, Object>> actions = new ArrayList<>();
        if (tierDef != null && tierDef.actionsOnUnsetActive != null) {
            actions.addAll(tierDef.actionsOnUnsetActive);
        }
        if (EasyVipConfig.integrations.ftbRanksEnabled) {
            Map<String, Object> ftbAction = new HashMap<>();
            ftbAction.put("type", "remove_ftb_rank");
            ftbAction.put("rank", tierId);
            actions.add(ftbAction);
        }
        if (EasyVipConfig.integrations.luckpermsEnabled) {
            Map<String, Object> lpAction = new HashMap<>();
            lpAction.put("type", "remove_luckperms_group");
            lpAction.put("group", tierId);
            actions.add(lpAction);
        }
        executeTierActions(server, uuid, resolvePlayerName(server, uuid), player, actions, ctx, source);
    }

    private static boolean executeTierActions(MinecraftServer server, UUID uuid, String playerName, ServerPlayer onlinePlayer,
                                              List<Map<String, Object>> actions, Map<String, String> ctx, String source) {
        ActionContext actionContext = (onlinePlayer != null)
                ? ActionContext.online(onlinePlayer, source)
                : ActionContext.offline(server, uuid, playerName, source);
        return ActionExecutor.execute(actionContext, actions, ctx);
    }
}
