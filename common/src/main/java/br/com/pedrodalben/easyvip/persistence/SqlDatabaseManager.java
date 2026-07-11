package br.com.pedrodalben.easyvip.persistence;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.lang.reflect.Type;
import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class SqlDatabaseManager {

    private static final Gson GSON = new GsonBuilder().create();
    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();

    private static HikariDataSource dataSource;
    private static boolean initialized = false;

    private SqlDatabaseManager() {
    }

    public static synchronized void initialize(String dbUrl, String dbUsername, String dbPassword) {
        if (dataSource != null && !dataSource.isClosed()) {
            shutdown();
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dbUrl);
        if (dbUsername != null && !dbUsername.isEmpty()) {
            config.setUsername(dbUsername);
        }
        if (dbPassword != null && !dbPassword.isEmpty()) {
            config.setPassword(dbPassword);
        }
        config.setPoolName("EasyVip-Pool");
        int poolSize = Math.max(1, EasyVipConfig.integrations.sqlPoolSize);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(Math.min(poolSize, 2));
        long timeoutMs = Math.max(1, EasyVipConfig.integrations.sqlConnectionTimeoutSeconds) * 1000L;
        config.setConnectionTimeout(timeoutMs);
        config.setIdleTimeout(600000L);
        config.setMaxLifetime(1800000L);

        config.addDataSourceProperty("useSSL", "false");
        config.addDataSourceProperty("allowPublicKeyRetrieval", "true");
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        try {
            dataSource = new HikariDataSource(config);
            createTables();
            initialized = true;
        } catch (Exception e) {
            if (dataSource != null) {
                try {
                    dataSource.close();
                } catch (Exception ignored) {}
                dataSource = null;
            }
            initialized = false;
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Failed to initialize SqlDatabaseManager", e);
        }
    }

    public static boolean isInitialized() {
        return initialized && dataSource != null && !dataSource.isClosed();
    }

    public static synchronized void shutdown() {
        initialized = false;
        if (dataSource != null) {
            try {
                dataSource.close();
            } catch (Exception e) {
                System.err.println("[EasyVip-SQL] Error closing HikariDataSource: " + e.getMessage());
            } finally {
                dataSource = null;
            }
        }
    }

    private static Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("SqlDatabaseManager is not initialized or datasource is closed.");
        }
        return dataSource.getConnection();
    }

    // ─── Table Creation ──────────────────────────────────────

    private static void createTables() {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS easyvip_vips (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        player_name VARCHAR(255) NOT NULL DEFAULT '',
                        last_observed_active_vip VARCHAR(255) DEFAULT NULL,
                        vips_data MEDIUMTEXT
                    )
                """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS easyvip_keys (
                        code VARCHAR(255) PRIMARY KEY,
                        type VARCHAR(50) NOT NULL,
                        tier_id VARCHAR(255) DEFAULT NULL,
                        duration VARCHAR(100) DEFAULT NULL,
                        reward_key_id VARCHAR(255) DEFAULT NULL,
                        max_uses INT NOT NULL DEFAULT 1,
                        used_count INT NOT NULL DEFAULT 0,
                        bound_player_uuid VARCHAR(36) DEFAULT NULL,
                        created_time BIGINT NOT NULL DEFAULT 0,
                        expiry_time BIGINT NOT NULL DEFAULT -1,
                        used_by_json MEDIUMTEXT,
                        last_used_at_by_json MEDIUMTEXT,
                        actions_json MEDIUMTEXT
                    )
                """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS easyvip_pending_variants (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        player_uuid VARCHAR(36) NOT NULL,
                        package_id VARCHAR(255) NOT NULL,
                        variants_json TEXT,
                        timestamp BIGINT NOT NULL DEFAULT 0
                    )
                """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS easyvip_package_usage (
                        player_uuid VARCHAR(36) NOT NULL,
                        package_id VARCHAR(255) NOT NULL,
                        usage_count BIGINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (player_uuid, package_id)
                    )
                """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS easyvip_audit_logs (
                        id VARCHAR(36) PRIMARY KEY,
                        timestamp BIGINT NOT NULL DEFAULT 0,
                        operator VARCHAR(255) DEFAULT NULL,
                        action VARCHAR(255) DEFAULT NULL,
                        details TEXT
                    )
                """);
                break;
            } catch (SQLException e) {
                if (attempt == maxRetries) {
                    throw new RuntimeException("Failed to create database tables after " + maxRetries + " attempts", e);
                }
                System.err.println("[EasyVip-SQL] Table creation attempt " + attempt + " failed (" + e.getMessage() + "). Retrying in " + (attempt * 2) + " seconds...");
                try {
                    Thread.sleep(attempt * 2000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting to retry table creation", e);
                }
            }
        }
    }

    // ─── VIPs ────────────────────────────────────────────────

    public static PlayerVipRegistry getPlayerVips(UUID uuid) {
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT player_name, last_observed_active_vip, vips_data FROM easyvip_vips WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    PlayerVipRegistry registry = new PlayerVipRegistry(uuid);
                    registry.setPlayerName(rs.getString("player_name"));
                    registry.setLastObservedActiveVip(rs.getString("last_observed_active_vip"));
                    String vipsJson = rs.getString("vips_data");
                    if (vipsJson != null && !vipsJson.isEmpty()) {
                        Type type = new TypeToken<Map<String, PlayerVipRecord>>(){}.getType();
                        Map<String, PlayerVipRecord> vips = GSON.fromJson(vipsJson, type);
                        if (vips != null) {
                            registry.setVips(vips);
                        }
                    }
                    return registry;
                }
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading VIPs for " + uuid + ": " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return null;
    }

    public static Map<UUID, PlayerVipRegistry> getAllPlayerVips() {
        Map<UUID, PlayerVipRegistry> result = new HashMap<>();
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT player_uuid, player_name, last_observed_active_vip, vips_data FROM easyvip_vips")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                PlayerVipRegistry registry = new PlayerVipRegistry(uuid);
                registry.setPlayerName(rs.getString("player_name"));
                registry.setLastObservedActiveVip(rs.getString("last_observed_active_vip"));
                String vipsJson = rs.getString("vips_data");
                if (vipsJson != null && !vipsJson.isEmpty()) {
                    Type type = new TypeToken<Map<String, PlayerVipRecord>>(){}.getType();
                    Map<String, PlayerVipRecord> vips = GSON.fromJson(vipsJson, type);
                    if (vips != null) {
                        registry.setVips(vips);
                    }
                }
                result.put(uuid, registry);
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading all VIPs: " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return result;
    }

    public static void updatePlayerVips(UUID uuid, PlayerVipRegistry registry) {
        LOCK.writeLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "REPLACE INTO easyvip_vips (player_uuid, player_name, last_observed_active_vip, vips_data) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, registry.getPlayerName() != null ? registry.getPlayerName() : "");
            ps.setString(3, registry.getLastObservedActiveVip());
            ps.setString(4, GSON.toJson(registry.getVips()));
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error updating VIPs for " + uuid + ": " + e.getMessage());
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    // ─── Keys ────────────────────────────────────────────────

    public static KeyRecord getKey(String code) {
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM easyvip_keys WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapKeyRecord(rs);
                }
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading key " + code + ": " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return null;
    }

    public static List<KeyRecord> getAllKeys() {
        List<KeyRecord> result = new ArrayList<>();
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM easyvip_keys")) {
            while (rs.next()) {
                result.add(mapKeyRecord(rs));
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading all keys: " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return result;
    }

    public static void putKey(KeyRecord record) {
        LOCK.writeLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                 REPLACE INTO easyvip_keys
                 (code, type, tier_id, duration, reward_key_id, max_uses, used_count,
                  bound_player_uuid, created_time, expiry_time, used_by_json,
                  last_used_at_by_json, actions_json)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                 """)) {
            ps.setString(1, record.getCode());
            ps.setString(2, record.getType());
            ps.setString(3, record.getTierId());
            ps.setString(4, record.getDuration());
            ps.setString(5, record.getRewardKeyId());
            ps.setInt(6, record.getMaxUses());
            ps.setInt(7, record.getUsedCount());
            ps.setString(8, record.getBoundPlayerUuid() != null ? record.getBoundPlayerUuid().toString() : null);
            ps.setLong(9, record.getCreatedTime());
            ps.setLong(10, record.getExpiryTime());
            ps.setString(11, GSON.toJson(record.getUsedBy()));
            ps.setString(12, GSON.toJson(record.getLastUsedAtBy()));
            ps.setString(13, GSON.toJson(record.getActions()));
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error saving key " + record.getCode() + ": " + e.getMessage());
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public static void removeKey(String code) {
        LOCK.writeLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM easyvip_keys WHERE code = ?")) {
            ps.setString(1, code);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error removing key " + code + ": " + e.getMessage());
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    private static KeyRecord mapKeyRecord(ResultSet rs) throws SQLException {
        KeyRecord record = new KeyRecord();
        record.setCode(rs.getString("code"));
        record.setType(rs.getString("type"));
        record.setTierId(rs.getString("tier_id"));
        record.setDuration(rs.getString("duration"));
        record.setRewardKeyId(rs.getString("reward_key_id"));
        record.setMaxUses(rs.getInt("max_uses"));
        record.setUsedCount(rs.getInt("used_count"));
        String boundStr = rs.getString("bound_player_uuid");
        if (boundStr != null && !boundStr.isEmpty()) {
            record.setBoundPlayerUuid(UUID.fromString(boundStr));
        }
        record.setCreatedTime(rs.getLong("created_time"));
        record.setExpiryTime(rs.getLong("expiry_time"));

        String usedByJson = rs.getString("used_by_json");
        if (usedByJson != null && !usedByJson.isEmpty()) {
            Type type = new TypeToken<Set<UUID>>(){}.getType();
            Set<UUID> usedBy = GSON.fromJson(usedByJson, type);
            if (usedBy != null) record.setUsedBy(usedBy);
        }

        String lastUsedJson = rs.getString("last_used_at_by_json");
        if (lastUsedJson != null && !lastUsedJson.isEmpty()) {
            Type type = new TypeToken<Map<UUID, Long>>(){}.getType();
            Map<UUID, Long> lastUsed = GSON.fromJson(lastUsedJson, type);
            if (lastUsed != null) record.setLastUsedAtBy(lastUsed);
        }

        String actionsJson = rs.getString("actions_json");
        if (actionsJson != null && !actionsJson.isEmpty()) {
            Type type = new TypeToken<List<Map<String, Object>>>(){}.getType();
            List<Map<String, Object>> actions = GSON.fromJson(actionsJson, type);
            if (actions != null) record.setActions(actions);
        }

        return record;
    }

    // ─── Pending Variants ────────────────────────────────────

    public static List<PendingVariantSelection> getPendingVariants(UUID uuid) {
        List<PendingVariantSelection> result = new ArrayList<>();
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM easyvip_pending_variants WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapPendingVariant(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading pending variants for " + uuid + ": " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return result;
    }

    public static Map<UUID, List<PendingVariantSelection>> getAllPendingVariants() {
        Map<UUID, List<PendingVariantSelection>> result = new HashMap<>();
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM easyvip_pending_variants")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                result.computeIfAbsent(uuid, k -> new ArrayList<>()).add(mapPendingVariant(rs));
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading all pending variants: " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return result;
    }

    public static void addPendingVariant(UUID uuid, PendingVariantSelection selection) {
        LOCK.writeLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO easyvip_pending_variants (player_uuid, package_id, variants_json, timestamp) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, selection.getPackageId());
            ps.setString(3, GSON.toJson(selection.getVariants()));
            ps.setLong(4, selection.getTimestamp());
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error adding pending variant: " + e.getMessage());
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public static void removePendingVariant(UUID uuid, String packageId) {
        LOCK.writeLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM easyvip_pending_variants WHERE player_uuid = ? AND package_id = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, packageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error removing pending variant: " + e.getMessage());
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    private static PendingVariantSelection mapPendingVariant(ResultSet rs) throws SQLException {
        PendingVariantSelection sel = new PendingVariantSelection();
        sel.setPlayerUuid(UUID.fromString(rs.getString("player_uuid")));
        sel.setPackageId(rs.getString("package_id"));
        sel.setTimestamp(rs.getLong("timestamp"));
        String variantsJson = rs.getString("variants_json");
        if (variantsJson != null && !variantsJson.isEmpty()) {
            Type type = new TypeToken<List<String>>(){}.getType();
            List<String> variants = GSON.fromJson(variantsJson, type);
            if (variants != null) sel.setVariants(variants);
        }
        return sel;
    }

    // ─── Package Usage ───────────────────────────────────────

    public static Map<String, Long> getPackageUsage(UUID uuid) {
        Map<String, Long> result = new HashMap<>();
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT package_id, usage_count FROM easyvip_package_usage WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("package_id"), rs.getLong("usage_count"));
                }
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading package usage for " + uuid + ": " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return result;
    }

    public static Map<UUID, Map<String, Long>> getAllPackageUsage() {
        Map<UUID, Map<String, Long>> result = new HashMap<>();
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT player_uuid, package_id, usage_count FROM easyvip_package_usage")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                result.computeIfAbsent(uuid, k -> new HashMap<>())
                    .put(rs.getString("package_id"), rs.getLong("usage_count"));
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading all package usage: " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return result;
    }

    public static void updatePackageUsage(UUID uuid, Map<String, Long> usage) {
        LOCK.writeLock().lock();
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement deletePs = conn.prepareStatement(
                     "DELETE FROM easyvip_package_usage WHERE player_uuid = ?");
                 PreparedStatement insertPs = conn.prepareStatement(
                     "INSERT INTO easyvip_package_usage (player_uuid, package_id, usage_count) VALUES (?, ?, ?)")) {
                deletePs.setString(1, uuid.toString());
                deletePs.executeUpdate();

                for (Map.Entry<String, Long> entry : usage.entrySet()) {
                    insertPs.setString(1, uuid.toString());
                    insertPs.setString(2, entry.getKey());
                    insertPs.setLong(3, entry.getValue());
                    insertPs.addBatch();
                }
                insertPs.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error updating package usage for " + uuid + ": " + e.getMessage());
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    // ─── Audit Logs ──────────────────────────────────────────

    public static void log(AuditLogRecord record) {
        LOCK.writeLock().lock();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO easyvip_audit_logs (id, timestamp, operator, action, details) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, record.getId().toString());
            ps.setLong(2, record.getTimestamp());
            ps.setString(3, record.getOperator());
            ps.setString(4, record.getAction());
            ps.setString(5, record.getDetails());
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error writing audit log: " + e.getMessage());
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public static List<AuditLogRecord> getAuditLogs() {
        List<AuditLogRecord> result = new ArrayList<>();
        LOCK.readLock().lock();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT * FROM easyvip_audit_logs ORDER BY timestamp ASC")) {
            while (rs.next()) {
                AuditLogRecord record = new AuditLogRecord();
                record.setId(UUID.fromString(rs.getString("id")));
                record.setTimestamp(rs.getLong("timestamp"));
                record.setOperator(rs.getString("operator"));
                record.setAction(rs.getString("action"));
                record.setDetails(rs.getString("details"));
                result.add(record);
            }
        } catch (SQLException e) {
            System.err.println("[EasyVip-SQL] Error reading audit logs: " + e.getMessage());
        } finally {
            LOCK.readLock().unlock();
        }
        return result;
    }
}
