package br.com.pedrodalben.easyvip.platform;

import dev.ftb.mods.ftbranks.api.FTBRanksAPI;
import net.minecraft.server.level.ServerPlayer;

public final class FtbRanksWrapper {

    private FtbRanksWrapper() {
    }

    public static boolean hasPermission(ServerPlayer player, String permission) {
        try {
            return FTBRanksAPI.getPermissionValue(player, permission).asBoolean().orElse(false);
        } catch (Throwable t) {
            // Safe fallback
        }
        return false;
    }

    public static void setPermission(ServerPlayer player, String permission, boolean value) {
        // FTB Ranks doesn't support dynamic node injection at runtime via API
    }
}
