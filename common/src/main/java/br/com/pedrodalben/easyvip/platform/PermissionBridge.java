package br.com.pedrodalben.easyvip.platform;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import net.minecraft.server.level.ServerPlayer;

public final class PermissionBridge {

    private static boolean luckPermsPresent = false;
    private static boolean ftbRanksPresent = false;

    static {
        try {
            Class.forName("net.luckperms.api.LuckPermsProvider");
            luckPermsPresent = true;
        } catch (ClassNotFoundException e) {
            // Not present
        }

        try {
            Class.forName("dev.ftb.mods.ftbranks.api.FTBRanksAPI");
            ftbRanksPresent = true;
        } catch (ClassNotFoundException e) {
            // Not present
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

        if (primary.equals("luckperms") && luckPermsPresent && EasyVipConfig.integrations.luckpermsEnabled) {
            return LuckPermsWrapper.hasPermission(player, permission);
        } else if (primary.equals("ftbranks") && ftbRanksPresent && EasyVipConfig.integrations.ftbRanksEnabled) {
            return FtbRanksWrapper.hasPermission(player, permission);
        }

        // Fallback to active bridges if primary didn't match/is disabled
        if (luckPermsPresent && EasyVipConfig.integrations.luckpermsEnabled) {
            return LuckPermsWrapper.hasPermission(player, permission);
        }
        if (ftbRanksPresent && EasyVipConfig.integrations.ftbRanksEnabled) {
            return FtbRanksWrapper.hasPermission(player, permission);
        }

        // Vanilla OP Fallback
        return player.hasPermissions(4);
    }

    public static void setPermission(ServerPlayer player, String permission, boolean value) {
        if (luckPermsPresent && EasyVipConfig.integrations.luckpermsEnabled) {
            LuckPermsWrapper.setPermission(player, permission, value);
        }
    }
}
