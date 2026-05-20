package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.action.ActionExecutor;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.KeyRecord;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.security.SecureRandom;
import java.util.*;

public final class KeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
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
        String charset = EasyVipConfig.common.keyCharset;
        int length = EasyVipConfig.common.keyLength;
        String prefix = EasyVipConfig.common.keyPrefix;

        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < length; i++) {
            sb.append(charset.charAt(RANDOM.nextInt(charset.length())));
        }
        return sb.toString();
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

        if (record.getType().equalsIgnoreCase("vip")) {
            EasyVipConfig.VipTierDefinition tierDef = EasyVipConfig.tiers.list.get(record.getTierId());
            String tierDisplay = (tierDef != null) ? tierDef.displayName : record.getTierId();

            ctx.put("tier_id", record.getTierId());
            ctx.put("tier_display", tierDisplay);
            ctx.put("duration", record.getDuration());

            success = VipService.addVip(player.getServer(), uuid, record.getTierId(), record.getDuration(), player.getGameProfile().getName());
        } else if (record.getType().equalsIgnoreCase("reward")) {
            ctx.put("reward_key_id", record.getRewardKeyId());
            List<Map<String, Object>> actions = record.getActions();
            if (actions == null || actions.isEmpty()) {
                EasyVipConfig.RewardKeyDefinition rkDef = EasyVipConfig.rewardKeys.list.get(record.getRewardKeyId());
                if (rkDef != null) {
                    actions = rkDef.actions;
                    ctx.put("key_display", rkDef.displayName);
                }
            }

            ActionExecutor.execute(player, actions, ctx);
            success = true;
        }

        if (success) {
            record.setUsedCount(record.getUsedCount() + 1);
            record.getUsedBy().add(uuid);
            PersistenceManager.putKey(record);

            confirmations.remove(uuid);
            PersistenceManager.log(player.getGameProfile().getName(), "redeem_key", "Redeemed key: " + code);
            return RedeemResult.SUCCESS;
        }

        return RedeemResult.ERROR;
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
}
