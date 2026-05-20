package br.com.pedrodalben.easyvip;

import br.com.pedrodalben.easyvip.action.ActionExecutor;
import br.com.pedrodalben.easyvip.command.EasyVipCommands;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.KeyRecord;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import br.com.pedrodalben.easyvip.service.ExpirationService;
import br.com.pedrodalben.easyvip.service.KeyService;
import br.com.pedrodalben.easyvip.service.VipService;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Mod("easyvip")
public final class NeoForgeEasyVipMod {

    private static final String KEY_TAG = "easyvip_key";

    private final NeoForgePlatformBridge platformBridge = new NeoForgePlatformBridge();

    public NeoForgeEasyVipMod() {
        ActionExecutor.setPlatform(platformBridge);
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        EasyVipCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        Path configDir = server.getServerDirectory().resolve("config").resolve("easyvip");

        try {
            EasyVipConfig.initialize(configDir);
            EasyVipConfig.loadAll();
            PersistenceManager.initialize(configDir);
            ExpirationService.start(server);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize EasyVip configuration", e);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        ExpirationService.stop();
        PersistenceManager.shutdown();
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            VipService.handlePlayerJoin(player);
        }
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) {
            return;
        }

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) {
            return;
        }

        CompoundTag tag = customData.copyTag();
        if (!tag.contains(KEY_TAG)) {
            return;
        }

        String keyCode = tag.getString(KEY_TAG).trim();
        if (keyCode.isEmpty()) {
            return;
        }

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        KeyService.RedeemResult result = KeyService.redeemKey(player, keyCode, false);
        if (result == KeyService.RedeemResult.SUCCESS) {
            stack.shrink(1);
            player.sendSystemMessage(Component.literal("§7[§eEasyVip§7] §aChave usada com sucesso."));
            return;
        }

        sendRedeemFeedback(player, result, keyCode);
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
