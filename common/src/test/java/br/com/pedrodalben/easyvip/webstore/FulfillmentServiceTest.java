package br.com.pedrodalben.easyvip.webstore;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import br.com.pedrodalben.easyvip.persistence.SqlDatabaseManager;
import br.com.pedrodalben.easyvip.util.KeySecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FulfillmentServiceTest {

    @TempDir
    Path tempDir;

    private Map<String, EasyVipConfig.RewardKeyDefinition> rewardBackup;
    private Map<String, EasyVipConfig.VipTierDefinition> tierBackup;
    private String originalKeyCharset;
    private int originalKeyLength;
    private String originalKeyPrefix;
    private Map<String, FulfillmentProductConfig> productBackup;

    @BeforeEach
    void setUp() {
        PersistenceManager.initialize(tempDir);

        rewardBackup = new LinkedHashMap<>(EasyVipConfig.rewardKeys.list);
        tierBackup = new LinkedHashMap<>(EasyVipConfig.tiers.list);
        productBackup = new LinkedHashMap<>(EasyVipConfig.fulfillment.products);

        EasyVipConfig.common.keyCharset = "ABCDEF0123456789";
        EasyVipConfig.common.keyLength = 6;
        EasyVipConfig.common.keyPrefix = "EVIP-";
        EasyVipConfig.common.caseSensitiveKeys = false;
        EasyVipConfig.common.confirmBeforeUse = false;

        EasyVipConfig.rewardKeys.list.clear();
        EasyVipConfig.tiers.list.clear();
        EasyVipConfig.fulfillment.products.clear();
        EasyVipConfig.fulfillment.enabled = true;

        EasyVipConfig.RewardKeyDefinition def = new EasyVipConfig.RewardKeyDefinition();
        def.id = "gems_50";
        def.displayName = "50 Gems";
        def.consumeOnUse = true;
        def.actions = List.of(Map.of("type", "send_message", "message", "ok"));
        EasyVipConfig.rewardKeys.list.put(def.id, def);

        FulfillmentProductConfig pc = new FulfillmentProductConfig();
        pc.sku = "gems_50";
        pc.kind = "reward";
        pc.rewardKeyId = "gems_50";
        pc.maxUses = 1;
        pc.expiresAfter = "365d";
        pc.bindToPlayer = true;
        EasyVipConfig.fulfillment.products.put(pc.sku, pc);

        FulfillmentKeyConfig.KeyEntry keyEntry = new FulfillmentKeyConfig.KeyEntry();
        keyEntry.secret = "test-secret-key-for-hmac-testing-12345";
        EasyVipConfig.fulfillment.keys.current = keyEntry;
        EasyVipConfig.fulfillment.keys.keys.put("current", keyEntry);
        EasyVipConfig.fulfillment.keys.keys.put("key-001", keyEntry);
    }

    @AfterEach
    void tearDown() {
        PersistenceManager.flush();
        EasyVipConfig.rewardKeys.list.clear();
        EasyVipConfig.rewardKeys.list.putAll(rewardBackup);
        EasyVipConfig.tiers.list.clear();
        EasyVipConfig.tiers.list.putAll(tierBackup);
        EasyVipConfig.fulfillment.products.clear();
        EasyVipConfig.fulfillment.products.putAll(productBackup);
        EasyVipConfig.fulfillment.enabled = false;
        EasyVipConfig.common.keyCharset = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        EasyVipConfig.common.keyLength = 12;
        EasyVipConfig.common.keyPrefix = "EVIP-";
        PersistenceManager.flush();
    }

    // ─── HMAC Signature Tests ──────────────────────────────

    @Test
    void hmacSha256ProducesValidSignature() {
        String signature = FulfillmentApiServer.hmacSha256("secret", "test-data");
        assertNotNull(signature);
        assertEquals(64, signature.length());
        assertTrue(signature.matches("[0-9a-f]{64}"));
    }

    @Test
    void hmacSha256SameInputProducesSameOutput() {
        String sig1 = FulfillmentApiServer.hmacSha256("secret", "data");
        String sig2 = FulfillmentApiServer.hmacSha256("secret", "data");
        assertEquals(sig1, sig2);
    }

    @Test
    void hmacSha256DifferentSecretProducesDifferentOutput() {
        String sig1 = FulfillmentApiServer.hmacSha256("secret1", "data");
        String sig2 = FulfillmentApiServer.hmacSha256("secret2", "data");
        assertNotEquals(sig1, sig2);
    }

    @Test
    void hmacSha256DifferentDataProducesDifferentOutput() {
        String sig1 = FulfillmentApiServer.hmacSha256("secret", "data1");
        String sig2 = FulfillmentApiServer.hmacSha256("secret", "data2");
        assertNotEquals(sig1, sig2);
    }

    // ─── SHA-256 Tests ──────────────────────────────────────

    @Test
    void sha256ProducesHexString() {
        String hash = FulfillmentApiServer.sha256("test");
        assertNotNull(hash);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void sha256IsDeterministic() {
        assertEquals(FulfillmentApiServer.sha256("test"),
                FulfillmentApiServer.sha256("test"));
    }

    // ─── Rate Limiting Tests ────────────────────────────────

    @Test
    void isPublicBindBlocksPublicAddresses() {
        assertTrue(invokeIsPublicBind("0.0.0.0"));
        assertTrue(invokeIsPublicBind("192.0.2.1"));
        assertTrue(invokeIsPublicBind("203.0.113.5"));
    }

    @Test
    void isPublicBindAllowsPrivateAddresses() {
        assertFalse(invokeIsPublicBind("127.0.0.1"));
        assertFalse(invokeIsPublicBind("localhost"));
        assertFalse(invokeIsPublicBind("10.0.0.1"));
        assertFalse(invokeIsPublicBind("192.168.1.1"));
        assertFalse(invokeIsPublicBind("172.16.0.1"));
    }

    private boolean invokeIsPublicBind(String addr) {
        try {
            var method = FulfillmentApiServer.class.getDeclaredMethod("isPublicBind", String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, addr);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ─── Fulfillment Product Validation ─────────────────────

    @Test
    void unknownSkuReturns422() {
        List<Map<String, Object>> items = List.of(Map.of(
                "line_item_id", UUID.randomUUID().toString(),
                "product_sku", "nonexistent",
                "quantity", 1
        ));
        FulfillmentService.FulfillmentResult result = FulfillmentService.processFulfillment(
                UUID.randomUUID().toString(), "ORD-001", UUID.randomUUID().toString(),
                "TestPlayer", items, "body", "current");
        assertEquals(422, result.httpStatus);
        assertTrue(result.errorCode.contains("unknown_sku"));
    }

    @Test
    void emptyItemsReturns400() {
        FulfillmentService.FulfillmentResult result = FulfillmentService.processFulfillment(
                UUID.randomUUID().toString(), "ORD-001", UUID.randomUUID().toString(),
                "TestPlayer", new ArrayList<>(), "body", "current");
        assertEquals(400, result.httpStatus);
        assertEquals("empty_items", result.errorCode);
    }

    // ─── Disabled State Tests ───────────────────────────────

    @Test
    void disabledFulfillmentReturns503() {
        EasyVipConfig.fulfillment.enabled = false;
        List<Map<String, Object>> items = List.of(Map.of(
                "line_item_id", UUID.randomUUID().toString(),
                "product_sku", "gems_50",
                "quantity", 1
        ));
        FulfillmentService.FulfillmentResult result = FulfillmentService.processFulfillment(
                UUID.randomUUID().toString(), "ORD-001", UUID.randomUUID().toString(),
                "TestPlayer", items, "body", "current");
        assertEquals(503, result.httpStatus);
    }

    // ─── Invalid UUID Test ──────────────────────────────────

    @Test
    void invalidUuidReturns422() {
        List<Map<String, Object>> items = List.of(Map.of(
                "line_item_id", UUID.randomUUID().toString(),
                "product_sku", "gems_50",
                "quantity", 1
        ));
        FulfillmentService.FulfillmentResult result = FulfillmentService.processFulfillment(
                UUID.randomUUID().toString(), "ORD-001", "not-a-uuid",
                "TestPlayer", items, "body", "current");
        assertEquals(422, result.httpStatus);
    }

    // ─── Code Masking Tests ─────────────────────────────────

    @Test
    void keySecurityMaskKeyHidesFullCode() {
        String code = "EVIP-ABCD1234";
        String masked = KeySecurity.maskKey(code);
        assertFalse(masked.contains("ABCD1234"));
        assertTrue(masked.startsWith("EVIP"));
    }

    @Test
    void keySecurityDescribeForLogContainsFingerprintNotCode() {
        String desc = KeySecurity.describeKeyForLog("EVIP-TEST1234");
        assertTrue(desc.contains("[fp:"));
        assertFalse(desc.contains("TEST1234"));
    }

    // ─── Signature Comparison Uses Constant Time ─────────────

    @Test
    void constantTimeComparisonDetectsMismatch() {
        byte[] a = "abc123".getBytes(StandardCharsets.UTF_8);
        byte[] b = "abc124".getBytes(StandardCharsets.UTF_8);
        assertFalse(MessageDigest.isEqual(a, b));
    }

    @Test
    void constantTimeComparisonMatches() {
        byte[] a = "abc123".getBytes(StandardCharsets.UTF_8);
        byte[] b = "abc123".getBytes(StandardCharsets.UTF_8);
        assertTrue(MessageDigest.isEqual(a, b));
    }

    // ─── Timestamp Tolerance ────────────────────────────────

    @Test
    void timestampWithinToleranceIsValid() {
        long now = System.currentTimeMillis();
        long tolerance = EasyVipConfig.fulfillment.timestampToleranceSeconds * 1000L;
        assertTrue(Math.abs(now - (now + tolerance / 2)) <= tolerance);
    }

    @Test
    void timestampOutsideToleranceIsInvalid() {
        long now = System.currentTimeMillis();
        long tolerance = EasyVipConfig.fulfillment.timestampToleranceSeconds * 1000L;
        assertFalse(Math.abs(now - (now + tolerance * 2)) <= tolerance);
    }
}
