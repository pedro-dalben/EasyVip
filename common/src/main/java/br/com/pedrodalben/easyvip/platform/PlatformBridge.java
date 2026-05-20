package br.com.pedrodalben.easyvip.platform;

import net.minecraft.server.level.ServerPlayer;
import java.util.Map;

public interface PlatformBridge {

    boolean hasPermission(ServerPlayer player, String permission);

    void setPermissionFlagInternal(ServerPlayer player, String permission, boolean active);

    void fireCustomEventHook(ServerPlayer player, String hook, Map<String, String> context);
}
