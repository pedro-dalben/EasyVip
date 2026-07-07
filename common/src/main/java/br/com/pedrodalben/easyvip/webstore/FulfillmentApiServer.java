package br.com.pedrodalben.easyvip.webstore;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class FulfillmentApiServer {

    private static final Gson GSON = new Gson();
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "v1=";

    private static HttpServer server;
    private static ExecutorService executor;
    private static volatile boolean running = false;

    private static final ConcurrentHashMap<String, Long> nonceCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, RateBucket> rateLimitMap = new ConcurrentHashMap<>();

    private static final int MAX_RATE_PER_SECOND = 20;

    private FulfillmentApiServer() {}

    public static synchronized void start() {
        if (running) return;
        FulfillmentConfig cfg = EasyVipConfig.fulfillment;

        if (!cfg.enabled) {
            System.out.println("[EasyVip-Fulfillment] API not enabled in config");
            return;
        }

        if (cfg.requireSql && !br.com.pedrodalben.easyvip.persistence.SqlDatabaseManager.isInitialized()) {
            System.err.println("[EasyVip-Fulfillment] API requires SQL mode but SQL is not initialized. Not starting listener.");
            return;
        }

        String resolvedSecret = cfg.keys.current.resolveSecret();
        if (resolvedSecret == null || resolvedSecret.isBlank()) {
            System.err.println("[EasyVip-Fulfillment] No signing secret configured. "
                    + "Set environment variable or secret in config. Not starting listener.");
            return;
        }

        if (!cfg.allowPublicBind && isPublicBind(cfg.bindAddress)) {
            System.err.println("[EasyVip-Fulfillment] Refusing to bind to public address '"
                    + cfg.bindAddress + "'. Set allow_public_bind=true explicitly to override.");
            return;
        }

        try {
            InetSocketAddress addr = new InetSocketAddress(cfg.bindAddress, cfg.port);
            server = HttpServer.create(addr, 0);
            server.createContext("/api/v1/webstore/fulfillments", new FulfillmentHandler());
            server.createContext("/health", new HealthHandler());
            executor = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "EasyVip-Fulfillment-Worker");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(executor);
            server.start();
            running = true;
            System.out.println("[EasyVip-Fulfillment] API started on " + cfg.bindAddress + ":" + cfg.port);

            Thread cleanup = new Thread(FulfillmentApiServer::nonceCleanupLoop, "EasyVip-Fulfillment-Cleanup");
            cleanup.setDaemon(true);
            cleanup.start();
        } catch (IOException e) {
            System.err.println("[EasyVip-Fulfillment] Failed to start HTTP server: " + e.getMessage());
        }
    }

    public static synchronized void stop() {
        if (!running) return;
        running = false;
        if (server != null) {
            server.stop(2);
            server = null;
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
        System.out.println("[EasyVip-Fulfillment] API stopped");
    }

    public static boolean isRunning() { return running; }

    static void setRunningForTest(boolean value) { running = value; }

    private static boolean isPublicBind(String addr) {
        if (addr == null) return true;
        return !("127.0.0.1".equals(addr) || "localhost".equalsIgnoreCase(addr)
                || addr.startsWith("10.") || addr.startsWith("172.16.")
                || addr.startsWith("192.168.") || addr.startsWith("100."));
    }

    private static void nonceCleanupLoop() {
        while (running) {
            try {
                Thread.sleep(30000);
                long cutoff = System.currentTimeMillis() - (EasyVipConfig.fulfillment.timestampToleranceSeconds * 2000L);
                nonceCache.entrySet().removeIf(e -> e.getValue() < cutoff);
                rateLimitMap.entrySet().removeIf(e -> e.getValue().expiresAt < System.currentTimeMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    static class RateBucket {
        int tokens = MAX_RATE_PER_SECOND;
        long expiresAt = System.currentTimeMillis() + 1000;
    }

    static class FulfillmentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            FulfillmentConfig cfg = EasyVipConfig.fulfillment;

            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "method_not_allowed");
                    return;
                }

                String remoteAddr = exchange.getRemoteAddress().getAddress().getHostAddress();
                if (!checkRateLimit(remoteAddr)) {
                    sendError(exchange, 429, "rate_limit_exceeded");
                    return;
                }

                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                if (contentType == null || !contentType.toLowerCase().contains("application/json")) {
                    sendError(exchange, 400, "invalid_content_type");
                    return;
                }

                int contentLength = 0;
                String clHeader = exchange.getRequestHeaders().getFirst("Content-Length");
                if (clHeader != null) {
                    contentLength = Integer.parseInt(clHeader);
                }
                if (contentLength > cfg.maxRequestBytes) {
                    sendError(exchange, 413, "payload_too_large");
                    return;
                }

                String keyId = exchange.getRequestHeaders().getFirst("X-EasyVip-Key-Id");
                String timestampStr = exchange.getRequestHeaders().getFirst("X-EasyVip-Timestamp");
                String nonce = exchange.getRequestHeaders().getFirst("X-EasyVip-Nonce");
                String signatureHeader = exchange.getRequestHeaders().getFirst("X-EasyVip-Signature");

                if (keyId == null || timestampStr == null || nonce == null || signatureHeader == null) {
                    sendError(exchange, 401, "missing_auth_headers");
                    return;
                }

                long timestamp;
                try {
                    timestamp = Long.parseLong(timestampStr);
                } catch (NumberFormatException e) {
                    sendError(exchange, 401, "invalid_timestamp");
                    return;
                }

                long now = System.currentTimeMillis();
                long tolerance = cfg.timestampToleranceSeconds * 1000L;
                if (Math.abs(now - timestamp) > tolerance) {
                    sendError(exchange, 401, "timestamp_out_of_range");
                    return;
                }

                if (nonce.length() < 8 || nonce.length() > 128 || !nonce.matches("[a-zA-Z0-9_-]+")) {
                    sendError(exchange, 401, "invalid_nonce_format");
                    return;
                }

                if (nonceCache.putIfAbsent(nonce, now) != null) {
                    sendError(exchange, 401, "nonce_replay");
                    return;
                }
                if (nonceCache.size() > cfg.maxNonceCacheSize) {
                    nonceCache.clear();
                }

                FulfillmentKeyConfig.KeyEntry keyEntry = cfg.keys.keys.get(keyId);
                if (keyEntry == null && "current".equals(keyId)) {
                    keyEntry = cfg.keys.current;
                }
                if (keyEntry == null) {
                    sendError(exchange, 401, "unknown_key_id");
                    return;
                }

                String secret = keyEntry.resolveSecret();
                if (secret == null || secret.isBlank()) {
                    sendError(exchange, 401, "key_not_configured");
                    return;
                }

                InputStream is = exchange.getRequestBody();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int read;
                int total = 0;
                while ((read = is.read(buf)) != -1) {
                    total += read;
                    if (total > cfg.maxRequestBytes) {
                        sendError(exchange, 413, "payload_too_large");
                        return;
                    }
                    bos.write(buf, 0, read);
                }
                String rawBody = bos.toString(StandardCharsets.UTF_8);

                String path = "/api/v1/webstore/fulfillments";
                String canonical = exchange.getRequestMethod().toUpperCase() + "\n"
                        + path + "\n"
                        + timestampStr + "\n"
                        + nonce + "\n"
                        + sha256(rawBody);

                String expectedSignature = SIGNATURE_PREFIX + hmacSha256(secret, canonical);

                if (!signatureHeader.startsWith(SIGNATURE_PREFIX)) {
                    sendError(exchange, 401, "invalid_signature_format");
                    return;
                }

                if (!MessageDigest.isEqual(
                        expectedSignature.getBytes(StandardCharsets.UTF_8),
                        signatureHeader.getBytes(StandardCharsets.UTF_8))) {
                    sendError(exchange, 401, "invalid_signature");
                    return;
                }

                Map<String, Object> payload;
                try {
                    Type type = new TypeToken<Map<String, Object>>(){}.getType();
                    payload = GSON.fromJson(rawBody, type);
                } catch (Exception e) {
                    sendError(exchange, 400, "invalid_json");
                    return;
                }

                if (payload == null) {
                    sendError(exchange, 400, "empty_payload");
                    return;
                }

                String fulfillmentId = payload.get("fulfillment_id") != null
                        ? payload.get("fulfillment_id").toString() : "";
                String orderId = payload.get("order_id") != null
                        ? payload.get("order_id").toString() : "";
                String mcUuid = payload.get("minecraft_uuid") != null
                        ? payload.get("minecraft_uuid").toString() : "";
                String mcUsername = payload.get("minecraft_username") != null
                        ? payload.get("minecraft_username").toString() : "";

                if (fulfillmentId.isBlank() || mcUuid.isBlank()) {
                    sendError(exchange, 400, "missing_required_fields");
                    return;
                }

                Object itemsObj = payload.get("items");
                List<Map<String, Object>> items = new ArrayList<>();
                if (itemsObj instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> m = (Map<String, Object>) o;
                            items.add(m);
                        }
                    }
                }

                for (Map<String, Object> item : items) {
                    if (item.containsKey("command") || item.containsKey("actions")
                            || item.containsKey("nbt") || item.containsKey("stack_snbt")
                            || item.containsKey("duration") || item.containsKey("tier")
                            || item.containsKey("max_uses") || item.containsKey("price")
                            || item.containsKey("authorization")) {
                        sendError(exchange, 422, "restricted_field_in_item");
                        return;
                    }
                }

                FulfillmentService.FulfillmentResult result = FulfillmentService.processFulfillment(
                        fulfillmentId, orderId, mcUuid, mcUsername, items, rawBody, keyId);

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", result.status);
                response.put("fulfillment_id", result.fulfillmentId);

                if (result.items != null && !result.items.isEmpty()) {
                    List<Map<String, Object>> respItems = new ArrayList<>();
                    for (FulfillmentService.ItemResult ir : result.items) {
                        Map<String, Object> ri = new LinkedHashMap<>();
                        ri.put("line_item_id", ir.lineItemId);
                        ri.put("product_sku", ir.productSku);
                        ri.put("activation_key", ir.activationKey);
                        ri.put("key_fingerprint", ir.keyFingerprint);
                        respItems.add(ri);
                    }
                    response.put("items", respItems);
                } else {
                    response.put("items", new ArrayList<>());
                }

                String responseJson = GSON.toJson(response);
                byte[] responseBytes = responseJson.getBytes(StandardCharsets.UTF_8);

                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(result.httpStatus, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }

                logFulfillment(fulfillmentId, orderId, mcUuid, result.httpStatus,
                        result.errorCode != null ? result.errorCode : result.status, keyId);
            } catch (Exception e) {
                System.err.println("[EasyVip-Fulfillment] Unexpected error: " + e.getMessage());
                try {
                    sendError(exchange, 500, "internal_error");
                } catch (IOException ignored) {}
            }
        }
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            FulfillmentConfig cfg = EasyVipConfig.fulfillment;
            Map<String, Object> health = new LinkedHashMap<>();
            if (!cfg.enabled || !running) {
                health.put("status", "unavailable");
                health.put("reason", cfg.enabled ? "server_not_running" : "api_disabled");
                String json = GSON.toJson(health);
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }
            health.put("status", "ok");
            String json = GSON.toJson(health);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static boolean checkRateLimit(String remoteAddr) {
        RateBucket bucket = rateLimitMap.computeIfAbsent(remoteAddr, k -> new RateBucket());
        synchronized (bucket) {
            long now = System.currentTimeMillis();
            if (now >= bucket.expiresAt) {
                bucket.tokens = MAX_RATE_PER_SECOND;
                bucket.expiresAt = now + 1000;
            }
            if (bucket.tokens <= 0) return false;
            bucket.tokens--;
            return true;
        }
    }

    private static void sendError(HttpExchange exchange, int code, String error) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        if (code == 401 || code == 403) {
            body.put("error", "authentication_failed");
        }
        String json = GSON.toJson(body);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] result = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : result) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
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

    private static void logFulfillment(String fulfillmentId, String orderId, String mcUuid,
                                       int httpStatus, String result, String keyId) {
        String msg = "fulfillment_id=" + fulfillmentId
                + " order_id=" + (orderId != null ? orderId.substring(0, Math.min(8, orderId.length())) + "..." : "null")
                + " uuid=" + mcUuid
                + " http=" + httpStatus
                + " result=" + result
                + " key_id=" + keyId;
        if (EasyVipConfig.common.debug) {
            System.out.println("[EasyVip-Fulfillment] " + msg);
        }
    }
}
