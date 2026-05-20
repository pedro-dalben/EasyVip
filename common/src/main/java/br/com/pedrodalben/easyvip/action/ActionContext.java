package br.com.pedrodalben.easyvip.action;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class ActionContext {

    private final MinecraftServer server;
    private final UUID playerUuid;
    private final String playerName;
    private final ServerPlayer onlinePlayer;
    private final String source;

    public ActionContext(MinecraftServer server, UUID playerUuid, String playerName, ServerPlayer onlinePlayer, String source) {
        this.server = server;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.onlinePlayer = onlinePlayer;
        this.source = source;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public ServerPlayer getOnlinePlayer() {
        return onlinePlayer;
    }

    public String getSource() {
        return source;
    }

    public static ActionContext online(ServerPlayer player, String source) {
        return new ActionContext(player.getServer(), player.getUUID(), player.getGameProfile().getName(), player, source);
    }

    public static ActionContext offline(MinecraftServer server, UUID playerUuid, String playerName, String source) {
        return new ActionContext(server, playerUuid, playerName, null, source);
    }
}
