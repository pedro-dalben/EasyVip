package br.com.pedrodalben.easyvip.platform;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import net.minecraft.server.level.ServerPlayer;

public final class PermissionBridge {

    private static boolean luckPermsPresent = false;
    private static boolean ftbRanksPresent = false;

    static {
        try {
            Class.forName("net.luckperms.api.LuckPermsProvider", false, PermissionBridge.class.getClassLoader());
            luckPermsPresent = true;
        } catch (Throwable e) {
            luckPermsPresent = false;
        }

        try {
            Class.forName("dev.ftb.mods.ftbranks.api.FTBRanksAPI", false, PermissionBridge.class.getClassLoader());
            ftbRanksPresent = true;
        } catch (Throwable e) {
            ftbRanksPresent = false;
        }
    }

    private PermissionBridge() {
    }

    public static boolean isLuckPermsPresent() {
        return luckPermsPresent;
    }

    public static boolean isFtbRanksPresent() {
        return ftbRanksPresent;
    }

    public static boolean hasPermission(ServerPlayer player, String permission) {
        String primary = EasyVipConfig.integrations.primaryPermissionBridge.toLowerCase();
        boolean opFallback = player.hasPermissions(4);

        boolean luckPermsAllowed = false;
        if (luckPermsPresent && EasyVipConfig.integrations.luckpermsEnabled) {
            luckPermsAllowed = LuckPermsWrapper.hasPermission(player, permission);
        }

        boolean ftbRanksAllowed = false;
        if (ftbRanksPresent && EasyVipConfig.integrations.ftbRanksEnabled) {
            ftbRanksAllowed = FtbRanksWrapper.hasPermission(player, permission);
        }

        boolean primaryAllowed = switch (primary) {
            case "luckperms" -> luckPermsAllowed;
            case "ftbranks" -> ftbRanksAllowed;
            default -> false;
        };

        return resolvePermission(primaryAllowed, luckPermsAllowed, ftbRanksAllowed, opFallback);
    }

    static boolean resolvePermission(boolean primaryAllowed, boolean fallbackLuckPermsAllowed, boolean fallbackFtbRanksAllowed, boolean opFallback) {
        if (primaryAllowed) {
            return true;
        }
        if (fallbackLuckPermsAllowed) {
            return true;
        }
        if (fallbackFtbRanksAllowed) {
            return true;
        }
        return opFallback;
    }

    public static void setPermission(ServerPlayer player, String permission, boolean value) {
        if (luckPermsPresent && EasyVipConfig.integrations.luckpermsEnabled) {
            LuckPermsWrapper.setPermission(player, permission, value);
        }
    }

    public static void setGroup(ServerPlayer player, String group, boolean value) {
        if (luckPermsPresent && EasyVipConfig.integrations.luckpermsEnabled) {
            LuckPermsWrapper.setGroup(player, group, value);
        }
    }
}
