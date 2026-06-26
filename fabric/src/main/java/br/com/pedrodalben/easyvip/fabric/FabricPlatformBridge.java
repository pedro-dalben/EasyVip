package br.com.pedrodalben.easyvip.fabric;

import br.com.pedrodalben.easyvip.platform.PermissionBridge;
import br.com.pedrodalben.easyvip.platform.PlatformBridge;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

public final class FabricPlatformBridge implements PlatformBridge {

    @Override
    public boolean hasPermission(ServerPlayer player, String permission) {
        return PermissionBridge.hasPermission(player, permission);
    }

    @Override
    public void setPermissionFlagInternal(ServerPlayer player, String permission, boolean active) {
        PermissionBridge.setPermission(player, permission, active);
    }

    @Override
    public void fireCustomEventHook(ServerPlayer player, String hook, Map<String, String> context) {
    }
}
