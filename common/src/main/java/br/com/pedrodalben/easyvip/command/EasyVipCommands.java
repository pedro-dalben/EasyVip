package br.com.pedrodalben.easyvip.command;

import br.com.pedrodalben.easyvip.action.ActionExecutor;
import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.model.*;
import br.com.pedrodalben.easyvip.persistence.PersistenceManager;
import br.com.pedrodalben.easyvip.platform.PermissionBridge;
import br.com.pedrodalben.easyvip.service.*;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class EasyVipCommands {

    private EasyVipCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("easyvip")
                .requires(src -> hasPermission(src, "easyvip.use"));

        // ─── Player Subcommands ─────────────────────────────────

        // /easyvip use <key>
        root.then(Commands.literal("use")
                .then(Commands.argument("key", StringArgumentType.string())
                        .executes(EasyVipCommands::executeUseKey)));

        // /easyvip confirm
        root.then(Commands.literal("confirm")
                .executes(EasyVipCommands::executeConfirmKey));

        // /easyvip info
        root.then(Commands.literal("info")
                .executes(ctx -> executeInfo(ctx, null))
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .requires(src -> hasPermission(src, "easyvip.admin"))
                        .executes(ctx -> executeInfo(ctx, resolveGameProfiles(ctx, "player")))));

        // /easyvip select <tier>
        root.then(Commands.literal("select")
                .then(Commands.argument("tier", StringArgumentType.word())
                        .executes(EasyVipCommands::executeSelectVip)));

        // /easyvip variant choose <package> <variant>
        root.then(Commands.literal("variant")
                .then(Commands.literal("choose")
                        .then(Commands.argument("package", StringArgumentType.word())
                                .then(Commands.argument("variant", StringArgumentType.word())
                                        .executes(EasyVipCommands::executeChooseVariant)))));

        // ─── Admin Subcommands ──────────────────────────────────
        LiteralArgumentBuilder<CommandSourceStack> admin = Commands.literal("admin")
                .requires(src -> hasPermission(src, "easyvip.admin"));

        // /easyvip admin addvip <player> <tier> <duration>
        admin.then(Commands.literal("addvip")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(Commands.argument("tier", StringArgumentType.word())
                                .then(Commands.argument("duration", StringArgumentType.string())
                                        .executes(EasyVipCommands::executeAddVip)))));

        // /easyvip admin removevip <player> <tier>
        admin.then(Commands.literal("removevip")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(Commands.argument("tier", StringArgumentType.word())
                                .executes(EasyVipCommands::executeRemoveVip))));

        // /easyvip admin generate vip <tier> <duration> [max_uses] [bound_player]
        admin.then(Commands.literal("generate")
                .then(Commands.literal("vip")
                        .then(Commands.argument("tier", StringArgumentType.word())
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
                                .executes(ctx -> executeGenerateKey(ctx, "reward", null))
                                .then(Commands.argument("max_uses", IntegerArgumentType.integer(1))
                                        .executes(ctx -> executeGenerateKey(ctx, "reward", null))
                                        .then(Commands.argument("bound_player", GameProfileArgument.gameProfile())
                                                .executes(ctx -> executeGenerateKey(ctx, "reward", resolveGameProfiles(ctx, "bound_player"))))))));

        // /easyvip admin givepackage <player> <package_id>
        admin.then(Commands.literal("givepackage")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(Commands.argument("package_id", StringArgumentType.word())
                                .executes(EasyVipCommands::executeGivePackage))));

        // /easyvip admin audit [page]
        admin.then(Commands.literal("audit")
                .executes(ctx -> executeAudit(ctx, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> executeAudit(ctx, IntegerArgumentType.getInteger(ctx, "page")))));

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

        root.then(config);

        dispatcher.register(root);
    }

    private static boolean hasPermission(CommandSourceStack source, String node) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return PermissionBridge.hasPermission(player, node);
        }
        return true; // Console
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
                    } else {
                        var rkDef = EasyVipConfig.rewardKeys.list.get(rec.getRewardKeyId());
                        tierDisplay = (rkDef != null) ? rkDef.displayName : rec.getRewardKeyId();
                        duration = "recompensa";
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
                msg = EasyVipConfig.messages.prefix + "&cErro ao processar chave.";
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
            src.sendSuccess(() -> Component.literal("§7[EasyVip] " + name + " não possui nenhum VIP registrado."), false);
            return 1;
        }

        src.sendSuccess(() -> Component.literal(ActionExecutor.resolvePlaceholders(EasyVipConfig.messages.vipTimeHeader, new HashMap<>())), false);
        for (PlayerVipRecord record : registry.getVips().values()) {
            EasyVipConfig.VipTierDefinition def = EasyVipConfig.tiers.list.get(record.getTierId());
            String display = (def != null) ? def.displayName : record.getTierId();
            String remaining;

            if (record.isExpired()) {
                remaining = "expirado";
            } else if (record.getExpiryTime() == -1) {
                remaining = "permanente";
            } else {
                remaining = formatTimeLeft(record.getExpiryTime() - System.currentTimeMillis());
            }

            Map<String, String> context = new HashMap<>();
            context.put("tier_display", display);
            context.put("duration_left", remaining);

            String status = record.isActive() ? " §a[Ativo]" : " §7[Inativo]";
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

    // ─── Admin Executions ───────────────────────────────────

    private static int executeAddVip(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Collection<GameProfile> profiles = resolveGameProfiles(ctx, "player");
        if (profiles.isEmpty()) {
            src.sendFailure(Component.literal("§cJogador não encontrado."));
            return 0;
        }

        GameProfile profile = profiles.iterator().next();
        String tier = StringArgumentType.getString(ctx, "tier");
        String duration = StringArgumentType.getString(ctx, "duration");

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
            src.sendFailure(Component.literal("§cErro ao adicionar VIP. Verifique se o tier existe ou se as regras de stacking bloqueiam a operação."));
            return 0;
        }
    }

    private static int executeRemoveVip(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Collection<GameProfile> profiles = resolveGameProfiles(ctx, "player");
        if (profiles.isEmpty()) {
            src.sendFailure(Component.literal("§cJogador não encontrado."));
            return 0;
        }

        GameProfile profile = profiles.iterator().next();
        String tier = StringArgumentType.getString(ctx, "tier");
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
            src.sendFailure(Component.literal("§cO jogador não possui este tier VIP ativo."));
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
            String duration = StringArgumentType.getString(ctx, "duration");
            keyRec = KeyService.generateVipKey(tier, duration, maxUses, boundUuid, -1, null);
        } else {
            String rkId = StringArgumentType.getString(ctx, "reward_key_id");
            keyRec = KeyService.generateRewardKey(rkId, maxUses, boundUuid, -1, null);
        }

        String finalBoundName = boundName;
        int finalMaxUses = maxUses;
        src.sendSuccess(() -> Component.literal("§aChave gerada com sucesso: §e" + keyRec.getCode() 
                + " §a(Usos: §f" + finalMaxUses + "§a, Jogador: §f" + finalBoundName + "§a)"), true);
        return 1;
    }

    private static int executeGivePackage(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Collection<GameProfile> profiles = resolveGameProfiles(ctx, "player");
        if (profiles.isEmpty()) {
            src.sendFailure(Component.literal("§cJogador não encontrado."));
            return 0;
        }

        GameProfile profile = profiles.iterator().next();
        ServerPlayer player = src.getServer().getPlayerList().getPlayer(profile.getId());
        if (player == null) {
            src.sendFailure(Component.literal("§cO jogador precisa estar online para receber o pacote."));
            return 0;
        }

        String packageId = StringArgumentType.getString(ctx, "package_id");
        boolean success = PackageService.givePackage(player, packageId);
        return success ? 1 : 0;
    }

    private static int executeAudit(CommandContext<CommandSourceStack> ctx, int page) {
        CommandSourceStack src = ctx.getSource();
        List<AuditLogRecord> logs = PersistenceManager.getAuditLogs();
        if (logs.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§7[§eEasyVip§7] §7Nenhum log de auditoria encontrado."), false);
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
                "§7[§eEasyVip§7] §eAudit log §7(página " + currentPage + "/" + totalPages + ", " + ordered.size() + " entradas)"
        ), false);

        for (int i = fromIndex; i < toIndex; i++) {
            AuditLogRecord record = ordered.get(i);
            String timestamp = formatter.format(Instant.ofEpochMilli(record.getTimestamp()));
            String details = record.getDetails() != null ? record.getDetails() : "";
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

    private static Collection<GameProfile> resolveGameProfiles(CommandContext<CommandSourceStack> ctx, String argumentName) {
        try {
            return GameProfileArgument.getGameProfiles(ctx, argumentName);
        } catch (CommandSyntaxException e) {
            ctx.getSource().sendFailure(Component.literal("§cJogador não encontrado."));
            return Collections.emptyList();
        }
    }

    // ─── Config Executions ──────────────────────────────────

    private static int executeConfigReload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        try {
            EasyVipConfig.loadAll();
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
            src.sendSuccess(() -> Component.literal("§7[§eEasyVip§7] §aTodas as configurações são válidas!"), true);
            return 1;
        } else {
            src.sendFailure(Component.literal("§7[§eEasyVip§7] §cErros de configuração encontrados:"));
            for (String error : errors) {
                src.sendFailure(Component.literal("§7- §c" + error));
            }
            return 0;
        }
    }
}
