package br.com.pedrodalben.easyvip.model;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerVipRegistry {
    private UUID playerUuid;
    private Map<String, PlayerVipRecord> vips = new HashMap<>();

    public PlayerVipRegistry() {
    }

    public PlayerVipRegistry(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public void setPlayerUuid(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public Map<String, PlayerVipRecord> getVips() {
        return vips;
    }

    public void setVips(Map<String, PlayerVipRecord> vips) {
        this.vips = vips;
    }
}
