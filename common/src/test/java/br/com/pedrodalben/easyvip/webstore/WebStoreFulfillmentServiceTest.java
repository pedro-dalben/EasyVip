package br.com.pedrodalben.easyvip.webstore;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.net.ssl.SSLSession;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WebStoreFulfillmentServiceTest {

    private Map<String, FulfillmentProductConfig> productsBackup;
    private String originalInlineSecret;
    private String originalSecretEnv;
    private String originalToken;
    private String originalTokenEnv;
    private boolean originalSqlEnabled;

    @BeforeEach
    void setUp() {
        productsBackup = new LinkedHashMap<>(EasyVipConfig.fulfillment.products);
        originalInlineSecret = EasyVipConfig.fulfillment.keys.current != null ? EasyVipConfig.fulfillment.keys.current.secret : null;
        originalSecretEnv = EasyVipConfig.fulfillment.secretEnv;
        originalToken = EasyVipConfig.fulfillment.token;
        originalTokenEnv = EasyVipConfig.fulfillment.tokenEnv;
        originalSqlEnabled = EasyVipConfig.integrations.sqlEnabled;

        EasyVipConfig.fulfillment.products.clear();
        EasyVipConfig.fulfillment.enabled = true;
        EasyVipConfig.fulfillment.serverId = "allthemons-01";
        EasyVipConfig.fulfillment.keyId = "current";
        EasyVipConfig.fulfillment.secretEnv = "EASYVIP_FULFILLMENT_SECRET";
        EasyVipConfig.fulfillment.keys.current.secret = "inline-secret";
        EasyVipConfig.fulfillment.keys.keys.put("current", EasyVipConfig.fulfillment.keys.current);
        EasyVipConfig.fulfillment.token = "token";
        EasyVipConfig.fulfillment.tokenEnv = "EASYVIP_FULFILLMENT_TOKEN";
        EasyVipConfig.integrations.sqlEnabled = false;
    }

    @AfterEach
    void tearDown() {
        EasyVipConfig.fulfillment.products.clear();
        EasyVipConfig.fulfillment.products.putAll(productsBackup);
        if (EasyVipConfig.fulfillment.keys.current != null) {
            EasyVipConfig.fulfillment.keys.current.secret = originalInlineSecret;
        }
        EasyVipConfig.fulfillment.secretEnv = originalSecretEnv;
        EasyVipConfig.fulfillment.token = originalToken;
        EasyVipConfig.fulfillment.tokenEnv = originalTokenEnv;
        EasyVipConfig.integrations.sqlEnabled = originalSqlEnabled;
        WebStoreFulfillmentService.stop();
    }

    @Test
    void hmacSha256IsDeterministic() {
        String sig1 = invokeString("hmacSha256", new Class<?>[]{String.class, String.class}, "secret", "data");
        String sig2 = invokeString("hmacSha256", new Class<?>[]{String.class, String.class}, "secret", "data");
        assertEquals(sig1, sig2);
        assertTrue(sig1.matches("[0-9a-f]{64}"));
    }

    @Test
    void validateResponseSignatureRejectsTampering() {
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        long timestamp = System.currentTimeMillis() / 1000L;
        String nonce = "nonce-123";
        String secret = "super-secret";
        String canonical = timestamp + "\n" + nonce + "\n" + 200 + "\n" + invokeString("sha256", new Class<?>[]{byte[].class}, body);
        String signature = "v1=" + invokeString("hmacSha256", new Class<?>[]{String.class, String.class}, secret, canonical);

        TestResponse response = new TestResponse(200, body, Map.of(
                "X-EasyVip-Response-Timestamp", List.of(Long.toString(timestamp)),
                "X-EasyVip-Response-Signature", List.of(signature)
        ));
        boolean valid = invokeBoolean("validateResponseSignature",
                new Class<?>[]{HttpResponse.class, byte[].class, String.class, String.class},
                response, body, nonce, secret);
        assertTrue(valid);

        byte[] tamperedBody = "{\"ok\":false}".getBytes(StandardCharsets.UTF_8);
        boolean invalid = invokeBoolean("validateResponseSignature",
                new Class<?>[]{HttpResponse.class, byte[].class, String.class, String.class},
                response, tamperedBody, nonce, secret);
        assertFalse(invalid);
    }

    @Test
    void parseClaimResponseRejectsUnexpectedFields() {
        String json = """
                {"fulfillments":[{"fulfillment_id":"f","order_id":"o","minecraft_uuid":"u","minecraft_username":"n","items":[{"line_item_id":"l","product_sku":"gems_50","quantity":1}],"extra":true}]}
                """;
        assertThrows(Exception.class, () -> invoke("parseClaimResponse", new Class<?>[]{byte[].class}, json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void validateProductRejectsMissingDefinitions() {
        assertEquals("unknown_sku", invokeString("validateProduct", new Class<?>[]{FulfillmentProductConfig.class}, new Object[]{null}));

        FulfillmentProductConfig reward = new FulfillmentProductConfig();
        reward.sku = "reward";
        reward.type = "reward";
        reward.rewardKeyId = "missing";
        assertEquals("unknown_reward_key", invokeString("validateProduct", new Class<?>[]{FulfillmentProductConfig.class}, reward));

        FulfillmentProductConfig vip = new FulfillmentProductConfig();
        vip.sku = "vip";
        vip.type = "vip";
        vip.tierId = "missing";
        vip.duration = "30d";
        assertEquals("unknown_tier", invokeString("validateProduct", new Class<?>[]{FulfillmentProductConfig.class}, vip));
    }

    @Test
    void statusSummaryShowsUnavailableWithoutSql() {
        String summary = WebStoreFulfillmentService.statusSummary();
        assertTrue(summary.contains("sql=unavailable"));
    }

    @Test
    void startDoesNotRunWithoutSql() {
        WebStoreFulfillmentService.start(Path.of("build/test-webstore"));
        assertFalse(WebStoreFulfillmentService.isRunning());
        assertFalse(WebStoreFulfillmentService.isAvailable());
        assertTrue(WebStoreFulfillmentService.statusSummary().contains("sql=unavailable"));
    }

    private static Object invoke(String method, Class<?>[] types, Object... args) throws Exception {
        Method m = WebStoreFulfillmentService.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static String invokeString(String method, Class<?>[] types, Object... args) {
        try {
            return (String) invoke(method, types, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean invokeBoolean(String method, Class<?>[] types, Object... args) {
        try {
            return (boolean) invoke(method, types, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final class TestResponse implements HttpResponse<byte[]> {
        private final int statusCode;
        private final byte[] body;
        private final HttpHeaders headers;

        private TestResponse(int statusCode, byte[] body, Map<String, List<String>> headersMap) {
            this.statusCode = statusCode;
            this.body = body;
            this.headers = HttpHeaders.of(headersMap, (a, b) -> true);
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public HttpRequest request() {
            return HttpRequest.newBuilder(URI.create("http://localhost")).build();
        }

        @Override
        public Optional<HttpResponse<byte[]>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public byte[] body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("http://localhost");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
