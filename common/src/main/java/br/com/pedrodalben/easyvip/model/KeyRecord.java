package br.com.pedrodalben.easyvip.model;

import java.util.*;

public class KeyRecord {
    private String code;
    private String type; // "vip" or "reward"
    private String tierId;
    private String duration; // e.g., "30d", "permanent"
    private String rewardKeyId;
    private int maxUses = 1;
    private int usedCount = 0;
    private UUID boundPlayerUuid;
    private long createdTime;
    private long expiryTime = -1; // -1 for no expiration
    private Set<UUID> usedBy = new HashSet<>();
    private List<Map<String, Object>> actions = new ArrayList<>();

    public KeyRecord() {
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTierId() {
        return tierId;
    }

    public void setTierId(String tierId) {
        this.tierId = tierId;
    }

    public String getDuration() {
        return duration;
    }

    public void setDuration(String duration) {
        this.duration = duration;
    }

    public String getRewardKeyId() {
        return rewardKeyId;
    }

    public void setRewardKeyId(String rewardKeyId) {
        this.rewardKeyId = rewardKeyId;
    }

    public int getMaxUses() {
        return maxUses;
    }

    public void setMaxUses(int maxUses) {
        this.maxUses = maxUses;
    }

    public int getUsedCount() {
        return usedCount;
    }

    public void setUsedCount(int usedCount) {
        this.usedCount = usedCount;
    }

    public UUID getBoundPlayerUuid() {
        return boundPlayerUuid;
    }

    public void setBoundPlayerUuid(UUID boundPlayerUuid) {
        this.boundPlayerUuid = boundPlayerUuid;
    }

    public long getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }

    public long getExpiryTime() {
        return expiryTime;
    }

    public void setExpiryTime(long expiryTime) {
        this.expiryTime = expiryTime;
    }

    public Set<UUID> getUsedBy() {
        return usedBy;
    }

    public void setUsedBy(Set<UUID> usedBy) {
        this.usedBy = usedBy;
    }

    public List<Map<String, Object>> getActions() {
        return actions;
    }

    public void setActions(List<Map<String, Object>> actions) {
        this.actions = actions;
    }

    public boolean isExpired() {
        if (expiryTime == -1) {
            return false;
        }
        return System.currentTimeMillis() > expiryTime;
    }

    public boolean isFullyUsed() {
        return usedCount >= maxUses;
    }
}
