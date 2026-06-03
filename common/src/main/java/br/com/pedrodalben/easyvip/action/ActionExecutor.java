package br.com.pedrodalben.easyvip.action;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.platform.PlatformBridge;
import br.com.pedrodalben.easyvip.platform.PermissionBridge;
import br.com.pedrodalben.easyvip.service.PackageService;
import br.com.pedrodalben.easyvip.util.CommandAllowlist;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;

public final class ActionExecutor {

    private static PlatformBridge platform;

    private ActionExecutor() {
    }

    public static void setPlatform(PlatformBridge bridge) {
        platform = bridge;
    }

    public static boolean execute(ServerPlayer player, List<Map<String, Object>> actions, Map<String, String> context) {
        return execute(ActionContext.online(player, "server_player"), actions, context);
    }

    public static boolean execute(ActionContext actionContext, List<Map<String, Object>> actions, Map<String, String> context) {
        if (actions == null || actions.isEmpty()) {
            return true;
        }

        Map<String, String> fullContext = new HashMap<>(context);
        fullContext.put("player", actionContext.getPlayerName());
        fullContext.put("player_uuid", actionContext.getPlayerUuid().toString());

        boolean allOk = true;
        for (Map<String, Object> action : actions) {
            try {
                if (!executeSingle(actionContext, action, fullContext)) {
                    allOk = false;
                }
            } catch (Throwable e) {
                System.err.println("[EasyVip] Error executing action of type " + action.get("type") + ": " + e.getMessage());
                if (EasyVipConfig.common.debug) {
                    e.printStackTrace();
                }
                allOk = false;
            }
        }
        return allOk;
    }

    private static boolean executeSingle(ActionContext ctx, Map<String, Object> action, Map<String, String> context) {
        String type = getString(action, "type", "");
        if (type.isEmpty()) return false;

        ServerPlayer player = ctx.getOnlinePlayer();
        MinecraftServer server = ctx.getServer();

        switch (type.toLowerCase()) {
            case "give_item": {
                if (player == null) {
                    return false;
                }
                String itemStr = getString(action, "item", "");
                int amount = getInt(action, "amount", 1);
                if (!itemStr.isEmpty()) {
                    ResourceLocation res = ResourceLocation.tryParse(itemStr);
                    if (res != null) {
                        Item item = BuiltInRegistries.ITEM.get(res);
                        if (item != null) {
                            ItemStack stack = new ItemStack(item, amount);
                            player.getInventory().add(stack);
                            return true;
                        }
                    }
                }
                break;
            }
            case "give_item_stack": {
                if (player == null || server == null) {
                    return false;
                }
                String stackSnbt = getString(action, "stack_snbt", getString(action, "stack", ""));
                if (!stackSnbt.isEmpty()) {
                    try {
                        CompoundTag tag = NbtUtils.snbtToStructure(stackSnbt);
                        ItemStack stack = ItemStack.parseOptional(server.registryAccess(), tag);
                        if (!stack.isEmpty()) {
                            player.getInventory().add(stack);
                            return true;
                        }
                    } catch (Exception e) {
                        if (EasyVipConfig.common.debug) {
                            e.printStackTrace();
                        }
                    }
                }
                break;
            }
            case "give_experience": {
                if (player == null) {
                    return false;
                }
                int amount = getInt(action, "amount", 0);
                if (amount > 0) {
                    player.giveExperiencePoints(amount);
                    return true;
                }
                break;
            }
            case "give_level": {
                if (player == null) {
                    return false;
                }
                int amount = getInt(action, "amount", 0);
                if (amount > 0) {
                    player.giveExperienceLevels(amount);
                    return true;
                }
                break;
            }
            case "give_effect": {
                if (player == null) {
                    return false;
                }
                String effectStr = getString(action, "effect", "");
                int durationSeconds = getInt(action, "duration", 30);
                int amplifier = getInt(action, "amplifier", 0);
                if (!effectStr.isEmpty()) {
                    ResourceLocation res = ResourceLocation.tryParse(effectStr);
                    if (res != null) {
                        Optional<Holder.Reference<MobEffect>> opt = BuiltInRegistries.MOB_EFFECT.getHolder(res);
                        if (opt.isPresent()) {
                            player.addEffect(new MobEffectInstance(opt.get(), durationSeconds * 20, amplifier));
                            return true;
                        }
                    }
                }
                break;
            }
            case "send_message": {
                String message = getString(action, "message", "");
                if (!message.isEmpty()) {
                    if (player != null) {
                        player.sendSystemMessage(Component.literal(resolvePlaceholders(message, context)));
                    }
                    return true;
                }
                break;
            }
            case "broadcast_message": {
                String message = getString(action, "message", "");
                if (!message.isEmpty() && server != null) {
                    server.getPlayerList().broadcastSystemMessage(
                            Component.literal(resolvePlaceholders(message, context)),
                            false
                    );
                    return true;
                }
                break;
            }
            case "run_server_command": {
                String command = getString(action, "command", "");
                if (!command.isEmpty()) {
                    String cmd = resolvePlaceholders(command, context);
                    return executeServerCommand(server, cmd);
                }
                break;
            }
            case "run_player_command": {
                if (player == null) {
                    return false;
                }
                String command = getString(action, "command", "");
                if (!command.isEmpty()) {
                    String cmd = resolvePlaceholders(command, context);
                    if (server != null) {
                        server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), cmd);
                        return true;
                    }
                }
                break;
            }
            case "give_package": {
                String pkgId = getString(action, "package_id", "");
                if (!pkgId.isEmpty() && player != null) {
                    return PackageService.givePackage(player, pkgId);
                }
                break;
            }
            case "set_scoreboard_tag": {
                if (player == null) {
                    return false;
                }
                String tag = getString(action, "tag", "");
                if (!tag.isEmpty()) {
                    player.addTag(tag);
                    return true;
                }
                break;
            }
            case "remove_scoreboard_tag": {
                if (player == null) {
                    return false;
                }
                String tag = getString(action, "tag", "");
                if (!tag.isEmpty()) {
                    player.removeTag(tag);
                    return true;
                }
                break;
            }
            case "add_to_team": {
                if (player == null) {
                    return false;
                }
                String teamName = getString(action, "team", "");
                if (!teamName.isEmpty() && server != null) {
                    var scoreboard = server.getScoreboard();
                    var team = scoreboard.getPlayerTeam(teamName);
                    if (team != null) {
                        scoreboard.addPlayerToTeam(player.getGameProfile().getName(), team);
                        return true;
                    }
                }
                break;
            }
            case "remove_from_team": {
                if (player == null) {
                    return false;
                }
                String teamName = getString(action, "team", "");
                if (!teamName.isEmpty() && server != null) {
                    var scoreboard = server.getScoreboard();
                    var team = scoreboard.getPlayerTeam(teamName);
                    if (team != null) {
                        scoreboard.removePlayerFromTeam(player.getGameProfile().getName(), team);
                        return true;
                    }
                }
                break;
            }
            case "give_permission_flag_internal": {
                if (player == null) {
                    return false;
                }
                String perm = getString(action, "permission", "");
                if (!perm.isEmpty() && platform != null) {
                    platform.setPermissionFlagInternal(player, perm, true);
                    return true;
                }
                break;
            }
            case "remove_permission_flag_internal": {
                if (player == null) {
                    return false;
                }
                String perm = getString(action, "permission", "");
                if (!perm.isEmpty() && platform != null) {
                    platform.setPermissionFlagInternal(player, perm, false);
                    return true;
                }
                break;
            }
            case "custom_event_hook": {
                if (player == null) {
                    return false;
                }
                String hook = getString(action, "hook", "");
                if (!hook.isEmpty() && platform != null) {
                    platform.fireCustomEventHook(player, hook, context);
                    return true;
                }
                break;
            }
            case "run_ftb_rank_command": {
                String command = getString(action, "command", "");
                if (!command.isEmpty()) {
                    String cmd = resolvePlaceholders(command, context);
                    return executeServerCommand(server, cmd);
                }
                break;
            }
            case "add_ftb_rank": {
                String rank = getString(action, "rank", "");
                if (!rank.isEmpty()) {
                    String cmd = renderFtbRankCommand(EasyVipConfig.integrations.ftbRanksAddCommand, context, rank);
                    return executeFtbRankCommand(server, cmd);
                }
                break;
            }
            case "remove_ftb_rank": {
                String rank = getString(action, "rank", "");
                if (!rank.isEmpty()) {
                    String cmd = renderFtbRankCommand(EasyVipConfig.integrations.ftbRanksRemoveCommand, context, rank);
                    return executeFtbRankCommand(server, cmd);
                }
                break;
            }
            case "set_ftb_rank": {
                String rank = getString(action, "rank", "");
                if (!rank.isEmpty()) {
                    String cmd = renderFtbRankCommand(EasyVipConfig.integrations.ftbRanksSetCommand, context, rank);
                    return executeFtbRankCommand(server, cmd);
                }
                break;
            }
            case "add_luckperms_group": {
                String group = getString(action, "group", "");
                if (!group.isEmpty()) {
                    if (player != null) {
                        PermissionBridge.setGroup(player, group, true);
                    } else if (server != null) {
                        String playerName = context.get("player");
                        if (playerName != null && !playerName.isEmpty()) {
                            executeServerCommand(server, "lp user " + playerName + " parent add " + group);
                        }
                    }
                    return true;
                }
                break;
            }
            case "remove_luckperms_group": {
                String group = getString(action, "group", "");
                if (!group.isEmpty()) {
                    if (player != null) {
                        PermissionBridge.setGroup(player, group, false);
                    } else if (server != null) {
                        String playerName = context.get("player");
                        if (playerName != null && !playerName.isEmpty()) {
                            executeServerCommand(server, "lp user " + playerName + " parent remove " + group);
                        }
                    }
                    return true;
                }
                break;
            }
        }
        return false;
    }

    public static String resolvePlaceholders(String text, Map<String, String> context) {
        if (text == null) return "";
        String result = text;
        for (Map.Entry<String, String> entry : context.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result.replace('&', '§');
    }

    public static boolean isCommandAllowed(String command) {
        return CommandAllowlist.isAllowed(command, EasyVipConfig.common.commandAllowlistEnabled, EasyVipConfig.common.commandAllowlist);
    }

    public static String sanitizeCommand(String command) {
        return CommandAllowlist.normalize(command);
    }

    private static boolean executeServerCommand(MinecraftServer server, String cmd) {
        String normalized = sanitizeCommand(cmd);
        if (normalized == null) {
            return false;
        }
        if (!isCommandAllowed(normalized)) {
            System.err.println("[EasyVip] Command execution blocked by security allowlist: " + normalized);
            return false;
        }
        if (server != null) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), normalized);
            return true;
        }
        return false;
    }

    private static boolean executeFtbRankCommand(MinecraftServer server, String cmd) {
        if (!EasyVipConfig.integrations.ftbRanksEnabled) {
            return false;
        }
        return executeServerCommand(server, cmd);
    }

    private static String renderFtbRankCommand(String template, Map<String, String> context, String rank) {
        Map<String, String> full = new HashMap<>(context);
        full.put("rank", rank);
        return resolvePlaceholders(template, full);
    }

    private static String getString(Map<String, Object> map, String key, String def) {
        Object val = map.get(key);
        return val != null ? val.toString() : def;
    }

    private static int getInt(Map<String, Object> map, String key, int def) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val != null) {
            try {
                return Integer.parseInt(val.toString());
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        return def;
    }
}
