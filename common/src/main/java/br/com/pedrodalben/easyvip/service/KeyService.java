package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.action.ActionExecutor;
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

public final class KeyService {

    private static final Map<UUID, PendingConfirmation> confirmations = new HashMap<>();

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

    public static RedeemResult redeemKey(ServerPlayer player, String rawCode, boolean bypassConfirm) {
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

            success = VipService.addVip(player.getServer(), uuid, record.getTierId(), record.getDuration(), playerName);
        } else if (record.getType().equalsIgnoreCase("reward")) {
            ctx.put("reward_key_id", record.getRewardKeyId());
            EasyVipConfig.RewardKeyDefinition rkDef = EasyVipConfig.rewardKeys.list.get(record.getRewardKeyId());
            if (rkDef == null) {
                player.sendSystemMessage(Component.literal(
                        ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + "&cRecompensa não encontrada ou inválida. A chave não foi consumida.", ctx)
                ));
                return RedeemResult.ERROR;
            }
            if (EasyVipConfig.common.allowedDimensions != null && !EasyVipConfig.common.allowedDimensions.isEmpty()) {
                if (!isDimensionAllowed(player.level().dimension().location().toString(), EasyVipConfig.common.allowedDimensions)) {
                    return RedeemResult.ERROR;
                }
            }
            if (!rkDef.allowedDimensions.isEmpty() && !isDimensionAllowed(player.level().dimension().location().toString(), rkDef.allowedDimensions)) {
                return RedeemResult.ERROR;
            }

            List<Map<String, Object>> actions = record.getActions();
            if (actions == null || actions.isEmpty()) {
                actions = rkDef.actions;
                ctx.put("key_display", rkDef.displayName);
            }
            if (actions == null || actions.isEmpty()) {
                player.sendSystemMessage(Component.literal(
                        ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + "&cRecompensa não encontrada ou inválida. A chave não foi consumida.", ctx)
                ));
                return RedeemResult.ERROR;
            }

            Long lastUsed = record.getLastUsedAtBy().get(uuid);
            long cooldownMs = rkDef.cooldownSeconds > 0 ? rkDef.cooldownSeconds * 1000L : 0L;
            if (cooldownMs > 0 && lastUsed != null && System.currentTimeMillis() - lastUsed < cooldownMs) {
                return RedeemResult.ERROR;
            }

            boolean actionsOk = ActionExecutor.execute(player, actions, ctx);
            if (!actionsOk) {
                player.sendSystemMessage(Component.literal(
                        ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + "&cRecompensa não encontrada ou inválida. A chave não foi consumida.", ctx)
                ));
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

            confirmations.remove(uuid);
            PersistenceManager.log(playerName, "redeem_key", "Redeemed key: " + code);
            return RedeemResult.SUCCESS;
        }

        return RedeemResult.ERROR;
    }

    private static boolean isDimensionAllowed(String dimensionId, List<String> allowedList) {
        String normalized = dimensionId == null ? "" : dimensionId.toLowerCase();
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
        return redeemKey(player, pc.code, true);
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
        tag.putBoolean("easyvip_item_key", true);
        tag.putString("easyvip_key", keyCode);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }
}
