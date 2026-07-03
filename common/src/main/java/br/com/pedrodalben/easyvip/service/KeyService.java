package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.action.ActionExecutor;
import br.com.pedrodalben.easyvip.action.ActionContext;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.KeyRecord;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import br.com.pedrodalben.easyvip.util.UniqueCodeGenerator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.*;
import java.util.function.Function;

public final class KeyService {

    private static final Map<UUID, PendingConfirmation> confirmations = new HashMap<>();
    private static final Map<UUID, Map<CommandThrottleType, Long>> commandCooldowns = new HashMap<>();

    private enum CommandThrottleType {
        USE,
        CONFIRM
    }

    private KeyService() {
    }

    public static class PendingConfirmation {
        public final String code;
        public final long timestamp;

        public PendingConfirmation(String code) {
            this.code = code;
            this.timestamp = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > (EasyVipConfig.common.confirmTimeoutSeconds * 1000L);
        }
    }

    public enum RedeemResult {
        SUCCESS,
        INVALID_KEY,
        EXPIRED,
        NO_USES_LEFT,
        ON_COOLDOWN,
        ALREADY_USED,
        BOUND_TO_OTHER,
        CONFIRMATION_REQUIRED,
        ERROR
    }

    public static String generateRandomCode() {
        return generateRandomCode(PersistenceManager::getKey);
    }

    public static String generateRandomCode(java.util.function.Function<String, KeyRecord> keyLookup) {
        return UniqueCodeGenerator.generate(
                EasyVipConfig.common.keyCharset,
                EasyVipConfig.common.keyLength,
                EasyVipConfig.common.keyPrefix,
                candidate -> keyLookup.apply(candidate) == null,
                1000
        );
    }

    public static KeyRecord generateVipKey(String tierId, String durationStr, int maxUses, UUID boundPlayer, long expiryTime, List<Map<String, Object>> actions) {
        KeyRecord record = new KeyRecord();
        record.setCode(generateRandomCode());
        record.setType("vip");
        record.setTierId(tierId);
        record.setDuration(durationStr);
        record.setMaxUses(maxUses);
        record.setBoundPlayerUuid(boundPlayer);
        record.setCreatedTime(System.currentTimeMillis());
        record.setExpiryTime(expiryTime);
        if (actions != null) {
            record.setActions(actions);
        }

        PersistenceManager.putKey(record);
        PersistenceManager.log("System", "generate_vip_key", "Generated VIP key " + record.getCode() + " for tier " + tierId);
        return record;
    }

    public static KeyRecord generateRewardKey(String rewardKeyId, int maxUses, UUID boundPlayer, long expiryTime, List<Map<String, Object>> actions) {
        KeyRecord record = new KeyRecord();
        record.setCode(generateRandomCode());
        record.setType("reward");
        record.setRewardKeyId(rewardKeyId);
        record.setMaxUses(maxUses);
        record.setBoundPlayerUuid(boundPlayer);
        record.setCreatedTime(System.currentTimeMillis());
        record.setExpiryTime(expiryTime);
        if (actions != null) {
            record.setActions(actions);
        }

        PersistenceManager.putKey(record);
        PersistenceManager.log("System", "generate_reward_key", "Generated Reward key " + record.getCode() + " of definition " + rewardKeyId);
        return record;
    }

    public static KeyRecord generateCustomKey(List<Map<String, Object>> actions, int maxUses, UUID boundPlayer, long expiryTime) {
        KeyRecord record = new KeyRecord();
        record.setCode(generateRandomCode());
        record.setType("custom");
        record.setMaxUses(maxUses);
        record.setBoundPlayerUuid(boundPlayer);
        record.setCreatedTime(System.currentTimeMillis());
        record.setExpiryTime(expiryTime);
        if (actions != null) {
            record.setActions(actions);
        }

        PersistenceManager.putKey(record);
        PersistenceManager.log("System", "generate_custom_key", "Generated Custom key " + record.getCode() + " with " + (actions != null ? actions.size() : 0) + " actions");
        return record;
    }

    public static RedeemResult redeemKey(ServerPlayer player, String rawCode, boolean bypassConfirm) {
        return redeemKey(player, rawCode, bypassConfirm, CommandThrottleType.USE, true);
    }

    public static RedeemResult redeemKey(ServerPlayer player, String rawCode, boolean bypassConfirm, CommandThrottleType throttleType) {
        return redeemKey(player, rawCode, bypassConfirm, throttleType, true);
    }

    private static RedeemResult redeemKey(ServerPlayer player, String rawCode, boolean bypassConfirm, CommandThrottleType throttleType, boolean applyCooldown) {
        String code = EasyVipConfig.common.caseSensitiveKeys ? rawCode.trim() : rawCode.trim().toUpperCase();
        KeyRecord record = PersistenceManager.getKey(code);
        if (record == null && !EasyVipConfig.common.caseSensitiveKeys) {
            // Try exact match fallback
            record = PersistenceManager.getKey(rawCode.trim());
        }

        if (record == null) {
            return RedeemResult.INVALID_KEY;
        }

        if (record.isExpired()) {
            return RedeemResult.EXPIRED;
        }

        if (record.isFullyUsed()) {
            return RedeemResult.NO_USES_LEFT;
        }

        UUID uuid = player.getUUID();
        if (applyCooldown) {
            RedeemResult cooldown = checkCooldown(uuid, throttleType);
            if (cooldown != null) {
                return cooldown;
            }
        }
        if (record.getBoundPlayerUuid() != null && !record.getBoundPlayerUuid().equals(uuid)) {
            return RedeemResult.BOUND_TO_OTHER;
        }

        if (record.getUsedBy().contains(uuid)) {
            return RedeemResult.ALREADY_USED;
        }

        // Confirmation check
        if (EasyVipConfig.common.confirmBeforeUse && !bypassConfirm) {
            PendingConfirmation pc = confirmations.get(uuid);
            if (pc == null || !pc.code.equals(code) || pc.isExpired()) {
                confirmations.put(uuid, new PendingConfirmation(code));
                return RedeemResult.CONFIRMATION_REQUIRED;
            }
        }

        // Consume key
        boolean success = false;
        Map<String, String> ctx = new HashMap<>();
        String playerName = resolvePlayerName(player.getServer(), uuid);
        ctx.put("player", playerName);
        ctx.put("player_uuid", uuid.toString());

        if (record.getType().equalsIgnoreCase("vip")) {
            EasyVipConfig.VipTierDefinition tierDef = EasyVipConfig.tiers.list.get(record.getTierId());
            String tierDisplay = (tierDef != null) ? tierDef.displayName : record.getTierId();

            ctx.put("tier_id", record.getTierId());
            ctx.put("tier_display", tierDisplay);
            ctx.put("duration", record.getDuration());

            if ((EasyVipConfig.common.allowedDimensions != null && !EasyVipConfig.common.allowedDimensions.isEmpty())
                    || (EasyVipConfig.common.denyDimensions != null && !EasyVipConfig.common.denyDimensions.isEmpty())) {
                if (!isDimensionAllowed(player.level().dimension().location().toString(), EasyVipConfig.common.allowedDimensions, EasyVipConfig.common.denyDimensions)) {
                    PersistenceManager.log(playerName, "redeem_key_failed", "VIP dimension blocked for " + code);
                    return RedeemResult.ERROR;
                }
            }
            success = VipService.addVip(player.getServer(), uuid, record.getTierId(), record.getDuration(), playerName);
        } else if (record.getType().equalsIgnoreCase("reward")) {
            ctx.put("reward_key_id", record.getRewardKeyId());
            EasyVipConfig.RewardKeyDefinition rkDef = EasyVipConfig.rewardKeys.list.get(record.getRewardKeyId());
            if (rkDef == null) {
                player.sendSystemMessage(Component.literal(
                        ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.localized(
                                "&cReward not found or invalid. The key was not consumed.",
                                "&cRecompensa não encontrada ou inválida. A chave não foi consumida."
                        ), ctx)
                ));
                PersistenceManager.log(playerName, "redeem_key_failed", "Reward definition missing for " + code);
                return RedeemResult.ERROR;
            }
            if ((EasyVipConfig.common.allowedDimensions != null && !EasyVipConfig.common.allowedDimensions.isEmpty())
                    || (EasyVipConfig.common.denyDimensions != null && !EasyVipConfig.common.denyDimensions.isEmpty())) {
                if (!isDimensionAllowed(player.level().dimension().location().toString(), EasyVipConfig.common.allowedDimensions, EasyVipConfig.common.denyDimensions)) {
                    PersistenceManager.log(playerName, "redeem_key_failed", "Dimension blocked for " + code);
                    return RedeemResult.ERROR;
                }
            }
            if (!rkDef.allowedDimensions.isEmpty()) {
                if (!isDimensionAllowed(player.level().dimension().location().toString(), rkDef.allowedDimensions, Collections.emptyList())) {
                    PersistenceManager.log(playerName, "redeem_key_failed", "Reward dimension blocked for " + code);
                    return RedeemResult.ERROR;
                }
            }

            List<Map<String, Object>> actions = record.getActions();
            if (actions == null || actions.isEmpty()) {
                actions = rkDef.actions;
                ctx.put("key_display", rkDef.displayName);
            }
            if (actions == null || actions.isEmpty()) {
                player.sendSystemMessage(Component.literal(
                        ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.localized(
                                "&cReward not found or invalid. The key was not consumed.",
                                "&cRecompensa não encontrada ou inválida. A chave não foi consumida."
                        ), ctx)
                ));
                PersistenceManager.log(playerName, "redeem_key_failed", "Reward actions missing for " + code);
                return RedeemResult.ERROR;
            }

            Long lastUsed = record.getLastUsedAtBy().get(uuid);
            long cooldownMs = rkDef.cooldownSeconds > 0 ? rkDef.cooldownSeconds * 1000L : 0L;
            if (cooldownMs > 0 && lastUsed != null && System.currentTimeMillis() - lastUsed < cooldownMs) {
                PersistenceManager.log(playerName, "redeem_key_failed", "Reward cooldown active for " + code);
                return RedeemResult.ERROR;
            }

            boolean actionsOk = ActionExecutor.execute(player, actions, ctx);
            if (!actionsOk) {
                player.sendSystemMessage(Component.literal(
                        ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.localized(
                                "&cReward not found or invalid. The key was not consumed.",
                                "&cRecompensa não encontrada ou inválida. A chave não foi consumida."
                        ), ctx)
                ));
                PersistenceManager.log(playerName, "redeem_key_failed", "Reward actions failed for " + code);
                return RedeemResult.ERROR;
            }
            success = true;
        } else if (record.getType().equalsIgnoreCase("custom")) {
            if ((EasyVipConfig.common.allowedDimensions != null && !EasyVipConfig.common.allowedDimensions.isEmpty())
                    || (EasyVipConfig.common.denyDimensions != null && !EasyVipConfig.common.denyDimensions.isEmpty())) {
                if (!isDimensionAllowed(player.level().dimension().location().toString(), EasyVipConfig.common.allowedDimensions, EasyVipConfig.common.denyDimensions)) {
                    PersistenceManager.log(playerName, "redeem_key_failed", "Dimension blocked for " + code);
                    return RedeemResult.ERROR;
                }
            }

            List<Map<String, Object>> actions = record.getActions();
            if (actions == null || actions.isEmpty()) {
                player.sendSystemMessage(Component.literal(
                        ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.localized(
                                "&cCustom actions not found or invalid. The key was not consumed.",
                                "&cAções customizadas não encontradas ou inválidas. A chave não foi consumida."
                        ), ctx)
                ));
                PersistenceManager.log(playerName, "redeem_key_failed", "Custom actions missing for " + code);
                return RedeemResult.ERROR;
            }

            boolean actionsOk = ActionExecutor.execute(player, actions, ctx);
            if (!actionsOk) {
                player.sendSystemMessage(Component.literal(
                        ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.localized(
                                "&cError executing custom actions. The key was not consumed.",
                                "&cErro ao executar ações customizadas. A chave não foi consumida."
                        ), ctx)
                ));
                PersistenceManager.log(playerName, "redeem_key_failed", "Custom actions failed for " + code);
                return RedeemResult.ERROR;
            }
            success = true;
        }

        if (success) {
            if ("reward".equalsIgnoreCase(record.getType())) {
                EasyVipConfig.RewardKeyDefinition rkDef = EasyVipConfig.rewardKeys.list.get(record.getRewardKeyId());
                if (rkDef != null && rkDef.consumeOnUse) {
                    record.setUsedCount(record.getUsedCount() + 1);
                    record.getUsedBy().add(uuid);
                }
                record.getLastUsedAtBy().put(uuid, System.currentTimeMillis());
            } else {
                record.setUsedCount(record.getUsedCount() + 1);
                record.getUsedBy().add(uuid);
            }
            PersistenceManager.putKey(record);
            markCooldown(uuid, throttleType);

            confirmations.remove(uuid);
            PersistenceManager.log(playerName, "redeem_key", "Redeemed key: " + code);
            return RedeemResult.SUCCESS;
        }

        return RedeemResult.ERROR;
    }

    private static RedeemResult checkCooldown(UUID uuid, CommandThrottleType throttleType) {
        long cooldownMs = EasyVipConfig.common.commandCooldownTicks * 50L;
        if (cooldownMs <= 0) {
            return null;
        }

        long now = System.currentTimeMillis();
        Map<CommandThrottleType, Long> byType = commandCooldowns.computeIfAbsent(uuid, k -> new EnumMap<>(CommandThrottleType.class));
        Long lastUsed = byType.get(throttleType);
        if (lastUsed != null && now - lastUsed < cooldownMs) {
            return RedeemResult.ON_COOLDOWN;
        }
        byType.put(throttleType, now);
        return null;
    }

    private static void markCooldown(UUID uuid, CommandThrottleType throttleType) {
        long cooldownMs = EasyVipConfig.common.commandCooldownTicks * 50L;
        if (cooldownMs <= 0) {
            return;
        }
        Map<CommandThrottleType, Long> byType = commandCooldowns.computeIfAbsent(uuid, k -> new EnumMap<>(CommandThrottleType.class));
        byType.put(throttleType, System.currentTimeMillis());
    }

    private static boolean isDimensionAllowed(String dimensionId, List<String> allowedList, List<String> denyList) {
        String normalized = dimensionId == null ? "" : dimensionId.toLowerCase();
        for (String entry : denyList) {
            if (normalized.equals(entry.toLowerCase())) {
                return false;
            }
        }
        if (allowedList == null || allowedList.isEmpty()) {
            return true;
        }
        for (String entry : allowedList) {
            if (normalized.equals(entry.toLowerCase())) {
                return true;
            }
        }
        return false;
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

    public static RedeemResult confirmPending(ServerPlayer player) {
        UUID uuid = player.getUUID();
        PendingConfirmation pc = confirmations.get(uuid);
        if (pc == null || pc.isExpired()) {
            confirmations.remove(uuid);
            return RedeemResult.INVALID_KEY;
        }
        return redeemKey(player, pc.code, true, CommandThrottleType.CONFIRM);
    }

    public static ItemStack createPhysicalKeyItem(String keyCode) {
        ResourceLocation itemId = ResourceLocation.tryParse(EasyVipConfig.common.itemKeyItemId);
        if (itemId == null) {
            throw new IllegalArgumentException("Invalid item key item id: " + EasyVipConfig.common.itemKeyItemId);
        }

        net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(itemId);
        if (item == null) {
            throw new IllegalArgumentException("Configured item key item does not exist: " + EasyVipConfig.common.itemKeyItemId);
        }

        ItemStack stack = new ItemStack(item, 1);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(EasyVipConfig.common.itemKeyMarker, true);
        tag.putString("easyvip_key", keyCode);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    public static boolean isPhysicalKeyItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        ResourceLocation itemId = ResourceLocation.tryParse(EasyVipConfig.common.itemKeyItemId);
        if (itemId == null) {
            return false;
        }

        net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(itemId);
        if (item == null || !stack.is(item)) {
            return false;
        }

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) {
            return false;
        }

        CompoundTag tag = customData.copyTag();
        String markerTag = EasyVipConfig.common.itemKeyMarker;
        return tag.contains(markerTag) && tag.getBoolean(markerTag) && tag.contains("easyvip_key");
    }

    static RedeemResult redeemRewardKeyForTest(KeyRecord record, UUID uuid, String playerName, String dimensionId, boolean applyCooldown, Function<List<Map<String, Object>>, Boolean> actionRunner) {
        if (record == null || uuid == null) {
            return RedeemResult.ERROR;
        }

        if (record.isExpired()) {
            return RedeemResult.EXPIRED;
        }

        if (record.isFullyUsed()) {
            return RedeemResult.NO_USES_LEFT;
        }

        if (record.getBoundPlayerUuid() != null && !record.getBoundPlayerUuid().equals(uuid)) {
            return RedeemResult.BOUND_TO_OTHER;
        }

        if (record.getUsedBy().contains(uuid)) {
            return RedeemResult.ALREADY_USED;
        }

        if (applyCooldown) {
            RedeemResult cooldown = checkCooldown(uuid, CommandThrottleType.USE);
            if (cooldown != null) {
                return cooldown;
            }
            EasyVipConfig.RewardKeyDefinition rkDefCooldown = EasyVipConfig.rewardKeys.list.get(record.getRewardKeyId());
            if (rkDefCooldown != null) {
                Long lastUsed = record.getLastUsedAtBy().get(uuid);
                long cooldownMs = rkDefCooldown.cooldownSeconds > 0 ? rkDefCooldown.cooldownSeconds * 1000L : 0L;
                if (cooldownMs > 0 && lastUsed != null && System.currentTimeMillis() - lastUsed < cooldownMs) {
                    return RedeemResult.ON_COOLDOWN;
                }
            }
        }

        List<Map<String, Object>> actions = record.getActions();
        if ("reward".equalsIgnoreCase(record.getType())) {
            EasyVipConfig.RewardKeyDefinition rkDef = EasyVipConfig.rewardKeys.list.get(record.getRewardKeyId());
            if (rkDef == null) {
                return RedeemResult.ERROR;
            }

            if ((EasyVipConfig.common.allowedDimensions != null && !EasyVipConfig.common.allowedDimensions.isEmpty())
                    || (EasyVipConfig.common.denyDimensions != null && !EasyVipConfig.common.denyDimensions.isEmpty())) {
                if (!isDimensionAllowed(dimensionId, EasyVipConfig.common.allowedDimensions, EasyVipConfig.common.denyDimensions)) {
                    return RedeemResult.ERROR;
                }
            }
            if (!rkDef.allowedDimensions.isEmpty() && !isDimensionAllowed(dimensionId, rkDef.allowedDimensions, Collections.emptyList())) {
                return RedeemResult.ERROR;
            }

            if (actions == null || actions.isEmpty()) {
                actions = rkDef.actions;
            }
            if (actions == null || actions.isEmpty()) {
                return RedeemResult.ERROR;
            }

            if (actionRunner == null || !Boolean.TRUE.equals(actionRunner.apply(actions))) {
                return RedeemResult.ERROR;
            }

            if (rkDef.consumeOnUse) {
                record.setUsedCount(record.getUsedCount() + 1);
                record.getUsedBy().add(uuid);
            }
            record.getLastUsedAtBy().put(uuid, System.currentTimeMillis());
            PersistenceManager.putKey(record);
            if (applyCooldown) {
                markCooldown(uuid, CommandThrottleType.USE);
            }
            return RedeemResult.SUCCESS;
        } else if ("custom".equalsIgnoreCase(record.getType())) {
            if ((EasyVipConfig.common.allowedDimensions != null && !EasyVipConfig.common.allowedDimensions.isEmpty())
                    || (EasyVipConfig.common.denyDimensions != null && !EasyVipConfig.common.denyDimensions.isEmpty())) {
                if (!isDimensionAllowed(dimensionId, EasyVipConfig.common.allowedDimensions, EasyVipConfig.common.denyDimensions)) {
                    return RedeemResult.ERROR;
                }
            }

            if (actions == null || actions.isEmpty()) {
                return RedeemResult.ERROR;
            }

            if (actionRunner == null || !Boolean.TRUE.equals(actionRunner.apply(actions))) {
                return RedeemResult.ERROR;
            }

            record.setUsedCount(record.getUsedCount() + 1);
            record.getUsedBy().add(uuid);
            record.getLastUsedAtBy().put(uuid, System.currentTimeMillis());
            PersistenceManager.putKey(record);
            if (applyCooldown) {
                markCooldown(uuid, CommandThrottleType.USE);
            }
            return RedeemResult.SUCCESS;
        }

        return RedeemResult.ERROR;
    }

    static boolean isPhysicalKeyPayloadValid(String configuredItemId, String actualItemId, boolean markerPresent, boolean hasKeyValue) {
        if (configuredItemId == null || actualItemId == null) {
            return false;
        }
        if (!configuredItemId.equalsIgnoreCase(actualItemId)) {
            return false;
        }
        return markerPresent && hasKeyValue;
    }
}
