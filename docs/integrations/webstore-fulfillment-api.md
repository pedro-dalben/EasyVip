# Webstore Fulfillment API

**Version:** 1.0.0  
**Protocol:** HMAC-SHA256 authenticated HTTP/JSON  
**Transport:** Loopback (`127.0.0.1`) or WireGuard private network only  
**Requires:** SQL mode enabled  

---

## 1. Architecture

```
 ┌──────────────────┐     ┌───────────────────┐     ┌──────────────────┐
 │  Payment Gateway │     │  Rails Webstore   │     │  Minecraft Server│
 │  (Stripe/etc)    │────▶│  (job async)      │────▶│  (EasyVip mod)   │
 │                  │     │                   │     │                  │
 │  webhook →       │     │  POST /api/v1/    │     │  FulfillmentAPI  │
 │  confirm payment │     │  webstore/        │     │  → validate HMAC │
 │                  │     │  fulfillments     │     │  → idempotency   │
 │                  │     │                   │     │  → generate keys │
 │                  │     │◀── JSON response ─│     │  → SQL ledger    │
 │                  │     │                   │     │                  │
 └──────────────────┘     └───────────────────┘     └──────────────────┘
```

1. Payment gateway confirms payment → Rails webhook endpoint
2. Rails validates webhook, marks order paid, creates fulfillment record
3. Rails job (async, idempotent) calls EasyVip fulfillment API
4. EasyVip validates HMAC signature, timestamp, nonce, product SKU, UUID
5. EasyVip generates activation keys bound to player UUID
6. EasyVip returns keys in JSON response
7. Rails stores keys encrypted, delivers to buyer via email/dashboard
8. Idempotency: same `fulfillment_id` → same response, no duplicate keys

**Key design:** The webhook from the payment gateway never depends on Minecraft availability. The Rails job retries safely. The EasyVip API is private, authenticated, and idempotent.

---

## 2. Configuration

### 2.1 EasyVip `webstore.toml`

```toml
# Sync to webstore (existing — unchanged)
enabled = true
api_url = "http://webstore-rails:3000"
api_token = "your-api-token"
sync_on_register = true
sync_on_login = true
sync_on_join = true
sync_on_nick_change = true
retry_max_attempts = 3
retry_delay_seconds = 5

[fulfillment]
enabled = false                # Set to true to enable the API
bind_address = "127.0.0.1"     # Loopback or WireGuard IP only
port = 28765
max_request_bytes = 16384
timestamp_tolerance_seconds = 60
request_timeout_seconds = 10
allow_public_bind = false      # Set true only if you understand the risk
require_sql = true             # Must be true — requires SQL mode
max_nonce_cache_size = 20000

[fulfillment.keys.current]
secret_env = "EASYVIP_WEBSTORE_SIGNING_SECRET"
# Or set inline (not recommended):
# secret = "your-shared-secret-here"

[fulfillment.keys.key-001]
secret_env = "EASYVIP_WEBSTORE_SIGNING_SECRET_V2"

# Product catalog — what the webstore can buy
[products.gems_50]
kind = "reward"
reward_key_id = "gems_50"
max_uses = 1
expires_after = "365d"
bind_to_player = true

[products.vip_ultraball_30d]
kind = "vip"
tier_id = "ultraball"
duration = "30d"
max_uses = 1
expires_after = "365d"
bind_to_player = true
```

### 2.2 Environment Variables

```bash
# Required: HMAC signing secret shared between Rails and Minecraft
export EASYVIP_WEBSTORE_SIGNING_SECRET="your-64-char-random-hex-secret"
```

### 2.3 SQL Mode

The fulfillment API requires SQL mode. Configure in `integrations.toml`:

```toml
[integrations]
sql_enabled = true
sql_url = "jdbc:mysql://localhost:3306/easyvip"
sql_username = "easyvip"
sql_password = "your-db-password"
```

### 2.4 Binding Recommendations

| Scenario | bind_address |
|----------|-------------|
| Same machine | `127.0.0.1` |
| Same WireGuard network | `10.X.X.X` (WireGuard IP) |
| Different Docker containers | Docker service name or bridge IP |
| **Never** | `0.0.0.0` or public IP |

The server refuses to start on a public address unless `allow_public_bind = true` is explicitly set.

---

## 3. API Specification

### Endpoint

```
POST /api/v1/webstore/fulfillments
```

### Headers

| Header | Required | Description |
|--------|:---:|-------------|
| `Content-Type: application/json` | Yes | Must be `application/json` |
| `X-EasyVip-Key-Id` | Yes | API key identifier for secret rotation |
| `X-EasyVip-Timestamp` | Yes | Unix timestamp (milliseconds) |
| `X-EasyVip-Nonce` | Yes | Random unique value, 8-128 chars, `[a-zA-Z0-9_-]+` |
| `X-EasyVip-Signature` | Yes | HMAC-SHA256 signature, format: `v1=<hex>` |

### Canonical Request String

For HMAC signing, construct this exact string:

```
METHOD + "\n" +
PATH + "\n" +
TIMESTAMP + "\n" +
NONCE + "\n" +
SHA256(RAW_REQUEST_BODY)
```

Example:
```
POST
/api/v1/webstore/fulfillments
1717891200000
aB3xK9mW2qR7tY5v
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
```

### Request Body

```json
{
  "fulfillment_id": "8aef8ae3-6d26-4d78-a3e0-9b4a73690913",
  "order_id": "ORD-2026-000123",
  "minecraft_uuid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "minecraft_username": "PedroDalben",
  "items": [
    {
      "line_item_id": "ffc8e543-05b7-45ba-ae6a-b0eeb07b91cc",
      "product_sku": "gems_50",
      "quantity": 1
    }
  ]
}
```

**Restricted fields** (not accepted):
- `command`, `actions`, `action_json`, `nbt`, `stack_snbt`
- `duration`, `tier`, `max_uses`, `price`, `authorization`

### Responses

#### 201 Created (new fulfillment)

```json
{
  "status": "created",
  "fulfillment_id": "8aef8ae3-6d26-4d78-a3e0-9b4a73690913",
  "items": [
    {
      "line_item_id": "ffc8e543-05b7-45ba-ae6a-b0eeb07b91cc",
      "product_sku": "gems_50",
      "activation_key": "EVIP-ABCDEFGHIJKL",
      "key_fingerprint": "sha256:a1b2c3d4..."
    }
  ]
}
```

#### 200 OK (idempotent retry)

```json
{
  "status": "already_created",
  "fulfillment_id": "8aef8ae3-6d26-4d78-a3e0-9b4a73690913",
  "items": []
}
```

If the original response included keys, the same keys are returned. Store them on first success.

#### Error Responses

| Status | Error Code | Meaning |
|--------|-----------|---------|
| 400 | `invalid_json` | JSON parse error |
| 400 | `empty_items` | Items array empty |
| 400 | `missing_required_fields` | fulfillment_id or minecraft_uuid missing |
| 401 | `authentication_failed` | Generic auth error (signature/timestamp/nonce) |
| 409 | `idempotency_conflict` | Same fulfillment_id, different payload |
| 413 | `payload_too_large` | Body exceeds max_request_bytes |
| 422 | `unknown_sku:X` | Product SKU not found in config |
| 422 | `unknown_tier:X` | Tier not found in tiers config |
| 422 | `unknown_reward_key:X` | Reward key not found in config |
| 422 | `invalid_uuid` | minecraft_uuid is not a valid UUID |
| 422 | `restricted_field_in_item` | Item contains forbidden field |
| 429 | `rate_limit_exceeded` | Too many requests |
| 500 | `internal_error` | Unexpected server error |
| 503 | `fulfillment_disabled` | API not enabled in config |
| 503 | `sql_unavailable` | SQL mode not available |

All error responses have format:
```json
{
  "error": "authentication_failed"
}
```

Authentication errors (401) always return the same generic message.

---

## 4. Rails Integration Example

### 4.1 Signing the Request (Ruby)

```ruby
require 'openssl'
require 'json'
require 'securerandom'

class EasyVipFulfillmentClient
  def initialize(base_url:, key_id:, secret:)
    @base_url = base_url
    @key_id = key_id
    @secret = secret
  end

  def fulfill(payload)
    path = "/api/v1/webstore/fulfillments"
    body = payload.to_json
    timestamp = (Time.now.to_f * 1000).to_i.to_s
    nonce = SecureRandom.alphanumeric(32)

    canonical = [
      "POST",
      path,
      timestamp,
      nonce,
      Digest::SHA256.hexdigest(body)
    ].join("\n")

    signature = "v1=" + OpenSSL::HMAC.hexdigest("SHA256", @secret, canonical)

    response = Net::HTTP.start(@base_url.host, @base_url.port) do |http|
      http.request_timeout = 15
      http.post(path, body, {
        "Content-Type" => "application/json",
        "X-EasyVip-Key-Id" => @key_id,
        "X-EasyVip-Timestamp" => timestamp,
        "X-EasyVip-Nonce" => nonce,
        "X-EasyVip-Signature" => signature
      })
    end

    case response.code.to_i
    when 200, 201
      JSON.parse(response.body)
    when 409
      raise "Idempotency conflict: fulfillment already exists with different data"
    when 401
      raise "Authentication failed — check secret and clock sync"
    else
      raise "Fulfillment failed: HTTP #{response.code} — #{response.body}"
    end
  end
end
```

### 4.2 Idempotent Rails Job

```ruby
class FulfillOrderJob < ApplicationJob
  queue_as :fulfillments

  retry_on StandardError, wait: :exponentially_longer, attempts: 5

  def perform(order_id)
    order = Order.find(order_id)
    return unless order.paid?

    order.items.each do |item|
      fulfillment = Fulfillment.find_or_initialize_by(
        fulfillment_id: item.fulfillment_id
      )

      next if fulfillment.completed?

      result = easyvip_client.fulfill({
        fulfillment_id: item.fulfillment_id,
        order_id: order.order_number,
        minecraft_uuid: order.player.minecraft_uuid,
        minecraft_username: order.player.username,
        items: [
          {
            line_item_id: item.line_item_id,
            product_sku: item.product.sku,
            quantity: item.quantity
          }
        ]
      })

      if result["status"] == "created"
        result["items"].each do |key_item|
          fulfillment.activation_keys.create!(
            line_item_id: key_item["line_item_id"],
            key_code_encrypted: encrypt(key_item["activation_key"]),
            key_fingerprint: key_item["key_fingerprint"]
          )
        end
        fulfillment.update!(status: "completed", completed_at: Time.current)
      end
    end
  end

  private

  def easyvip_client
    @easyvip_client ||= EasyVipFulfillmentClient.new(
      base_url: URI(ENV.fetch("EASYVIP_FULFILLMENT_URL")),
      key_id: "current",
      secret: ENV.fetch("EASYVIP_WEBSTORE_SIGNING_SECRET")
    )
  end

  def encrypt(data)
    # Use your application's encryption (e.g., Rails encrypted credentials
    # or a dedicated vault). Never store activation keys in plaintext.
    EncryptionService.encrypt(data)
  end
end
```

### 4.3 Retry Strategy

- Rails job retries with exponential backoff (1s, 2s, 4s, 8s, 16s)
- Same `fulfillment_id` sent on every retry → idempotent
- On `200 already_created`: job completes successfully
- On `409 idempotency_conflict`: log error, alert ops (indicates bug or tampering)
- On `503`: retry with backoff (server may be restarting)
- On network timeout: retry normally (idempotent)

---

## 5. Secret Rotation

### To add a new key:

1. Add new key to `webstore.toml`:
```toml
[fulfillment.keys.key-002]
secret_env = "EASYVIP_WEBSTORE_SIGNING_SECRET_V2"
```

2. Set env var on both Minecraft and Rails servers.

3. Deploy Minecraft first (now accepts both `key-001` and `key-002`).

4. Deploy Rails with `key_id: "key-002"`.

5. After confirming everything works, remove old key:
```toml
[fulfillment.keys.current]
secret_env = "EASYVIP_WEBSTORE_SIGNING_SECRET_V2"
```

6. Remove old env var, restart.

---

## 6. Disabling the API

### In an incident:

Set `enabled = false` in `[fulfillment]` section and run `/easyvip reload` or restart the server.

The API listener stops accepting connections. Health endpoint returns 503.

### Rollback:

Set `enabled = true`, reload. Keys generated while disabled are NOT lost (they were created before disabling).

---

## 7. Deploy Checklist

- [ ] SQL mode enabled (`integrations.toml: sql_enabled = true`)
- [ ] MySQL database accessible from Minecraft server
- [ ] `bind_address` set to `127.0.0.1` (same machine) or WireGuard IP
- [ ] `allow_public_bind = false` (default)
- [ ] `EASYVIP_WEBSTORE_SIGNING_SECRET` env var set on both servers
- [ ] Product SKUs defined in `webstore.toml` matching webstore catalog
- [ ] Reward keys / tiers referenced by SKUs exist in config
- [ ] Firewall allows port `28765` only from webstore IP
- [ ] Port `28765` NOT exposed to internet
- [ ] `./gradlew clean buildAll` passes
- [ ] Test fulfillment with `curl` from webstore machine:
```bash
TIMESTAMP=$(date +%s%3N)
NONCE=$(openssl rand -hex 16)
BODY='{"fulfillment_id":"test-'$(uuidgen)'","order_id":"TEST","minecraft_uuid":"'$(uuidgen)'","minecraft_username":"Test","items":[{"line_item_id":"test-'$(uuidgen)'","product_sku":"gems_50","quantity":1}]}'
CANONICAL="POST\n/api/v1/webstore/fulfillments\n$TIMESTAMP\n$NONCE\n$(echo -n "$BODY" | sha256sum | cut -d' ' -f1)"
SIGNATURE="v1=$(echo -ne "$CANONICAL" | openssl dgst -sha256 -hmac "$EASYVIP_WEBSTORE_SIGNING_SECRET" | cut -d' ' -f2)"
curl -X POST http://127.0.0.1:28765/api/v1/webstore/fulfillments \
  -H "Content-Type: application/json" \
  -H "X-EasyVip-Key-Id: current" \
  -H "X-EasyVip-Timestamp: $TIMESTAMP" \
  -H "X-EasyVip-Nonce: $NONCE" \
  -H "X-EasyVip-Signature: $SIGNATURE" \
  -d "$BODY"
```

---

## 8. Observability

- **Audit logs:** All fulfillments recorded in EasyVip SQL audit table (masked codes)
- **Debug mode:** Set `debug = true` in `common.toml` for verbose console output
- **Health endpoint:** `GET /health` returns `{"status": "ok"}` or `{"status": "unavailable"}`
- **Fulfillment ledger:** `easyvip_fulfillments` and `easyvip_fulfillment_items` tables in MySQL

---

## 9. Security Notes

- Activation keys are NEVER logged in full (masked: `EVIP-XXXX-••••-••••`)
- HMAC secret NEVER appears in logs, errors, or responses
- Signature comparison uses `MessageDigest.isEqual()` (constant-time)
- Nonce replay protection via in-memory cache (cleaned every 30s)
- Rate limit: 20 requests/second per IP
- Public bind blocked by default (`allow_public_bind = false`)
- If public bind is required, use reverse proxy with TLS and mTLS
- Do NOT use RCON for fulfillment — this API replaces any RCON-based approach

---

## 10. Troubleshooting

| Symptom | Check |
|---------|-------|
| `503 sql_unavailable` | SQL not enabled in `integrations.toml` or DB unreachable |
| `401 authentication_failed` | Clock sync between servers; correct secret; valid key_id |
| `422 unknown_sku:X` | SKU defined in `webstore.toml`? Reward key/tier exists? |
| API not listening | `enabled = true`? SQL initialized? Port not in use? |
| Server won't start | `allow_public_bind = false` but bind_address is public? |
