package br.com.pedrodalben.easyvip.service;

import br.com.pedrodalben.easyvip.action.ActionExecutor;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.PendingVariantSelection;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

public final class PackageService {

    private PackageService() {
    }

    public static boolean givePackage(ServerPlayer player, String packageId) {
        EasyVipConfig.PackageDefinition def = EasyVipConfig.packages.list.get(packageId);
        if (def == null) {
            player.sendSystemMessage(Component.literal(
                    ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.packageNotFound, new HashMap<>())
            ));
            return false;
        }

        Map<String, String> ctx = new HashMap<>();
        ctx.put("package", def.displayName);
        ctx.put("package_id", def.id);

        if (def.variants != null && !def.variants.isEmpty()) {
            List<String> variantNames = new ArrayList<>(def.variants.keySet());
            PendingVariantSelection pending = new PendingVariantSelection(player.getUUID(), packageId, variantNames);
            PersistenceManager.addPendingVariant(player.getUUID(), pending);

            String varMsg = EasyVipConfig.messages.prefix + EasyVipConfig.messages.variantPending;
            player.sendSystemMessage(Component.literal(
                    ActionExecutor.resolvePlaceholders(varMsg, ctx)
            ));
            return true;
        } else {
            // No variants, execute actions directly
            ActionExecutor.execute(player, def.actions, ctx);
            player.sendSystemMessage(Component.literal(
                    ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.packageGiven, ctx)
            ));
            PersistenceManager.log(player.getGameProfile().getName(), "give_package", "Given package " + packageId + " to " + player.getGameProfile().getName());
            return true;
        }
    }

    public static boolean chooseVariant(ServerPlayer player, String packageId, String variantName) {
        UUID uuid = player.getUUID();
        List<PendingVariantSelection> pendingList = PersistenceManager.getPendingVariants(uuid);
        PendingVariantSelection match = null;

        for (PendingVariantSelection sel : pendingList) {
            if (sel.getPackageId().equals(packageId)) {
                match = sel;
                break;
            }
        }

        if (match == null) {
            player.sendSystemMessage(Component.literal(
                    ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.packageNotFound, new HashMap<>())
            ));
            return false;
        }

        EasyVipConfig.PackageDefinition def = EasyVipConfig.packages.list.get(packageId);
        if (def == null) {
            PersistenceManager.removePendingVariant(uuid, packageId);
            return false;
        }

        List<Map<String, Object>> variantActions = def.variants.get(variantName.toLowerCase());
        if (variantActions == null) {
            Map<String, String> ctx = new HashMap<>();
            ctx.put("allowed_variants", String.join(", ", def.variants.keySet()));
            player.sendSystemMessage(Component.literal(
                    ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.variantInvalid, ctx)
            ));
            return false;
        }

        Map<String, String> ctx = new HashMap<>();
        ctx.put("package", def.displayName);
        ctx.put("package_id", def.id);
        ctx.put("variant", variantName);

        // Execute base actions + variant actions
        ActionExecutor.execute(player, def.actions, ctx);
        ActionExecutor.execute(player, variantActions, ctx);

        PersistenceManager.removePendingVariant(uuid, packageId);

        player.sendSystemMessage(Component.literal(
                ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.variantSelected, ctx)
        ));

        PersistenceManager.log(player.getGameProfile().getName(), "choose_variant", 
                "Selected variant " + variantName + " for package " + packageId);

        return true;
    }
}
