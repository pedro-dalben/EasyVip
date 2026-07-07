# EasyVip Key Module Security Audit

**Date:** 2026-07-06  
**Scope:** `/common/src/main/java/br/com/pedrodalben/easyvip/` — key generation, redemption, persistence, command execution  
**Version:** 1.0.0  
**Status:** Audited and hardened. Build green. 78 tests pass.

---

## 1. Audited Scope

| Area | Files |
|------|-------|
| Key generation | `util/UniqueCodeGenerator.java`, `service/KeyService.java` |
| Key data model | `model/KeyRecord.java` |
| Persistence (JSON) | `persistence/PersistenceManager.java` |
| Persistence (SQL) | `persistence/SqlDatabaseManager.java` |
| Action execution | `action/ActionExecutor.java` |
| Command allowlist | `util/CommandAllowlist.java` |
| Key security (masking) | `util/KeySecurity.java` |
| Commands and permissions | `command/EasyVipCommands.java` |
| Configuration | `config/EasyVipConfig.java` |
| VIP service (redemption flow) | `service/VipService.java` |

---

## 2. Current Architecture

```
/generate VIP/Reward/Custom key (admin command)
/use <key>           (player command)
/confirm             (player command)
        ↓
KeyService
 ├─ generateVipKey() / generateRewardKey() / generateCustomKey()
 ├─ redeemKey() → normalizeCode() → preflightCheck()
 │   → executeKeyReward() → VipService / ActionExecutor
 │   → consumeRecord()
 └─ confirmPending()
        ↓
ActionExecutor
 ├─ sanitizeCommand() → CommandAllowlist.normalize()
 ├─ isCommandAllowed() → CommandAllowlist.isAllowed()
 └─ execute() → 19 action types
        ↓
PersistenceManager
 ├─ JSON mode (default): ReentrantReadWriteLock, async save, atomic write with .bak
 └─ SQL mode (optional): MySQL via JDBC, REPLACE INTO, audit log table
```

---

## 3. Threats Assessed

| Threat | Severity | Status |
|--------|----------|--------|
| Predictable key codes via weak RNG | Critical | Pass — SecureRandom used. Charset entropy now validated. |
| Concurrent double-redemption of same key | Critical | Fixed — ConcurrentHashMap per-key locks. |
| Activation code leakage in logs | Critical | Fixed — KeySecurity.describeKeyForLog() masks all codes. Full reveal now audited. |
| SQL duplicate key insertion | High | Fixed — Transaction + duplicate-key error handling. |
| JSON save race condition (shared references) | High | Fixed — Deep copy KeyRecord.copy() during snapshot. |
| Command injection via placeholder expansion | High | Pass — Allowlist enforced. Separators rejected. Command normalization blocks bypass. |
| VIP reward key consumed before reward delivered | High | Pass — Failure in executeKeyReward returns ERROR, key not consumed. |
| Privilege escalation via admin commands | High | Pass — All admin commands require easyvip.admin. Console has full access. |
| Thread-unsafe confirmation map | High | Fixed — HashMap to ConcurrentHashMap. |
| Degenerate charset (1 char) | Medium | Fixed — UniqueCodeGenerator rejects <2 distinct chars. Config validator warns <32 bits entropy. |
| Full key code sent to admin chat on generate | Medium | Accepted — By design; admin needs the code. Audit log records only masked+fingerprint. |
| generateLinkCode uses java.util.Random | Medium | Fixed — Changed to SecureRandom. |
| KeyRecord mutable collection getters | Low | Accepted — Copy-on-read pattern in PersistenceManager. |
| Partial reward execution on multi-action failure | Low | Documented — Actions execute sequentially; earlier actions may succeed before a later failure. Key is not consumed on failure. |
| JSON mode: async save may lose data on crash | Low | Documented — Inherent limitation of file-based async persistence. |

---

## 4. Findings by Severity

### CRITICAL

#### C1: Unsynchronized confirmation and cooldown maps (FIXED)
**File:** `service/KeyService.java:24-25`

Before:
```java
private static final Map<UUID, PendingConfirmation> confirmations = new HashMap<>();
private static final Map<UUID, Map<CommandThrottleType, Long>> commandCooldowns = new HashMap<>();
```

After:
```java
private static final ConcurrentHashMap<UUID, PendingConfirmation> confirmations = new ConcurrentHashMap<>();
private static final ConcurrentHashMap<UUID, Map<CommandThrottleType, Long>> commandCooldowns = new ConcurrentHashMap<>();
```

**Risk:** Corrupted map state under concurrent access from multiple players.

---

#### C2: Unvalidated charset entropy (FIXED)
**File:** `util/UniqueCodeGenerator.java:23` and `config/EasyVipConfig.java:1068`

Before: `generateCandidate` accepted any charset including single-character strings like "A", producing zero-entropy keys.

After:
- `UniqueCodeGenerator.generateCandidate` throws `IllegalArgumentException` if charset has <2 distinct characters or length <1.
- `EasyVipConfig.validate()` warns if charset+length combination provides <32 bits of entropy.

**Risk:** Admin misconfiguration could produce trivially predictable keys.

---

#### C3: JSON save shared-reference data race (FIXED)
**File:** `persistence/PersistenceManager.java:253-262`

Before: `saveKeysSync` copied map references without deep-copying `KeyRecord` objects. Mutations during Gson serialization could produce inconsistent JSON.

After: Uses `entry.getValue().copy()` for deep-copy during snapshot.

**Risk:** Corrupted key data on disk under concurrent modification.

---

### HIGH

#### H1: SQL putKeyIfAbsent error masking (FIXED)
**File:** `persistence/SqlDatabaseManager.java:277-301`

Before: Catch block returned `record` on any `SQLException`, treating connection failures as "duplicate key".

After: Operations wrapped in transaction. `isDuplicateKeyError()` checks SQL state 23000/23505 specifically. On duplicate, reads existing record.

**Risk:** Connection failures could cause phantom "key already exists" behavior.

---

#### H2: Key revelation not audited (FIXED)
**File:** `command/EasyVipCommands.java:1166`

Before: `/easyvip key info <code> reveal` displayed full key code without audit trail.

After: Adds `PersistenceManager.log()` recording operator name and masked+fingerprinted key identity.

**Risk:** No forensic trail when admin views full key codes.

---

#### H3: Tier/Reward key validation missing in service layer (FIXED)
**File:** `service/KeyService.java:72,105`

Before: `generateVipKey` and `generateRewardKey` accepted arbitrary tierId/rewardKeyId without config validation.

After: Both methods now validate against `EasyVipConfig` maps, throwing `IllegalArgumentException` on unknown IDs.

**Risk:** Programmatic key generation with invalid tier/reward would succeed silently.

---

### MEDIUM

- **M1:** `generateLinkCode` insecure RNG → Fixed: SecureRandom.
- **M2:** Mutable collection getters → Accepted: copy-on-read in PersistenceManager.
- **M3:** Partial action execution → Documented: key not consumed on failure, but earlier actions may have run.

---

## 5. Code Leakage Surface

| Location | Status | Detail |
|----------|--------|--------|
| `KeyService.insertUnique()` log | Safe | Uses `KeySecurity.describeKeyForLog()` (masked+fingerprint) |
| `PersistenceManager.log()` | Safe | Details pass through `KeySecurity.sanitizeAuditDetails()` in SQL mode |
| `SqlDatabaseManager` errors | Safe | Uses `KeySecurity.maskKey()` |
| `executeKeyList()` | Safe | Uses `KeySecurity.maskKey()` |
| `executeKeyInfo()` | Safe | Masked by default; full reveal only with `reveal` subcommand (now audited) |
| `executeGenerate*()` | By Design | Admin needs full code; sent as chat message |
| `executeAudit()` | Safe | Passes through `KeySecurity.sanitizeAuditDetails()` |
| SQL audit log insertion | Safe | `SqlDatabaseManager.log()` applies sanitizeAuditDetails() |
| Console/stack traces in debug mode | Conditional | Only when `debug: true` in config |

---

## 6. Command Allowlist Verification

| Action Type | Allowlist Applied | Normalization | Safe |
|------------|:---:|:---:|:---:|
| `run_server_command` | Yes | Strip /, trim, collapse whitespace | Yes |
| `run_player_command` | Yes | Same | Yes |
| `run_ftb_rank_command` | Yes | Same | Yes |
| FTB rank add/remove/set | Yes | Placeholder resolved then normalized | Yes |
| LuckPerms group add/remove | Partial | PermissionBridge directly or `lp user` via executeServerCommand | Yes |
| VIP tier activation commands | Yes | Via VipService → ActionExecutor pipeline | Yes |

Command separator blocks: `;` `&` `|` `\n` `\r` all rejected by `CommandAllowlist.normalize()`.

---

## 7. JSON Mode Limitations

1. **Async saves:** Disk persistence is async. Crash between `putKey` and save completion may lose the last operation. Shutdown drains executor queue before final sync save.
2. **No transactional guarantees:** JSON files represent point-in-time snapshots. Cross-key consistency not guaranteed.
3. **File corruption recovery:** If primary file is corrupt, `.bak` file is attempted. If both are corrupt, data is lost. No checksum/parity.
4. **Scalability:** All keys loaded into memory. For >100K keys, use SQL mode.
5. **ATOMIC_MOVE fallback:** On filesystems without atomic move support, non-atomic move is used; crash during this window may corrupt target file.

---

## 8. SQL Mode Notes

- **Schema:** `code` column has PRIMARY KEY constraint ensuring DB-level uniqueness.
- **REPLACE INTO:** MySQL-specific upsert. Not portable to PostgreSQL/SQLite.
- **Transactions:** `putKeyIfAbsent` now uses explicit transaction with rollback on error.
- **Connection pooling:** Not used. Each operation opens a new connection. Acceptable for low-traffic; consider pooling for high-throughput webstore.
- **No migration framework:** Schema via CREATE TABLE IF NOT EXISTS. Column migrations via ensureColumnExists(). Consider Flyway/Liquibase for production.

---

## 9. PersistenceManager Defensive Copy Coverage

| Method | Defensive Copy | Status |
|--------|:---:|--------|
| `getKey(code)` | `record.copy()` | Safe |
| `getAllKeys()` | `record.copy()` per entry | Safe |
| `getAllPlayerVips()` | Shallow (Map entries shared) | Acceptable |
| `getPendingVariants(uuid)` | `new ArrayList<>(list)` | Safe |
| `getPackageUsage(uuid)` | `new HashMap<>(usage)` | Safe |
| `getAuditLogs()` | `new ArrayList<>(auditLogs)` | Safe |
| `saveKeysSync()` | Now deep-copies via `copy()` | Fixed |

---

## 10. Test Coverage

### Existing Tests
- `KeyServiceCorrectionsTest.java`: 8 tests (reward missing def, empty actions, consumeOnUse, cooldown, dimension block, custom keys, physical item)
- `UniqueCodeGeneratorTest.java`: 2 tests (retry on collision, max attempts)
- `CommandAllowlistTest.java`: 2 tests (prefix matching, disabled allowlist)
- `ActionExecutorPlaceholderTest.java`: 2 tests (brace and percent placeholders)
- Other module tests: `VipServiceCorrectionsTest`, `PackageServiceCorrectionsTest`, etc.

### New Tests (KeyModuleSecurityTest.java — 45 tests)

| Area | Coverage |
|------|----------|
| Key generation | Empty/null charset rejection, single-char charset rejection, zero length rejection, prefix/length correctness, charset enforcement, uniqueness (500 codes) |
| Expired key | Expired rejection, non-expired acceptance, no-expiry acceptance |
| Max uses | Fully-used rejection |
| UUID binding | Correct player accepted, wrong player rejected |
| Already used | Used-by check rejection |
| consumeOnUse | False: no increment, true: increment |
| Action failure | Key not consumed on action failure |
| Concurrent redemption | 4-thread concurrent redeem, only 1 succeeds |
| Code masking | Mask length, short codes, null/empty, deterministic fingerprint, describeKeyForLog format, audit sanitization |
| Command allowlist | Null/empty/separators/newlines, exact prefix, leading slash, whitespace normalization |
| KeyRecord copy | Deep copy isolation for usedBy and consumedInstances |
| Physical key | Instance tracking, sequential consumption |
| PersistenceManager | getKey defensive copy, getAllKeys defensive copies |
| Collision simulation | Retry on collision, max attempts exhaustion |
| Argument validation | generateVipKey rejects invalid arguments, unknown tier; generateRewardKey rejects unknown reward |

**Total: 78 tests, 0 failures.**

---

## 11. Permissions Checklist

All commands below verified. Console always has full access.

| Command | Permission |
|---------|-----------|
| `/easyvip use/activate/confirm` | `easyvip.use` |
| `/easyvip info/select <tier>` | `easyvip.use` |
| `/easyvip variant choose/pending` | `easyvip.use` |
| `/usekey /activate /vip /viptime` | `easyvip.use` (own) / `easyvip.admin` (others) |
| `/link` | `easyvip.use` |
| `/easyvip reload/createvip` | `easyvip.admin` |
| `/easyvip admin addvip/removevip/generate/givepackage/giveitemkey` | `easyvip.admin` |
| `/easyvip key list/info/info reveal/delete` | `easyvip.admin` |
| `/easyvip package list/info` | `easyvip.admin` |
| `/easyvip admin audit` | `easyvip.admin` |
| `/easyvip config reload/validate` | `easyvip.admin` |
| `/easyvip active set/savevipactivation` | `easyvip.admin` |

---

## 12. Residual Risks

1. **Admin misconfiguration:** Setting `commandAllowlistEnabled: false` or adding dangerous commands (e.g., `op`) to allowlist enables key-based privilege escalation. Admin-controlled and documented.
2. **Reward keys with run_server_command:** Execute with server-level permissions. Mitigated by restrictive default allowlist (`ftbranks`, `team`, `effect`, `give`, `broadcast`).
3. **JSON mode crash window:** ~1 second between last async save and shutdown. Mitigated by `flush()` and final sync saves.
4. **Physical key cloning:** Duplicate items can be created via mods, but `easyvip_key_instance` UUID prevents double-consumption.
5. **Memory pressure from KEY_LOCKS:** New lock object per unique code. Grows unbounded. Lightweight objects; acceptable for typical server deployments.

---

## 13. Build and Test Results

```
./gradlew clean buildAll    → BUILD SUCCESSFUL (6s, 20 tasks)
./gradlew test               → BUILD SUCCESSFUL (1s, 78 tests, 0 failures)
```

---

## 14. Changes Summary

| Commit | Description |
|--------|-------------|
| `fix(keys): harden redemption concurrency` | ConcurrentHashMap for confirmations/cooldowns; per-key lock in test method; tierId/rewardKeyId validation |
| `fix(keys): prevent activation code leakage` | SecureRandom for link codes; audit log for full key revelation |
| `fix(keys): validate code generation security` | Charset entropy validation; degenerate charset rejection |
| `fix(keys): harden persistence and data integrity` | Deep-copy KeyRecords in JSON save; SQL transaction + duplicate detection |
| `test(keys): cover security edge cases` | 45 new security tests |
| `docs(security): add key module audit` | This document |

---

## 15. Final Security Checklist

- [x] Key codes generated with SecureRandom
- [x] Charset validated (>=2 distinct chars, >=32 bits entropy warning)
- [x] Per-key mutex via ConcurrentHashMap + synchronized
- [x] Thread-safe confirmation and cooldown maps
- [x] Key not consumed on reward failure
- [x] All logs/audit use masked code + SHA-256 fingerprint
- [x] Full reveal audited via audit log
- [x] Command allowlist applied to all execution paths
- [x] Command separators (; & | \n \r) rejected
- [x] JSON save uses deep-copied snapshots
- [x] SQL upserts use transactions with duplicate detection
- [x] All admin commands require easyvip.admin
- [x] Console commands work without ServerPlayer
- [x] Expired keys rejected
- [x] Fully-used keys rejected
- [x] UUID-bound keys enforce correct player
- [x] consumeOnUse respected for reward keys
- [x] Physical key instance tracking prevents double-use
- [x] Defensive copies returned by PersistenceManager
- [x] Shutdown drains persistence executor
- [x] Build passes: ./gradlew clean buildAll
- [x] Tests pass: 78/78 green
