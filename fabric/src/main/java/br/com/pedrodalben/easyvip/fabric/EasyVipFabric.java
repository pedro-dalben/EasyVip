package br.com.pedrodalben.easyvip.fabric;

import br.com.pedrodalben.easyvip.action.ActionExecutor;
import br.com.pedrodalben.easyvip.command.EasyVipCommands;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.KeyRecord;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import br.com.pedrodalben.easyvip.service.ExpirationService;
import br.com.pedrodalben.easyvip.service.KeyService;
import br.com.pedrodalben.easyvip.service.PackageService;
import br.com.pedrodalben.easyvip.service.VipService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class EasyVipFabric implements ModInitializer {

    private static final String KEY_TAG = "easyvip_key";

    private final FabricPlatformBridge platformBridge = new FabricPlatformBridge();

    @Override
    public void onInitialize() {
        ActionExecutor.setPlatform(platformBridge);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                EasyVipCommands.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            Path configDir = server.getServerDirectory().resolve("config").resolve("easyvip");
            try {
                EasyVipConfig.initialize(configDir);
                EasyVipConfig.loadAll();
                PersistenceManager.initialize(configDir);
                ExpirationService.start(server);
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize EasyVip configuration", e);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ExpirationService.stop();
            PersistenceManager.shutdown();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            PackageService.cleanupExpiredPendingVariants(player.getUUID());
            PackageService.notifyPendingVariantsOnLogin(player);
            VipService.handlePlayerJoin(player);
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResultHolder.pass(player.getItemInHand(hand));
            }

            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty()) {
                return InteractionResultHolder.pass(stack);
            }

            if (!KeyService.isPhysicalKeyItem(stack)) {
                return InteractionResultHolder.pass(stack);
            }

            String keyCode = stack.get(DataComponents.CUSTOM_DATA)
                    .copyTag()
                    .getString(KEY_TAG).trim();
            if (keyCode.isEmpty()) {
                return InteractionResultHolder.pass(stack);
            }

            KeyService.RedeemResult result = KeyService.redeemKey(serverPlayer, keyCode, false);
            if (result == KeyService.RedeemResult.SUCCESS) {
                stack.shrink(1);
                serverPlayer.sendSystemMessage(Component.literal("§7[§eEasyVip§7] §aChave usada com sucesso."));
                return InteractionResultHolder.success(stack);
            }

            sendRedeemFeedback(serverPlayer, result, keyCode);
            return InteractionResultHolder.consume(stack);
        });
    }

    private void sendRedeemFeedback(ServerPlayer player, KeyService.RedeemResult result, String key) {
        String msg;
        switch (result) {
            case INVALID_KEY:
                msg = EasyVipConfig.messages.prefix + EasyVipConfig.messages.invalidKey;
                break;
            case EXPIRED:
                msg = EasyVipConfig.messages.prefix + EasyVipConfig.messages.keyExpired;
                break;
            case NO_USES_LEFT:
                msg = EasyVipConfig.messages.prefix + EasyVipConfig.messages.keyNoUsesLeft;
                break;
            case ON_COOLDOWN:
                msg = EasyVipConfig.messages.prefix + "&cAguarde um momento antes de usar outra chave.";
                break;
            case ALREADY_USED:
                msg = EasyVipConfig.messages.prefix + EasyVipConfig.messages.keyAlreadyUsed;
                break;
            case BOUND_TO_OTHER:
                msg = EasyVipConfig.messages.prefix + EasyVipConfig.messages.keyBoundToOtherPlayer;
                break;
            case CONFIRMATION_REQUIRED: {
                KeyRecord rec = PersistenceManager.getKey(key.trim().toUpperCase());
                if (rec == null) rec = PersistenceManager.getKey(key.trim());

                String tierDisplay = "";
                String duration = "";
                if (rec != null) {
                    if (rec.getType().equalsIgnoreCase("vip")) {
                        var tierDef = EasyVipConfig.tiers.list.get(rec.getTierId());
                        tierDisplay = tierDef != null ? tierDef.displayName : rec.getTierId();
                        duration = rec.getDuration();
                    } else {
                        var rkDef = EasyVipConfig.rewardKeys.list.get(rec.getRewardKeyId());
                        tierDisplay = rkDef != null ? rkDef.displayName : rec.getRewardKeyId();
                        duration = "recompensa";
                    }
                }

                Map<String, String> context = new HashMap<>();
                context.put("tier_display", tierDisplay);
                context.put("duration", duration);
                msg = ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.keyConfirmRequired, context);
                player.sendSystemMessage(Component.literal(msg));
                return;
            }
            default:
                msg = EasyVipConfig.messages.prefix + "&cErro ao processar chave.";
                break;
        }

        player.sendSystemMessage(Component.literal(ActionExecutor.resolvePlaceholders(msg, new HashMap<>())));
    }
}
