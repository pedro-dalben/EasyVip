package br.com.pedrodalben.easyvip.command;

import br.com.pedrodalben.easyvip.action.ActionExecutor;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.*;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import br.com.pedrodalben.easyvip.platform.PermissionBridge;
import br.com.pedrodalben.easyvip.service.*;
import br.com.pedrodalben.easyvip.webstore.WebStoreSyncService;
import br.com.pedrodalben.easyvip.webstore.WebStoreFulfillmentService;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import br.com.pedrodalben.easyvip.util.DurationParser;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class EasyVipCommands {

    private EasyVipCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("easyvip")
                .requires(src -> hasPermission(src, "easyvip.use"))
                .executes(EasyVipCommands::executeHelp);

        // /easyvip reload
        root.then(Commands.literal("reload")
                .requires(src -> hasPermission(src, "easyvip.admin"))
                .executes(EasyVipCommands::executeConfigReload));

        // /easyvip createvip <id> <display_name> [color]
        root.then(Commands.literal("createvip")
                .requires(src -> hasPermission(src, "easyvip.admin"))
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("display_name", StringArgumentType.string())
                                .executes(EasyVipCommands::executeCreateVip)
                                .then(Commands.argument("color", StringArgumentType.word())
                                        .executes(EasyVipCommands::executeCreateVip)))));

        // ─── Player Subcommands ─────────────────────────────────

        // /easyvip use <key>
        root.then(Commands.literal("use")
                .then(Commands.argument("key", StringArgumentType.string())
                        .executes(EasyVipCommands::executeUseKey)));

        // /easyvip activate <key> (alias)
        root.then(Commands.literal("activate")
                .then(Commands.argument("key", StringArgumentType.string())
                        .executes(EasyVipCommands::executeUseKey)));

        // /easyvip confirm
        root.then(Commands.literal("confirm")
                .executes(EasyVipCommands::executeConfirmKey));

        // /usekey <key> (alias)
        dispatcher.register(Commands.literal("usekey")
                .requires(src -> hasPermission(src, "easyvip.use"))
                .then(Commands.argument("key", StringArgumentType.string())
                        .executes(EasyVipCommands::executeUseKey)));

        // /activate <key> (alias)
        dispatcher.register(Commands.literal("activate")
                .requires(src -> hasPermission(src, "easyvip.use"))
                .then(Commands.argument("key", StringArgumentType.string())
                        .executes(EasyVipCommands::executeUseKey)));

        // /vip <key> (alias)
        dispatcher.register(Commands.literal("vip")
                .requires(src -> hasPermission(src, "easyvip.use"))
                .executes(EasyVipCommands::executeHelp)
                .then(Commands.argument("key", StringArgumentType.string())
                        .executes(EasyVipCommands::executeUseKey)));

        // /viptime [player] (alias)
        dispatcher.register(Commands.literal("viptime")
                .requires(src -> hasPermission(src, "easyvip.use"))
                .executes(ctx -> executeInfo(ctx, null))
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .requires(src -> hasPermission(src, "easyvip.admin"))
                        .executes(ctx -> executeInfo(ctx, resolveGameProfiles(ctx, "player")))));

        // /easyvip info
        root.then(Commands.literal("info")
                .executes(ctx -> executeInfo(ctx, null))
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .requires(src -> hasPermission(src, "easyvip.admin"))
                        .executes(ctx -> executeInfo(ctx, resolveGameProfiles(ctx, "player")))));

        // /easyvip select <tier>
        root.then(Commands.literal("select")
                .then(Commands.argument("tier", StringArgumentType.word())
                        .suggests(suggestTiers())
                        .executes(EasyVipCommands::executeSelectVip)));

        // /easyvip variant ...
        LiteralArgumentBuilder<CommandSourceStack> variant = Commands.literal("variant")
                .requires(src -> hasPermission(src, "easyvip.use"));

        variant.then(Commands.literal("choose")
                .then(Commands.argument("package", StringArgumentType.word())
                        .suggests(suggestPackages())
                        .then(Commands.argument("variant", StringArgumentType.word())
                                .suggests(suggestVariants())
                                .executes(EasyVipCommands::executeChooseVariant))));

        variant.then(Commands.literal("pending")
                .executes(ctx -> executeVariantPending(ctx, null))
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .requires(src -> hasPermission(src, "easyvip.admin"))
                        .executes(ctx -> executeVariantPending(ctx, resolveGameProfiles(ctx, "player")))));

        variant.then(Commands.literal("clear")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .requires(src -> hasPermission(src, "easyvip.admin"))
                        .executes(ctx -> executeVariantClear(ctx, resolveGameProfiles(ctx, "player"), null))
                        .then(Commands.argument("package_id", StringArgumentType.word())
                                .suggests(suggestPackages())
                                .executes(ctx -> executeVariantClear(
                                        ctx,
                                        resolveGameProfiles(ctx, "player"),
                                        StringArgumentType.getString(ctx, "package_id"))))));

        root.then(variant);

        // ─── Admin Subcommands ──────────────────────────────────
        LiteralArgumentBuilder<CommandSourceStack> admin = Commands.literal("admin")
                .requires(src -> hasPermission(src, "easyvip.admin"));

        // /easyvip admin addvip <player> <tier> <duration>
        admin.then(Commands.literal("addvip")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(Commands.argument("tier", StringArgumentType.word())
                                .suggests(suggestTiers())
                                .then(Commands.argument("duration", StringArgumentType.string())
                                        .executes(EasyVipCommands::executeAddVip)))));

        // /easyvip admin removevip <player> <tier>
        admin.then(Commands.literal("removevip")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(Commands.argument("tier", StringArgumentType.word())
                                .suggests(suggestTiers())
                                .executes(EasyVipCommands::executeRemoveVip))));

        // /easyvip admin generate vip <tier> <duration> [max_uses] [bound_player]
        admin.then(Commands.literal("generate")
                .then(Commands.literal("vip")
                        .then(Commands.argument("tier", StringArgumentType.word())
                                .suggests(suggestTiers())
                                .then(Commands.argument("duration", StringArgumentType.string())
                                        .executes(ctx -> executeGenerateKey(ctx, "vip", null))
                                        .then(Commands.argument("max_uses", IntegerArgumentType.integer(1))
                                                .executes(ctx -> executeGenerateKey(ctx, "vip", null))
                                                .then(Commands.argument("bound_player", GameProfileArgument.gameProfile())
                                                        .executes(ctx -> executeGenerateKey(ctx, "vip", resolveGameProfiles(ctx, "bound_player")))))))));

        // /easyvip admin generate reward <reward_key_id> [max_uses] [bound_player]
        admin.then(Commands.literal("generate")
                .then(Commands.literal("reward")
                        .then(Commands.argument("reward_key_id", StringArgumentType.word())
                                .suggests(suggestRewardKeys())
                                .executes(ctx -> executeGenerateKey(ctx, "reward", null))
                                .then(Commands.argument("max_uses", IntegerArgumentType.integer(1))
                                        .executes(ctx -> executeGenerateKey(ctx, "reward", null))
                                        .then(Commands.argument("bound_player", GameProfileArgument.gameProfile())
                                                .executes(ctx -> executeGenerateKey(ctx, "reward", resolveGameProfiles(ctx, "bound_player"))))))));

        // /easyvip admin generate command <command>
        // /easyvip admin generate command <max_uses> <bound_player_name> <command>
        admin.then(Commands.literal("generate")
                .then(Commands.literal("command")
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                .executes(ctx -> executeGenerateCommandKey(ctx, 1, "none")))
                        .then(Commands.argument("max_uses", IntegerArgumentType.integer(1))
                                .then(Commands.argument("bound_player_name", StringArgumentType.word())
                                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                                .executes(ctx -> executeGenerateCommandKey(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "max_uses"),
                                                        StringArgumentType.getString(ctx, "bound_player_name"))))))));

        // /easyvip admin generate item <item_id> <amount> [max_uses] [bound_player]
        admin.then(Commands.literal("generate")
                .then(Commands.literal("item")
                        .then(Commands.argument("item_id", StringArgumentType.word())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(ctx -> executeGenerateItemKey(ctx, 1, null))
                                        .then(Commands.argument("max_uses", IntegerArgumentType.integer(1))
                                                .executes(ctx -> executeGenerateItemKey(ctx, IntegerArgumentType.getInteger(ctx, "max_uses"), null))
                                                .then(Commands.argument("bound_player", GameProfileArgument.gameProfile())
                                                        .executes(ctx -> executeGenerateItemKey(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "max_uses"),
                                                                resolveGameProfiles(ctx, "bound_player")))))))));

        // /easyvip admin generate itemstack [max_uses] [bound_player]
        admin.then(Commands.literal("generate")
                .then(Commands.literal("itemstack")
                        .executes(ctx -> executeGenerateItemStackKey(ctx, 1, null))
                        .then(Commands.argument("max_uses", IntegerArgumentType.integer(1))
                                .executes(ctx -> executeGenerateItemStackKey(ctx, IntegerArgumentType.getInteger(ctx, "max_uses"), null))
                                .then(Commands.argument("bound_player", GameProfileArgument.gameProfile())
                                        .executes(ctx -> executeGenerateItemStackKey(ctx,
                                                IntegerArgumentType.getInteger(ctx, "max_uses"),
                                                resolveGameProfiles(ctx, "bound_player")))))));

        // /easyvip admin generate custom <actions_json>
        // /easyvip admin generate custom <max_uses> <bound_player_name> <actions_json>
        admin.then(Commands.literal("generate")
                .then(Commands.literal("custom")
                        .then(Commands.argument("actions_json", StringArgumentType.greedyString())
                                .executes(ctx -> executeGenerateCustomKey(ctx, 1, "none")))
                        .then(Commands.argument("max_uses", IntegerArgumentType.integer(1))
                                .then(Commands.argument("bound_player_name", StringArgumentType.word())
                                        .then(Commands.argument("actions_json", StringArgumentType.greedyString())
                                                .executes(ctx -> executeGenerateCustomKey(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "max_uses"),
                                                        StringArgumentType.getString(ctx, "bound_player_name"))))))));

        // /easyvip admin givepackage <player> <package_id>
        admin.then(Commands.literal("givepackage")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(Commands.argument("package_id", StringArgumentType.word())
                                .suggests(suggestPackages())
                                .executes(EasyVipCommands::executeGivePackage))));

        // /easyvip admin giveitemkey <player> <code>
        admin.then(Commands.literal("giveitemkey")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(Commands.argument("code", StringArgumentType.word())
                                .executes(EasyVipCommands::executeGiveItemKey))));

        // /easyvip key ...
        LiteralArgumentBuilder<CommandSourceStack> key = Commands.literal("key")
                .requires(src -> hasPermission(src, "easyvip.admin"));
        key.then(Commands.literal("list").executes(EasyVipCommands::executeKeyList));
        key.then(Commands.literal("info")
                .then(Commands.argument("code", StringArgumentType.word())
                        .executes(EasyVipCommands::executeKeyInfo)
                        .then(Commands.literal("reveal")
                                .executes(ctx -> executeKeyInfo(ctx, true)))));
        key.then(Commands.literal("delete")
                .then(Commands.argument("code", StringArgumentType.word()).executes(EasyVipCommands::executeKeyDelete)));
        admin.then(key);

        // /easyvip package ...
        LiteralArgumentBuilder<CommandSourceStack> packageCmd = Commands.literal("package")
                .requires(src -> hasPermission(src, "easyvip.admin"));
        packageCmd.then(Commands.literal("list").executes(EasyVipCommands::executePackageList));
        packageCmd.then(Commands.literal("info")
                .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(suggestPackages())
                        .executes(EasyVipCommands::executePackageInfo)));
        admin.then(packageCmd);

        // /easyvip admin audit [page]
        admin.then(Commands.literal("audit")
                .executes(ctx -> executeAudit(ctx, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> executeAudit(ctx, IntegerArgumentType.getInteger(ctx, "page")))));

        admin.then(Commands.literal("webstore")
                .then(Commands.literal("status")
                        .executes(EasyVipCommands::executeWebStoreStatus)));

        root.then(admin);

        // ─── Config Subcommands ─────────────────────────────────
        LiteralArgumentBuilder<CommandSourceStack> config = Commands.literal("config")
                .requires(src -> hasPermission(src, "easyvip.admin"));

        // /easyvip config reload
        config.then(Commands.literal("reload")
                .executes(EasyVipCommands::executeConfigReload));

        // /easyvip config validate
        config.then(Commands.literal("validate")
                .executes(EasyVipCommands::executeConfigValidate));

        // /easyvip active set <player> <tier>
        LiteralArgumentBuilder<CommandSourceStack> active = Commands.literal("active")
                .requires(src -> hasPermission(src, "easyvip.admin"));
        active.then(Commands.literal("set")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(Commands.argument("tier", StringArgumentType.word())
                                .executes(EasyVipCommands::executeActiveSet))));
        root.then(active);

        // /easyvip admin savevipactivation <tier>
        admin.then(Commands.literal("savevipactivation")
                .then(Commands.argument("tier", StringArgumentType.word())
                        .executes(EasyVipCommands::executeSaveVipActivation)));

        // /easyvip savevipactivation <tier> (alias)
        root.then(Commands.literal("savevipactivation")
                .requires(src -> hasPermission(src, "easyvip.admin"))
                .then(Commands.argument("tier", StringArgumentType.word())
                        .executes(EasyVipCommands::executeSaveVipActivation)));

        // /easyvip time [player]
        root.then(Commands.literal("time")
                .executes(ctx -> executeInfo(ctx, null))
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .requires(src -> hasPermission(src, "easyvip.admin"))
                        .executes(ctx -> executeInfo(ctx, resolveGameProfiles(ctx, "player")))));

        root.then(config);

        // /link - gera desafio para vincular conta web
        dispatcher.register(Commands.literal("link")
                .requires(src -> hasPermission(src, "easyvip.use"))
                .executes(EasyVipCommands::executeLink));

        dispatcher.register(root);
    }

    private static int executeHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();

        src.sendSuccess(() -> Component.literal("§7[§eEasyVip§7] " + EasyVipConfig.localized("§eAvailable commands:", "§eComandos disponíveis:")), false);
        src.sendSuccess(() -> Component.literal("§7- §f/easyvip use <key> §8- §7" + EasyVipConfig.localized("redeem a key", "resgatar uma chave")), false);
        src.sendSuccess(() -> Component.literal("§7- §f/easyvip confirm §8- §7" + EasyVipConfig.localized("confirm key redemption", "confirmar o uso de uma chave")), false);
        src.sendSuccess(() -> Component.literal("§7- §f/easyvip info [player] §8- §7" + EasyVipConfig.localized("view VIP times", "ver tempos de VIP")), false);
        src.sendSuccess(() -> Component.literal("§7- §f/easyvip select <tier> §8- §7" + EasyVipConfig.localized("set active VIP", "definir VIP ativo")), false);
        src.sendSuccess(() -> Component.literal("§7- §f/easyvip variant choose <package> <variant> §8- §7" + EasyVipConfig.localized("choose a variant", "escolher variante")), false);
        src.sendSuccess(() -> Component.literal("§7- §f/easyvip variant pending [player] §8- §7" + EasyVipConfig.localized("view pending variants", "ver variantes pendentes")), false);
        src.sendSuccess(() -> Component.literal("§7- §f/easyvip time [player] §8- §7" + EasyVipConfig.localized("alias for info", "alias de info")), false);

        if (hasPermission(src, "easyvip.admin")) {
            src.sendSuccess(() -> Component.literal("§7- §f/easyvip admin ... §8- §7" + EasyVipConfig.localized("administrative commands", "comandos administrativos")), false);
            src.sendSuccess(() -> Component.literal("§7- §f/easyvip admin webstore status §8- §7" + EasyVipConfig.localized("fulfillment state", "estado do fulfillment")), false);
            src.sendSuccess(() -> Component.literal("§7- §f/easyvip createvip <id> <display_name> [color] §8- §7" + EasyVipConfig.localized("create a new VIP definition", "criar uma nova definição de VIP")), false);
            src.sendSuccess(() -> Component.literal("§7- §f/easyvip key ... §8- §7" + EasyVipConfig.localized("manage keys", "gerenciar chaves")), false);
            src.sendSuccess(() -> Component.literal("§7- §f/easyvip package ... §8- §7" + EasyVipConfig.localized("manage packages", "gerenciar pacotes")), false);
            src.sendSuccess(() -> Component.literal("§7- §f/easyvip active set <player> <tier> §8- §7" + EasyVipConfig.localized("change active VIP", "alterar VIP ativo")), false);
            src.sendSuccess(() -> Component.literal("§7- §f/easyvip savevipactivation <tier> §8- §7" + EasyVipConfig.localized("save the current inventory as VIP activation items", "salvar o inventário atual como itens de ativação do VIP")), false);
            src.sendSuccess(() -> Component.literal("§7- §f/easyvip reload §8- §7" + EasyVipConfig.localized("reload TOML configs without restarting", "recarregar os TOMLs sem reiniciar")), false);
            src.sendSuccess(() -> Component.literal("§7- §f/easyvip config reload|validate §8- §7" + EasyVipConfig.localized("reload or validate config", "recarregar/validar config")), false);
        }

        return 1;
    }

    private static boolean hasPermission(CommandSourceStack source, String node) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return PermissionBridge.hasPermission(player, node);
        }
        return true; // Console
    }

    private static int executeCreateVip(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String rawId = StringArgumentType.getString(ctx, "id").trim();
        if (rawId.isEmpty()) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("Invalid VIP ID.", "ID de VIP inválido.")));
            return 0;
        }

        String id = rawId.toLowerCase(Locale.ROOT);
        if (EasyVipConfig.tiers.list.containsKey(id)) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("This VIP already exists.", "Este VIP já existe.")));
            return 0;
        }

        String displayName = StringArgumentType.getString(ctx, "display_name").trim();
        if (displayName.isEmpty()) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("Invalid VIP display name.", "Nome de exibição do VIP inválido.")));
            return 0;
        }

        String color = "white";
        try {
            color = StringArgumentType.getString(ctx, "color").trim();
            if (color.isEmpty()) {
                color = "white";
            }
        } catch (IllegalArgumentException ignored) {
        }

        EasyVipConfig.VipTierDefinition def = new EasyVipConfig.VipTierDefinition();
        def.id = id;
        def.displayName = displayName;
        def.color = color;
        def.priority = nextVipPriority();
        def.defaultDuration = EasyVipConfig.tiers.defaults.duration;
        def.allowStacking = EasyVipConfig.tiers.defaults.stacking;
        def.activationMode = EasyVipConfig.tiers.defaults.activationMode;
        def.messages.activated = EasyVipConfig.tiers.defaults.messages.activated;
        def.messages.expired = EasyVipConfig.tiers.defaults.messages.expired;
        def.messages.rareItemBroadcast = EasyVipConfig.tiers.defaults.messages.rareItemBroadcast;
        def.commands.activate = new ArrayList<>(EasyVipConfig.tiers.defaults.commands.activate);
        def.commands.expire = new ArrayList<>(EasyVipConfig.tiers.defaults.commands.expire);

        EasyVipConfig.tiers.list.put(id, def);
        try {
            EasyVipConfig.saveTiers();
        } catch (IOException e) {
            Map<String, String> context = new HashMap<>();
            context.put("error", e.getMessage() != null ? e.getMessage() : "unknown");
            src.sendFailure(Component.literal(
                    ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.reloadError, context)
            ));
            return 0;
        }

        if (PermissionBridge.isLuckPermsPresent() && EasyVipConfig.integrations.luckpermsEnabled) {
            PermissionBridge.createGroup(id);
        }

        Map<String, String> context = new HashMap<>();
        context.put("tier_display", def.displayName);
        context.put("tier_id", def.id);
        src.sendSuccess(() -> Component.literal(
                ActionExecutor.resolvePlaceholders(
                        EasyVipConfig.messages.prefix + EasyVipConfig.localized(
                                "VIP {tier_display} created successfully.",
                                "VIP {tier_display} criado com sucesso."
                        ),
                        context
                )
        ), true);
        return 1;
    }

    private static int nextVipPriority() {
        int max = 0;
        for (EasyVipConfig.VipTierDefinition tier : EasyVipConfig.tiers.list.values()) {
            if (tier != null && tier.priority > max) {
                max = tier.priority;
            }
        }
        return max > 0 ? max + 10 : 10;
    }

    // ─── Player Executions ──────────────────────────────────

    private static int executeUseKey(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal(EasyVipConfig.messages.playerOnly));
            return 0;
        }

        String key = StringArgumentType.getString(ctx, "key");
        KeyService.RedeemResult result = KeyService.redeemKey(player, key, false);

        sendRedeemFeedback(player, result, key);
        return result == KeyService.RedeemResult.SUCCESS ? 1 : 0;
    }

    private static int executeConfirmKey(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal(EasyVipConfig.messages.playerOnly));
            return 0;
        }

        KeyService.RedeemResult result = KeyService.confirmPending(player);
        sendRedeemFeedback(player, result, "");
        return result == KeyService.RedeemResult.SUCCESS ? 1 : 0;
    }

    private static void sendRedeemFeedback(ServerPlayer player, KeyService.RedeemResult result, String key) {
        String msg;
        switch (result) {
            case SUCCESS:
                msg = EasyVipConfig.messages.prefix + EasyVipConfig.messages.keyConfirmed;
                break;
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
                msg = EasyVipConfig.messages.prefix + EasyVipConfig.localized("&cPlease wait a moment before using another key.", "&cAguarde um momento antes de usar outra chave.");
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
                        tierDisplay = (tierDef != null) ? tierDef.displayName : rec.getTierId();
                        duration = rec.getDuration();
                    } else if (rec.getType().equalsIgnoreCase("custom")) {
                        tierDisplay = EasyVipConfig.localized("Custom Reward", "Recompensa Personalizada");
                        duration = EasyVipConfig.localized("one-time use", "uso único");
                    } else {
                        var rkDef = EasyVipConfig.rewardKeys.list.get(rec.getRewardKeyId());
                        tierDisplay = (rkDef != null) ? rkDef.displayName : (rec.getRewardKeyId() != null ? rec.getRewardKeyId() : EasyVipConfig.localized("Reward", "Recompensa"));
                        duration = EasyVipConfig.localized("reward", "recompensa");
                    }
                }
                Map<String, String> context = new HashMap<>();
                context.put("tier_display", tierDisplay);
                context.put("duration", duration);
                msg = EasyVipConfig.messages.prefix + EasyVipConfig.messages.keyConfirmRequired;
                player.sendSystemMessage(Component.literal(ActionExecutor.resolvePlaceholders(msg, context)));
                return;
            }
            default:
                msg = EasyVipConfig.messages.prefix + EasyVipConfig.localized("&cError processing key.", "&cErro ao processar chave.");
                break;
        }
        player.sendSystemMessage(Component.literal(ActionExecutor.resolvePlaceholders(msg, new HashMap<>())));
    }

    private static int executeInfo(CommandContext<CommandSourceStack> ctx, Collection<GameProfile> targets) {
        CommandSourceStack src = ctx.getSource();
        UUID uuid;
        String name;

        if (targets != null && !targets.isEmpty()) {
            GameProfile profile = targets.iterator().next();
            uuid = profile.getId();
            name = profile.getName();
        } else {
            if (!(src.getEntity() instanceof ServerPlayer player)) {
                src.sendFailure(Component.literal(EasyVipConfig.messages.playerOnly));
                return 0;
            }
            uuid = player.getUUID();
            name = player.getGameProfile().getName();
        }

        PlayerVipRegistry registry = PersistenceManager.getPlayerVips(uuid);
        if (registry == null || registry.getVips().isEmpty()) {
            src.sendSuccess(() -> Component.literal("§7[EasyVip] " + name + " " + EasyVipConfig.localized("has no registered VIPs.", "não possui nenhum VIP registrado.")), false);
            return 1;
        }

        src.sendSuccess(() -> Component.literal(ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.vipTimeHeader, new HashMap<>())), false);
        for (PlayerVipRecord record : registry.getVips().values()) {
            EasyVipConfig.VipTierDefinition def = EasyVipConfig.tiers.list.get(record.getTierId());
            String display = (def != null) ? def.displayName : record.getTierId();
            String remaining;

            if (record.isExpired()) {
                remaining = EasyVipConfig.localized("expired", "expirado");
            } else if (record.getExpiryTime() == -1) {
                remaining = EasyVipConfig.localized("permanent", "permanente");
            } else {
                remaining = formatTimeLeft(record.getExpiryTime() - System.currentTimeMillis());
            }

            Map<String, String> context = new HashMap<>();
            context.put("tier_display", display);
            context.put("duration_left", remaining);

            String status = record.isActive()
                    ? " §a[" + EasyVipConfig.localized("Active", "Ativo") + "]"
                    : " §7[" + EasyVipConfig.localized("Inactive", "Inativo") + "]";
            src.sendSuccess(() -> Component.literal(ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.vipTimeLine, context) + status), false);
        }

        return 1;
    }

    private static String formatTimeLeft(long diff) {
        long seconds = diff / 1000L;
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        long days = hours / 24L;

        if (days > 0) {
            return days + "d " + (hours % 24) + "h";
        }
        if (hours > 0) {
            return hours + "h " + (minutes % 60) + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + (seconds % 60) + "s";
        }
        return seconds + "s";
    }

    private static int executeSelectVip(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal(EasyVipConfig.messages.playerOnly));
            return 0;
        }

        String tier = StringArgumentType.getString(ctx, "tier");
        boolean success = VipService.setActiveVip(src.getServer(), player.getUUID(), tier, player.getGameProfile().getName());

        if (success) {
            EasyVipConfig.VipTierDefinition def = EasyVipConfig.tiers.list.get(tier);
            String display = (def != null) ? def.displayName : tier;
            Map<String, String> context = new HashMap<>();
            context.put("tier_display", display);
            player.sendSystemMessage(Component.literal(
                    ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.activeVipChanged, context)
            ));
            return 1;
        } else {
            player.sendSystemMessage(Component.literal(
                    ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.activeVipNotOwned, new HashMap<>())
            ));
            return 0;
        }
    }

    private static int executeChooseVariant(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal(EasyVipConfig.messages.playerOnly));
            return 0;
        }

        String pkg = StringArgumentType.getString(ctx, "package");
        String variant = StringArgumentType.getString(ctx, "variant");

        boolean success = PackageService.chooseVariant(player, pkg, variant);
        return success ? 1 : 0;
    }

    private static int executeVariantPending(CommandContext<CommandSourceStack> ctx, Collection<GameProfile> targets) {
        CommandSourceStack src = ctx.getSource();
        Collection<GameProfile> profiles = targets;
        if (profiles == null) {
            if (!(src.getEntity() instanceof ServerPlayer player)) {
                src.sendFailure(Component.literal(EasyVipConfig.messages.playerOnly));
                return 0;
            }
            profiles = List.of(player.getGameProfile());
        }

        GameProfile profile = profiles.iterator().next();
        PackageService.cleanupExpiredPendingVariants(profile.getId());
        List<PendingVariantSelection> pending = PersistenceManager.getPendingVariants(profile.getId());
        if (pending.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§7[§eEasyVip§7] §7" + EasyVipConfig.localized("No pending variants for ", "Sem variantes pendentes para ") + profile.getName()), false);
            return 1;
        }

        src.sendSuccess(() -> Component.literal("§7[§eEasyVip§7] §e" + EasyVipConfig.localized("Pending variants for ", "Variantes pendentes de ") + profile.getName() + ": " + pending.size()), false);
        for (PendingVariantSelection sel : pending) {
            src.sendSuccess(() -> Component.literal("§7- §f" + sel.getPackageId() + " §8| §7" + String.join(", ", sel.getVariants())), false);
        }
        return 1;
    }

    private static int executeVariantClear(CommandContext<CommandSourceStack> ctx, Collection<GameProfile> targets, String packageId) {
        CommandSourceStack src = ctx.getSource();
        Collection<GameProfile> profiles = targets;
        if (profiles == null || profiles.isEmpty()) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("Player not found.", "Jogador não encontrado.")));
            return 0;
        }

        GameProfile profile = profiles.iterator().next();
        List<PendingVariantSelection> pending = PersistenceManager.getPendingVariants(profile.getId());
        if (pending.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§7[§eEasyVip§7] §7" + EasyVipConfig.localized("No pending variant found.", "Nenhuma variante pendente encontrada.")), false);
            return 1;
        }

        if (packageId == null) {
            for (PendingVariantSelection sel : new ArrayList<>(pending)) {
                PersistenceManager.removePendingVariant(profile.getId(), sel.getPackageId());
            }
        } else {
            PersistenceManager.removePendingVariant(profile.getId(), packageId);
        }

        src.sendSuccess(() -> Component.literal("§a" + EasyVipConfig.localized("Variant pending entries removed successfully.", "Pendências de variante removidas com sucesso.")), true);
        return 1;
    }

    private static int executeActiveSet(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Collection<GameProfile> profiles = resolveGameProfiles(ctx, "player");
        if (profiles.isEmpty()) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("Player not found.", "Jogador não encontrado.")));
            return 0;
        }
        GameProfile profile = profiles.iterator().next();
        String tier = StringArgumentType.getString(ctx, "tier");
        boolean success = VipService.setActiveVip(src.getServer(), profile.getId(), tier, operatorName(src));
        if (success) {
            src.sendSuccess(() -> Component.literal("§a" + EasyVipConfig.localized("Active VIP changed successfully.", "VIP ativo alterado com sucesso.")), true);
            return 1;
        }
        src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("Could not change the active VIP.", "Não foi possível alterar o VIP ativo.")));
        return 0;
    }

    private static int executeSaveVipActivation(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal(EasyVipConfig.messages.playerOnly));
            return 0;
        }

        String tierId = StringArgumentType.getString(ctx, "tier");
        EasyVipConfig.VipTierDefinition tier = EasyVipConfig.tiers.list.get(tierId);
        if (tier == null) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.messages.invalidTier));
            return 0;
        }

        List<EasyVipConfig.VipActivationItemDefinition> items = captureVipActivationItems(player);
        if (items.isEmpty()) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("Your inventory is empty.", "Seu inventário está vazio.")));
            return 0;
        }

        tier.activationItems.clear();
        tier.activationItems.addAll(items);

        try {
            EasyVipConfig.saveActivationItems(tier.id);
        } catch (IOException e) {
            Map<String, String> context = new HashMap<>();
            context.put("error", e.getMessage() != null ? e.getMessage() : "unknown");
            src.sendFailure(Component.literal(
                    ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.reloadError, context)
            ));
            return 0;
        }

        Map<String, String> context = new HashMap<>();
        context.put("tier_display", tier.displayName != null ? tier.displayName : tier.id);
        context.put("tier_id", tier.id);
        context.put("items", String.valueOf(tier.activationItems.size()));
        src.sendSuccess(() -> Component.literal(
                ActionExecutor.resolvePlaceholders(
                        EasyVipConfig.messages.prefix + EasyVipConfig.localized(
                                "Saved {items} item(s) into activation_items/{tier_id}.toml.",
                                "Salvei {items} item(ns) em activation_items/{tier_id}.toml."
                        ),
                        context
                )
        ), true);
        return 1;
    }

    private static List<EasyVipConfig.VipActivationItemDefinition> captureVipActivationItems(ServerPlayer player) {
        List<EasyVipConfig.VipActivationItemDefinition> items = new ArrayList<>();
        captureStacks(player, items, player.getInventory().items);
        captureStacks(player, items, player.getInventory().armor);
        captureStacks(player, items, player.getInventory().offhand);
        return items;
    }

    private static void captureStacks(ServerPlayer player, List<EasyVipConfig.VipActivationItemDefinition> items, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }

            EasyVipConfig.VipActivationItemDefinition item = new EasyVipConfig.VipActivationItemDefinition();
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (itemId != null) {
                item.itemId = itemId.toString();
                item.amount = stack.getCount();
            }

            CompoundTag tag = (CompoundTag) stack.copy().save(player.getServer().registryAccess());
            item.stackSnbt = NbtUtils.structureToSnbt(tag);
            item.chance = 100.0d;
            items.add(item);
        }
    }

    // ─── Admin Executions ───────────────────────────────────

    private static int executeAddVip(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Collection<GameProfile> profiles = resolveGameProfiles(ctx, "player");
        if (profiles.isEmpty()) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("Player not found.", "Jogador não encontrado.")));
            return 0;
        }

        GameProfile profile = profiles.iterator().next();
        String tier = StringArgumentType.getString(ctx, "tier");
        if (!EasyVipConfig.tiers.list.containsKey(tier)) {
            src.sendFailure(Component.literal(EasyVipConfig.messages.prefix + EasyVipConfig.messages.invalidTier));
            return 0;
        }

        String duration = StringArgumentType.getString(ctx, "duration");
        long dur;
        try {
            dur = DurationParser.parseDurationMillis(duration);
        } catch (Exception e) {
            dur = 0;
        }
        if (dur == 0 || (dur < 0 && dur != -1)) {
            src.sendFailure(Component.literal(EasyVipConfig.messages.prefix + EasyVipConfig.messages.invalidDuration));
            return 0;
        }

        String operator = src.getEntity() instanceof ServerPlayer op ? op.getGameProfile().getName() : "Console";

        boolean success = VipService.addVip(src.getServer(), profile.getId(), tier, duration, operator);
        if (success) {
            EasyVipConfig.VipTierDefinition def = EasyVipConfig.tiers.list.get(tier);
            String display = (def != null) ? def.displayName : tier;
            Map<String, String> context = new HashMap<>();
            context.put("tier_display", display);
            context.put("player", profile.getName());
            context.put("duration", duration);

            src.sendSuccess(() -> Component.literal(
                    ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.vipSet, context)
            ), true);
            return 1;
        } else {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized(
                    "Error adding VIP. Check that the tier exists or that stacking rules do not block the operation.",
                    "Erro ao adicionar VIP. Verifique se o tier existe ou se as regras de stacking bloqueiam a operação."
            )));
            return 0;
        }
    }

    private static int executeRemoveVip(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Collection<GameProfile> profiles = resolveGameProfiles(ctx, "player");
        if (profiles.isEmpty()) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("Player not found.", "Jogador não encontrado.")));
            return 0;
        }

        GameProfile profile = profiles.iterator().next();
        String tier = StringArgumentType.getString(ctx, "tier");
        if (!EasyVipConfig.tiers.list.containsKey(tier)) {
            src.sendFailure(Component.literal(EasyVipConfig.messages.prefix + EasyVipConfig.messages.invalidTier));
            return 0;
        }
        String operator = src.getEntity() instanceof ServerPlayer op ? op.getGameProfile().getName() : "Console";

        boolean success = VipService.removeVip(src.getServer(), profile.getId(), tier, operator);
        if (success) {
            EasyVipConfig.VipTierDefinition def = EasyVipConfig.tiers.list.get(tier);
            String display = (def != null) ? def.displayName : tier;
            Map<String, String> context = new HashMap<>();
            context.put("tier_display", display);
            context.put("player", profile.getName());

            src.sendSuccess(() -> Component.literal(
                    ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.vipRemoved, context)
            ), true);
            return 1;
        } else {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("The player does not have this active VIP tier.", "O jogador não possui este tier VIP ativo.")));
            return 0;
        }
    }

    private static int executeGenerateKey(CommandContext<CommandSourceStack> ctx, String type, Collection<GameProfile> targets) {
        CommandSourceStack src = ctx.getSource();
        int maxUses = 1;
        try {
            maxUses = IntegerArgumentType.getInteger(ctx, "max_uses");
        } catch (IllegalArgumentException e) {
            // Optional
        }

        UUID boundUuid = null;
        String boundName = "qualquer um";
        if (targets != null && !targets.isEmpty()) {
            GameProfile profile = targets.iterator().next();
            boundUuid = profile.getId();
            boundName = profile.getName();
        }

        KeyRecord keyRec;
        if (type.equalsIgnoreCase("vip")) {
            String tier = StringArgumentType.getString(ctx, "tier");
            if (!EasyVipConfig.tiers.list.containsKey(tier)) {
                src.sendFailure(Component.literal(EasyVipConfig.messages.prefix + EasyVipConfig.messages.invalidTier));
                return 0;
            }
            String duration = StringArgumentType.getString(ctx, "duration");
            long dur;
            try {
                dur = DurationParser.parseDurationMillis(duration);
            } catch (Exception e) {
                dur = 0;
            }
            if (dur == 0 || (dur < 0 && dur != -1)) {
                src.sendFailure(Component.literal(EasyVipConfig.messages.prefix + EasyVipConfig.messages.invalidDuration));
                return 0;
            }
            keyRec = KeyService.generateVipKey(tier, duration, maxUses, boundUuid, -1, null);
        } else {
            String rkId = StringArgumentType.getString(ctx, "reward_key_id");
            if (!EasyVipConfig.rewardKeys.list.containsKey(rkId)) {
                src.sendFailure(Component.literal(EasyVipConfig.messages.prefix + EasyVipConfig.localized("Reward key not found.", "Chave de recompensa não encontrada.")));
                return 0;
            }
            keyRec = KeyService.generateRewardKey(rkId, maxUses, boundUuid, -1, null);
        }

        String finalBoundName = boundName;
        int finalMaxUses = maxUses;
        src.sendSuccess(() -> Component.literal("§a" + EasyVipConfig.localized("Key generated successfully: ", "Chave gerada com sucesso: ")
                + "§e" + keyRec.getCode()
                + " §a(" + EasyVipConfig.localized("Uses", "Usos") + ": §f" + finalMaxUses
                + "§a, " + EasyVipConfig.localized("Player", "Jogador") + ": §f" + finalBoundName + "§a)"), false);
        return 1;
    }

    private static int executeGenerateCommandKey(CommandContext<CommandSourceStack> ctx, int maxUses, String boundPlayerName) {
        CommandSourceStack src = ctx.getSource();
        String command = StringArgumentType.getString(ctx, "command");

        UUID boundUuid = null;
        String boundName = "qualquer um";
        if (boundPlayerName != null && !boundPlayerName.equalsIgnoreCase("none")) {
            ServerPlayer target = src.getServer().getPlayerList().getPlayerByName(boundPlayerName);
            if (target != null) {
                boundUuid = target.getUUID();
                boundName = target.getGameProfile().getName();
            } else {
                try {
                    Optional<com.mojang.authlib.GameProfile> profile = src.getServer().getProfileCache().get(boundPlayerName);
                    if (profile.isPresent()) {
                        boundUuid = profile.get().getId();
                        boundName = profile.get().getName();
                    } else {
                        boundUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + boundPlayerName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        boundName = boundPlayerName;
                    }
                } catch (Exception e) {
                    boundName = boundPlayerName;
                }
            }
        }

        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "run_server_command");
        action.put("command", command);
        actions.add(action);

        KeyRecord keyRec = KeyService.generateCustomKey(actions, maxUses, boundUuid, -1);

        String finalBoundName = boundName;
        src.sendSuccess(() -> Component.literal("§a" + EasyVipConfig.localized("Command key generated successfully: ", "Chave de comando gerada com sucesso: ")
                + "§e" + keyRec.getCode()
                + " §a(" + EasyVipConfig.localized("Uses", "Usos") + ": §f" + maxUses
                + "§a, " + EasyVipConfig.localized("Player", "Jogador") + ": §f" + finalBoundName + "§a)"), false);
        return 1;
    }

    private static int executeGenerateItemKey(CommandContext<CommandSourceStack> ctx, int maxUses, Collection<GameProfile> targets) {
        CommandSourceStack src = ctx.getSource();
        String itemId = StringArgumentType.getString(ctx, "item_id");
        int amount = IntegerArgumentType.getInteger(ctx, "amount");

        UUID boundUuid = null;
        String boundName = "qualquer um";
        if (targets != null && !targets.isEmpty()) {
            GameProfile profile = targets.iterator().next();
            boundUuid = profile.getId();
            boundName = profile.getName();
        }

        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "give_item");
        action.put("item", itemId);
        action.put("amount", amount);
        actions.add(action);

        KeyRecord keyRec = KeyService.generateCustomKey(actions, maxUses, boundUuid, -1);

        String finalBoundName = boundName;
        src.sendSuccess(() -> Component.literal("§a" + EasyVipConfig.localized("Item key generated successfully: ", "Chave de item gerada com sucesso: ")
                + "§e" + keyRec.getCode()
                + " §a(" + EasyVipConfig.localized("Uses", "Usos") + ": §f" + maxUses
                + "§a, " + EasyVipConfig.localized("Player", "Jogador") + ": §f" + finalBoundName + "§a)"), false);
        return 1;
    }

    private static int executeGenerateItemStackKey(CommandContext<CommandSourceStack> ctx, int maxUses, Collection<GameProfile> targets) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal(EasyVipConfig.messages.playerOnly));
            return 0;
        }

        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("You must hold an item in your main hand.", "Você precisa segurar um item na sua mão principal.")));
            return 0;
        }

        UUID boundUuid = null;
        String boundName = "qualquer um";
        if (targets != null && !targets.isEmpty()) {
            GameProfile profile = targets.iterator().next();
            boundUuid = profile.getId();
            boundName = profile.getName();
        }

        CompoundTag tag = (CompoundTag) stack.copy().save(player.getServer().registryAccess());
        String stackSnbt = NbtUtils.structureToSnbt(tag);

        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "give_item_stack");
        action.put("stack_snbt", stackSnbt);
        actions.add(action);

        KeyRecord keyRec = KeyService.generateCustomKey(actions, maxUses, boundUuid, -1);

        String finalBoundName = boundName;
        src.sendSuccess(() -> Component.literal("§a" + EasyVipConfig.localized("ItemStack key generated successfully: ", "Chave de itemstack gerada com sucesso: ")
                + "§e" + keyRec.getCode()
                + " §a(" + EasyVipConfig.localized("Uses", "Usos") + ": §f" + maxUses
                + "§a, " + EasyVipConfig.localized("Player", "Jogador") + ": §f" + finalBoundName + "§a)"), false);
        return 1;
    }

    private static int executeGenerateCustomKey(CommandContext<CommandSourceStack> ctx, int maxUses, String boundPlayerName) {
        CommandSourceStack src = ctx.getSource();
        String json = StringArgumentType.getString(ctx, "actions_json");

        UUID boundUuid = null;
        String boundName = "qualquer um";
        if (boundPlayerName != null && !boundPlayerName.equalsIgnoreCase("none")) {
            ServerPlayer target = src.getServer().getPlayerList().getPlayerByName(boundPlayerName);
            if (target != null) {
                boundUuid = target.getUUID();
                boundName = target.getGameProfile().getName();
            } else {
                try {
                    Optional<com.mojang.authlib.GameProfile> profile = src.getServer().getProfileCache().get(boundPlayerName);
                    if (profile.isPresent()) {
                        boundUuid = profile.get().getId();
                        boundName = profile.get().getName();
                    } else {
                        boundUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + boundPlayerName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        boundName = boundPlayerName;
                    }
                } catch (Exception e) {
                    boundName = boundPlayerName;
                }
            }
        }

        List<Map<String, Object>> actions;
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<List<Map<String, Object>>>(){}.getType();
            actions = gson.fromJson(json, type);
            if (actions == null || actions.isEmpty()) {
                src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("The actions JSON list cannot be empty.", "A lista de ações em JSON não pode ser vazia.")));
                return 0;
            }
        } catch (Exception e) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("Invalid JSON format for actions: ", "Formato de JSON inválido para as ações: ") + e.getMessage()));
            return 0;
        }

        KeyRecord keyRec = KeyService.generateCustomKey(actions, maxUses, boundUuid, -1);

        String finalBoundName = boundName;
        src.sendSuccess(() -> Component.literal("§a" + EasyVipConfig.localized("Custom action key generated successfully: ", "Chave de ações personalizadas gerada com sucesso: ")
                + "§e" + keyRec.getCode()
                + " §a(" + EasyVipConfig.localized("Uses", "Usos") + ": §f" + maxUses
                + "§a, " + EasyVipConfig.localized("Player", "Jogador") + ": §f" + finalBoundName + "§a)"), false);
        return 1;
    }

    private static int executeGivePackage(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Collection<GameProfile> profiles = resolveGameProfiles(ctx, "player");
        if (profiles.isEmpty()) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("Player not found.", "Jogador não encontrado.")));
            return 0;
        }

        GameProfile profile = profiles.iterator().next();
        ServerPlayer player = src.getServer().getPlayerList().getPlayer(profile.getId());
        if (player == null) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("The player must be online to receive the package.", "O jogador precisa estar online para receber o pacote.")));
            return 0;
        }

        String packageId = StringArgumentType.getString(ctx, "package_id");
        if (!EasyVipConfig.packages.list.containsKey(packageId)) {
            src.sendFailure(Component.literal(EasyVipConfig.messages.prefix + EasyVipConfig.messages.packageNotFound));
            return 0;
        }
        boolean success = PackageService.givePackage(player, packageId);
        return success ? 1 : 0;
    }

    private static int executeGiveItemKey(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Collection<GameProfile> profiles = resolveGameProfiles(ctx, "player");
        if (profiles.isEmpty()) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("Player not found.", "Jogador não encontrado.")));
            return 0;
        }

        GameProfile profile = profiles.iterator().next();
        ServerPlayer player = src.getServer().getPlayerList().getPlayer(profile.getId());
        if (player == null) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("The player must be online to receive the item.", "O jogador precisa estar online para receber o item.")));
            return 0;
        }

        String code = StringArgumentType.getString(ctx, "code");
        KeyRecord record = PersistenceManager.getKey(code.trim().toUpperCase());
        if (record == null && !EasyVipConfig.common.caseSensitiveKeys) {
            record = PersistenceManager.getKey(code.trim());
        }
        if (record == null) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("Key not found.", "Chave não encontrada.")));
            return 0;
        }

        player.getInventory().add(KeyService.createPhysicalKeyItem(record.getCode()));
        src.sendSuccess(() -> Component.literal("§a" + EasyVipConfig.localized("Key item delivered successfully.", "Item de chave entregue com segurança.")), true);
        return 1;
    }

    private static int executeKeyList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        List<KeyRecord> keys = PersistenceManager.getAllKeys();
        src.sendSuccess(() -> Component.literal("§7[§eEasyVip§7] §e" + EasyVipConfig.localized("Registered keys: ", "Keys cadastradas: ") + "§f" + keys.size()), false);
        for (KeyRecord key : keys) {
            String displayCode = br.com.pedrodalben.easyvip.util.KeySecurity.maskKey(key.getCode());
            src.sendSuccess(() -> Component.literal("§7- §f" + displayCode + " §8| §e" + key.getType() + " §8| §7" + EasyVipConfig.localized("uses", "usos") + " " + key.getUsedCount() + "/" + key.getMaxUses()), false);
        }
        return 1;
    }

    private static int executeKeyInfo(CommandContext<CommandSourceStack> ctx) {
        return executeKeyInfo(ctx, false);
    }

    private static int executeKeyInfo(CommandContext<CommandSourceStack> ctx, boolean reveal) {
        CommandSourceStack src = ctx.getSource();
        String code = StringArgumentType.getString(ctx, "code").trim();
        KeyRecord key = PersistenceManager.getKey(code);
        if (key == null && !EasyVipConfig.common.caseSensitiveKeys) {
            key = PersistenceManager.getKey(code.toUpperCase(Locale.ROOT));
        }
        if (key == null) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("Key not found.", "Chave não encontrada.")));
            return 0;
        }
        KeyRecord finalKey = key;

        String displayCode = br.com.pedrodalben.easyvip.util.KeySecurity.maskKey(finalKey.getCode());
        if (reveal) {
            String opName = operatorName(src);
            PersistenceManager.log(opName, "key_info_reveal",
                    "Key info requested for " + br.com.pedrodalben.easyvip.util.KeySecurity.describeKeyForLog(finalKey.getCode()));
        }

        src.sendSuccess(() -> Component.literal("§7[§eEasyVip§7] §a" + displayCode
                + " §8| §7" + br.com.pedrodalben.easyvip.util.KeySecurity.describeKeyForLog(finalKey.getCode())
                + " §8| §f" + finalKey.getType()
                + " §8| §f" + EasyVipConfig.localized("used", "usado") + " " + finalKey.getUsedCount() + "/" + finalKey.getMaxUses()), false);
        return 1;
    }

    private static int executeKeyDelete(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String code = StringArgumentType.getString(ctx, "code").trim();
        KeyRecord key = PersistenceManager.getKey(code);
        if (key == null && !EasyVipConfig.common.caseSensitiveKeys) {
            key = PersistenceManager.getKey(code.toUpperCase());
        }
        if (key == null) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("Key not found.", "Chave não encontrada.")));
            return 0;
        }
        PersistenceManager.removeKey(key.getCode());
        src.sendSuccess(() -> Component.literal("§a" + EasyVipConfig.localized("Key removed.", "Chave removida.")), true);
        return 1;
    }

    private static int executePackageList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("§7[§eEasyVip§7] §e" + EasyVipConfig.localized("Registered packages: ", "Pacotes cadastrados: ") + "§f" + EasyVipConfig.packages.list.size()), false);
        for (EasyVipConfig.PackageDefinition pkg : EasyVipConfig.packages.list.values()) {
            src.sendSuccess(() -> Component.literal("§7- §f" + pkg.id + " §8| §e" + pkg.displayName), false);
        }
        return 1;
    }

    private static int executePackageInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String id = StringArgumentType.getString(ctx, "id");
        EasyVipConfig.PackageDefinition pkg = EasyVipConfig.packages.list.get(id);
        if (pkg == null) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized("Package not found.", "Pacote não encontrado.")));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§7[§eEasyVip§7] §a" + pkg.id + " §8| §f" + pkg.displayName
                + " §8| §7" + EasyVipConfig.localized("variants", "variantes") + " " + pkg.variants.size()), false);
        return 1;
    }

    private static int executeAudit(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSourceStack src = ctx.getSource();
        List<AuditLogRecord> logs = PersistenceManager.getAuditLogs();
        if (logs.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§7[§eEasyVip§7] §7" + EasyVipConfig.localized("No audit log entries found.", "Nenhum log de auditoria encontrado.")), false);
            return 1;
        }

        int perPage = 10;
        List<AuditLogRecord> ordered = new ArrayList<>(logs);
        Collections.reverse(ordered);

        int totalPages = Math.max(1, (int) Math.ceil(ordered.size() / (double) perPage));
        int currentPage = Math.min(page, totalPages);
        int fromIndex = (currentPage - 1) * perPage;
        int toIndex = Math.min(fromIndex + perPage, ordered.size());

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault());

        src.sendSuccess(() -> Component.literal(
                "§7[§eEasyVip§7] §e" + EasyVipConfig.localized("Audit log", "Audit log") + " §7(" + EasyVipConfig.localized("page", "página") + " " + currentPage + "/" + totalPages + ", " + ordered.size() + " " + EasyVipConfig.localized("entries", "entradas") + ")"
        ), false);

        for (int i = fromIndex; i < toIndex; i++) {
            AuditLogRecord record = ordered.get(i);
            String timestamp = formatter.format(Instant.ofEpochMilli(record.getTimestamp()));
            String details = br.com.pedrodalben.easyvip.util.KeySecurity.sanitizeAuditDetails(record.getDetails());
            if (details == null) {
                details = "";
            }
            String line = String.format(
                    "§7- §f%s §8| §e%s §8| §a%s §8| §7%s",
                    timestamp,
                    record.getOperator(),
                    record.getAction(),
                    details
            );
            src.sendSuccess(() -> Component.literal(line), false);
        }

        return 1;
    }

    private static int executeWebStoreStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String status = WebStoreFulfillmentService.statusSummary();
        src.sendSuccess(() -> Component.literal("§7[§eEasyVip§7] §e" + EasyVipConfig.localized("WebStore fulfillment status:", "Status do fulfillment da WebStore:") + " §f" + status), false);
        return 1;
    }

    private static String operatorName(CommandSourceStack src) {
        if (src.getEntity() instanceof ServerPlayer op) {
            return op.getGameProfile().getName();
        }
        return "Console";
    }

    private static Collection<GameProfile> resolveGameProfiles(CommandContext<CommandSourceStack> ctx, String argumentName) {
        try {
            return GameProfileArgument.getGameProfiles(ctx, argumentName);
        } catch (CommandSyntaxException e) {
            ctx.getSource().sendFailure(Component.literal("§c" + EasyVipConfig.localized("Player not found.", "Jogador não encontrado.")));
            return Collections.emptyList();
        }
    }

    private static SuggestionProvider<CommandSourceStack> suggestTiers() {
        return (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String tierId : EasyVipConfig.tiers.list.keySet()) {
                if (tierId.toLowerCase().startsWith(remaining)) {
                    builder.suggest(tierId);
                }
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> suggestPackages() {
        return (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String pkgId : EasyVipConfig.packages.list.keySet()) {
                if (pkgId.toLowerCase().startsWith(remaining)) {
                    builder.suggest(pkgId);
                }
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> suggestRewardKeys() {
        return (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String keyId : EasyVipConfig.rewardKeys.list.keySet()) {
                if (keyId.toLowerCase().startsWith(remaining)) {
                    builder.suggest(keyId);
                }
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> suggestVariants() {
        return (ctx, builder) -> {
            try {
                String pkgId = StringArgumentType.getString(ctx, "package");
                EasyVipConfig.PackageDefinition pkg = EasyVipConfig.packages.list.get(pkgId);
                if (pkg != null && pkg.variants != null) {
                    String remaining = builder.getRemaining().toLowerCase();
                    for (String variant : pkg.variants.keySet()) {
                        if (variant.toLowerCase().startsWith(remaining)) {
                            builder.suggest(variant);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            return builder.buildFuture();
        };
    }

    // ─── Config Executions ──────────────────────────────────

    private static int executeConfigReload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        try {
            EasyVipConfig.loadAll();
            var server = src.getServer();
            if (server != null) {
                ExpirationService.reload(server);
                java.nio.file.Path configDir = server.getServerDirectory().resolve("config").resolve("easyvip");
                WebStoreFulfillmentService.reload(configDir);
            }
            src.sendSuccess(() -> Component.literal(
                    ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.reloadSuccess, new HashMap<>())
            ), true);
            return 1;
        } catch (Exception e) {
            Map<String, String> context = new HashMap<>();
            context.put("error", e.getMessage());
            src.sendFailure(Component.literal(
                    ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.prefix + EasyVipConfig.messages.reloadError, context)
            ));
            return 0;
        }
    }

    private static int executeConfigValidate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        List<String> errors = EasyVipConfig.validate();
        if (errors.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§7[§eEasyVip§7] §a" + EasyVipConfig.localized("All configuration settings are valid!", "Todas as configurações são válidas!")), true);
            return 1;
        } else {
            src.sendFailure(Component.literal("§7[§eEasyVip§7] §c" + EasyVipConfig.localized("Configuration errors found:", "Erros de configuração encontrados:")));
            for (String error : errors) {
                src.sendFailure(Component.literal("§7- §c" + error));
            }
            return 0;
        }
    }

    private static int executeLink(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            src.sendFailure(Component.literal(EasyVipConfig.messages.playerOnly));
            return 0;
        }

        if (!WebStoreSyncService.isEnabled()) {
            src.sendFailure(Component.literal("§c" + EasyVipConfig.localized(
                    "Web store integration is not configured. Contact an administrator.",
                    "A integração com a loja web não está configurada. Contate um administrador."
            )));
            return 0;
        }

        String code = generateLinkCode();
        String codeDigest = WebStoreSyncService.sha256(code);

        WebStoreSyncService.registerChallenge(player.getUUID(), code);

        player.sendSystemMessage(Component.literal("§7[§eEasyVip§7] §e" + EasyVipConfig.localized(
                "Link your account on the web store using this code:",
                "Vincule sua conta na loja web usando este código:"
        )));
        player.sendSystemMessage(Component.literal("§6§l" + code));
        player.sendSystemMessage(Component.literal("§7" + EasyVipConfig.localized(
                "The code expires in 5 minutes.",
                "O código expira em 5 minutos."
        )));
        return 1;
    }

    private static String generateLinkCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(8);
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
