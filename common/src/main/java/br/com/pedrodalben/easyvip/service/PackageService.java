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

    public static int cleanupExpiredPendingVariants() {
        int removed = 0;
        int timeout = EasyVipConfig.common.variantSelectionTimeoutSeconds;
        for (Map.Entry<UUID, List<PendingVariantSelection>> entry : PersistenceManager.getAllPendingVariants().entrySet()) {
            UUID uuid = entry.getKey();
            for (PendingVariantSelection selection : new ArrayList<>(entry.getValue())) {
                if (selection.isExpired(timeout)) {
                    PersistenceManager.removePendingVariant(uuid, selection.getPackageId());
                    removed++;
                }
            }
        }
        return removed;
    }

    public static void notifyPendingVariantsOnLogin(ServerPlayer player) {
        if (!EasyVipConfig.common.notifyPendingVariantOnLogin) {
            return;
        }
        List<PendingVariantSelection> pendingList = PersistenceManager.getPendingVariants(player.getUUID());
        if (pendingList.isEmpty()) {
            return;
        }
        for (PendingVariantSelection selection : pendingList) {
            if (!selection.isExpired(EasyVipConfig.common.variantSelectionTimeoutSeconds)) {
                EasyVipConfig.PackageDefinition def = EasyVipConfig.packages.list.get(selection.getPackageId());
                String pkgDisplay = def != null ? def.displayName : selection.getPackageId();
                Map<String, String> ctx = new HashMap<>();
                ctx.put("package", pkgDisplay);
                ctx.put("package_id", selection.getPackageId());
                player.sendSystemMessage(Component.literal(
                        ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.variantPending, ctx)
                ));
            }
        }
    }

    public static boolean givePackage(ServerPlayer player, String packageId) {
        EasyVipConfig.PackageDefinition def = EasyVipConfig.packages.list.get(packageId);
        if (def == null) {
            player.sendSystemMessage(Component.literal(
                    ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.packageNotFound, new HashMap<>())
            ));
            return false;
        }

        if (!def.repeatable) {
            Map<String, Long> usage = PersistenceManager.getPackageUsage(player.getUUID());
            if (usage.containsKey(packageId)) {
                player.sendSystemMessage(Component.literal(
                        ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + "&cEste pacote já foi resgatado anteriormente.", new HashMap<>())
                ));
                return false;
            }
        }

        if (def.cooldownSeconds > 0) {
            Map<String, Long> usage = PersistenceManager.getPackageUsage(player.getUUID());
            Long lastUsed = usage.get(packageId);
            if (lastUsed != null && System.currentTimeMillis() - lastUsed < (def.cooldownSeconds * 1000L)) {
                player.sendSystemMessage(Component.literal(
                        ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + "&cEste pacote ainda está em cooldown.", new HashMap<>())
                ));
                return false;
            }
        }

        Map<String, String> ctx = new HashMap<>();
        ctx.put("package", def.displayName);
        ctx.put("package_id", def.id);

        if (def.variants != null && !def.variants.isEmpty()) {
            List<String> variantNames = new ArrayList<>(def.variants.keySet());
            PendingVariantSelection pending = new PendingVariantSelection(player.getUUID(), packageId, variantNames);
            PersistenceManager.addPendingVariant(player.getUUID(), pending);
            markPackageUsage(player.getUUID(), packageId);

            String varMsg = EasyVipConfig.messages.prefix + EasyVipConfig.messages.variantPending;
            player.sendSystemMessage(Component.literal(
                    ActionExecutor.resolvePlaceholders(varMsg, ctx)
            ));
            return true;
        } else {
            // No variants, execute actions directly
            boolean ok = ActionExecutor.execute(player, def.actions, ctx);
            if (!ok) {
                return false;
            }
            markPackageUsage(player.getUUID(), packageId);
            player.sendSystemMessage(Component.literal(
                    ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.packageGiven, ctx)
            ));
            PersistenceManager.log(player.getGameProfile().getName(), "give_package", "Given package " + packageId + " to " + player.getGameProfile().getName());
            return true;
        }
    }

    public static boolean chooseVariant(ServerPlayer player, String packageId, String variantName) {
        UUID uuid = player.getUUID();
        cleanupExpiredPendingVariants();
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
        boolean ok = ActionExecutor.execute(player, def.actions, ctx);
        ok = ActionExecutor.execute(player, variantActions, ctx) && ok;
        if (!ok) {
            return false;
        }

        PersistenceManager.removePendingVariant(uuid, packageId);
        markPackageUsage(uuid, packageId);

        player.sendSystemMessage(Component.literal(
                ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.variantSelected, ctx)
        ));

        PersistenceManager.log(player.getGameProfile().getName(), "choose_variant", 
                "Selected variant " + variantName + " for package " + packageId);

        return true;
    }

    public static void markPackageUsage(UUID uuid, String packageId) {
        Map<String, Long> usage = PersistenceManager.getPackageUsage(uuid);
        usage.put(packageId, System.currentTimeMillis());
        PersistenceManager.updatePackageUsage(uuid, usage);
    }
}
