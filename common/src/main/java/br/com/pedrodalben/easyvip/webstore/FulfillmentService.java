package br.com.pedrodalben.easyvip.webstore;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.KeyRecord;
import br.com.pedrodalben.easyvip.persistence.SqlDatabaseManager;
import br.com.pedrodalben.easyvip.service.KeyService;
import br.com.pedrodalben.easyvip.util.KeySecurity;
import br.com.pedrodalben.easyvip.webstore.model.FulfillmentItemRecord;
import br.com.pedrodalben.easyvip.webstore.model.FulfillmentRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public final class FulfillmentService {

    private FulfillmentService() {}

    public static final class FulfillmentResult {
        public final String status;
        public final String fulfillmentId;
        public final List<ItemResult> items;
        public final int httpStatus;
        public final String errorCode;

        private FulfillmentResult(String status, String fulfillmentId, List<ItemResult> items, int httpStatus, String errorCode) {
            this.status = status;
            this.fulfillmentId = fulfillmentId;
            this.items = items != null ? items : new ArrayList<>();
            this.httpStatus = httpStatus;
            this.errorCode = errorCode;
        }

        public static FulfillmentResult created(String fulfillmentId, List<ItemResult> items) {
            return new FulfillmentResult("created", fulfillmentId, items, 201, null);
        }

        public static FulfillmentResult alreadyCreated(String fulfillmentId) {
            return new FulfillmentResult("already_created", fulfillmentId, new ArrayList<>(), 200, null);
        }

        public static FulfillmentResult error(int httpStatus, String errorCode) {
            return new FulfillmentResult("error", null, null, httpStatus, errorCode);
        }
    }

    public static final class ItemResult {
        public final String lineItemId;
        public final String productSku;
        public final String activationKey;
        public final String keyFingerprint;

        public ItemResult(String lineItemId, String productSku, String activationKey, String keyFingerprint) {
            this.lineItemId = lineItemId;
            this.productSku = productSku;
            this.activationKey = activationKey;
            this.keyFingerprint = keyFingerprint;
        }
    }

    public static FulfillmentResult processFulfillment(
            String fulfillmentId, String orderId, String minecraftUuid, String minecraftUsername,
            List<Map<String, Object>> jsonItems, String rawRequestBody, String requestKeyId) {

        FulfillmentConfig cfg = EasyVipConfig.fulfillment;
        if (!cfg.enabled) {
            return FulfillmentResult.error(503, "fulfillment_disabled");
        }

        if (jsonItems == null || jsonItems.isEmpty()) {
            return FulfillmentResult.error(400, "empty_items");
        }

        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(minecraftUuid);
        } catch (IllegalArgumentException e) {
            return FulfillmentResult.error(422, "invalid_uuid");
        }

        for (Map<String, Object> itemData : jsonItems) {
            String sku = itemData.get("product_sku") != null ? itemData.get("product_sku").toString() : "";
            FulfillmentProductConfig product = cfg.products.get(sku);
            if (product == null) {
                return FulfillmentResult.error(422, "unknown_sku:" + sku);
            }

            String error = validateProduct(product);
            if (error != null) {
                return FulfillmentResult.error(422, error + ":" + sku);
            }
        }

        if (!SqlDatabaseManager.isInitialized()) {
            return FulfillmentResult.error(503, "sql_unavailable");
        }

        String payloadDigest = sha256(rawRequestBody);

        FulfillmentRecord existing = SqlDatabaseManager.getFulfillment(fulfillmentId);
        if (existing != null) {
            if (payloadDigest.equals(existing.getPayloadDigest())) {
                return rebuildFromExisting(existing);
            }
            return FulfillmentResult.error(409, "idempotency_conflict");
        }

        FulfillmentRecord record = new FulfillmentRecord();
        record.setFulfillmentId(fulfillmentId);
        record.setOrderId(orderId != null ? orderId : "");
        record.setMinecraftUuid(minecraftUuid);
        record.setMinecraftUsername(minecraftUsername != null ? minecraftUsername : "");
        record.setPayloadDigest(payloadDigest);
        record.setStatus("completed");
        record.setRequestKeyId(requestKeyId);
        long now = System.currentTimeMillis();
        record.setCreatedAt(now);
        record.setCompletedAt(now);

        List<ItemResult> results = new ArrayList<>();

        for (Map<String, Object> itemData : jsonItems) {
            String lineItemId = itemData.get("line_item_id") != null ? itemData.get("line_item_id").toString() : "";
            String sku = itemData.get("product_sku").toString();
            int quantity = itemData.get("quantity") instanceof Number n ? n.intValue() : 1;

            FulfillmentProductConfig product = cfg.products.get(sku);

            FulfillmentItemRecord itemRec = new FulfillmentItemRecord();
            itemRec.setLineItemId(lineItemId);
            itemRec.setFulfillmentId(fulfillmentId);
            itemRec.setProductSku(sku);
            itemRec.setQuantity(quantity);
            itemRec.setCreatedAt(now);

            try {
                KeyRecord keyRec = generateKey(product, playerUuid, now);
                itemRec.setKeyCode(keyRec.getCode());
                itemRec.setKeyFingerprint(KeySecurity.fingerprintKey(keyRec.getCode()));
                itemRec.setStatus("created");

                results.add(new ItemResult(lineItemId, sku, keyRec.getCode(), itemRec.getKeyFingerprint()));
            } catch (Exception e) {
                itemRec.setStatus("failed");
                itemRec.setKeyCode(null);
                itemRec.setKeyFingerprint(null);
                record.setStatus("partial_failure");
                record.setFailureCode("key_generation_failed:" + sku);
                System.err.println("[EasyVip-Fulfillment] Failed to generate key for SKU " + sku
                        + ": " + e.getMessage());
            }

            record.getItems().add(itemRec);
        }

        if ("partial_failure".equals(record.getStatus())) {
            return FulfillmentResult.error(500, "partial_failure");
        }

        boolean saved = SqlDatabaseManager.insertFulfillmentTransaction(record);
        if (!saved) {
            System.err.println("[EasyVip-Fulfillment] Failed to persist fulfillment " + fulfillmentId);
            return FulfillmentResult.error(500, "persistence_failure");
        }

        return FulfillmentResult.created(fulfillmentId, results);
    }

    private static String validateProduct(FulfillmentProductConfig product) {
        switch (product.kind) {
            case "vip":
                if (product.tierId == null || product.tierId.isBlank()) return "missing_tier_id";
                if (!EasyVipConfig.tiers.list.containsKey(product.tierId)) return "unknown_tier";
                if (product.duration == null || product.duration.isBlank()) return "missing_duration";
                try {
                    long dur = br.com.pedrodalben.easyvip.util.DurationParser.parseDurationMillis(product.duration);
                    if (dur == 0 || (dur < 0 && dur != -1)) return "invalid_duration";
                } catch (Exception e) {
                    return "invalid_duration";
                }
                break;
            case "reward":
                if (product.rewardKeyId == null || product.rewardKeyId.isBlank()) return "missing_reward_key_id";
                if (!EasyVipConfig.rewardKeys.list.containsKey(product.rewardKeyId)) return "unknown_reward_key";
                break;
            default:
                return "invalid_kind:" + product.kind;
        }
        return null;
    }

    private static KeyRecord generateKey(FulfillmentProductConfig product, UUID playerUuid, long now) {
        long expiryTime = product.parseExpiresAfterMillis();
        if (expiryTime != -1) {
            expiryTime = now + expiryTime;
        }

        switch (product.kind) {
            case "vip":
                return KeyService.generateVipKey(product.tierId, product.duration, product.maxUses,
                        product.bindToPlayer ? playerUuid : null, expiryTime, null);
            case "reward":
                return KeyService.generateRewardKey(product.rewardKeyId, product.maxUses,
                        product.bindToPlayer ? playerUuid : null, expiryTime, null);
            default:
                throw new IllegalArgumentException("Unknown product kind: " + product.kind);
        }
    }

    private static FulfillmentResult rebuildFromExisting(FulfillmentRecord existing) {
        if ("completed".equals(existing.getStatus()) || "partial_failure".equals(existing.getStatus())) {
            List<ItemResult> items = new ArrayList<>();
            for (FulfillmentItemRecord item : existing.getItems()) {
                if ("created".equals(item.getStatus()) && item.getKeyCode() != null) {
                    items.add(new ItemResult(item.getLineItemId(), item.getProductSku(),
                            item.getKeyCode(), item.getKeyFingerprint()));
                }
            }
            return new FulfillmentResult("already_created", existing.getFulfillmentId(), items, 200, null);
        }
        return FulfillmentResult.alreadyCreated(existing.getFulfillmentId());
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
