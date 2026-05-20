package br.com.pedrodalben.easyvip.platform;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;
import net.minecraft.server.level.ServerPlayer;

public final class LuckPermsWrapper {

    private LuckPermsWrapper() {
    }

    public static boolean hasPermission(ServerPlayer player, String permission) {
        try {
            LuckPerms api = LuckPermsProvider.get();
            User user = api.getUserManager().getUser(player.getUUID());
            if (user != null) {
                return user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
            }
        } catch (Throwable t) {
            // Safe fallback
        }
        return false;
    }

    public static void setPermission(ServerPlayer player, String permission, boolean value) {
        try {
            LuckPerms api = LuckPermsProvider.get();
            User user = api.getUserManager().getUser(player.getUUID());
            if (user != null) {
                Node node = PermissionNode.builder(permission).value(value).build();
                if (value) {
                    user.data().add(node);
                } else {
                    user.data().remove(node);
                }
                api.getUserManager().saveUser(user);
            }
        } catch (Throwable t) {
            // Safe fallback
        }
    }
}
