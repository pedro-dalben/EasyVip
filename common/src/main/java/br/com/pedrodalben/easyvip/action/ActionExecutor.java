package br.com.pedrodalben.easyvip.action;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.platform.PlatformBridge;
import br.com.pedrodalben.easyvip.platform.PermissionBridge;
import br.com.pedrodalben.easyvip.service.PackageService;
import br.com.pedrodalben.easyvip.util.CommandAllowlist;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
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
        if (actions == null || actions.isEmpty()) {
            return true;
        }

        Map<String, String> fullContext = new HashMap<>(context);
        fullContext.put("player", player.getGameProfile().getName());

        boolean allOk = true;
        for (Map<String, Object> action : actions) {
            try {
                if (!executeSingle(player, action, fullContext)) {
                    allOk = false;
                }
            } catch (Exception e) {
                System.err.println("[EasyVip] Error executing action of type " + action.get("type") + ": " + e.getMessage());
                if (EasyVipConfig.common.debug) {
                    e.printStackTrace();
                }
                allOk = false;
            }
        }
        return allOk;
    }

    private static boolean executeSingle(ServerPlayer player, Map<String, Object> action, Map<String, String> context) {
        String type = getString(action, "type", "");
        if (type.isEmpty()) return false;

        switch (type.toLowerCase()) {
            case "give_item": {
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
            case "give_experience": {
                int amount = getInt(action, "amount", 0);
                if (amount > 0) {
                    player.giveExperiencePoints(amount);
                    return true;
                }
                break;
            }
            case "give_level": {
                int amount = getInt(action, "amount", 0);
                if (amount > 0) {
                    player.giveExperienceLevels(amount);
                    return true;
                }
                break;
            }
            case "give_effect": {
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
                    player.sendSystemMessage(Component.literal(resolvePlaceholders(message, context)));
                    return true;
                }
                break;
            }
            case "broadcast_message": {
                String message = getString(action, "message", "");
                MinecraftServer server = player.getServer();
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
                    return executeServerCommand(player, cmd);
                }
                break;
            }
            case "run_player_command": {
                String command = getString(action, "command", "");
                if (!command.isEmpty()) {
                    String cmd = resolvePlaceholders(command, context);
                    MinecraftServer server = player.getServer();
                    if (server != null) {
                        server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), cmd);
                        return true;
                    }
                }
                break;
            }
            case "give_package": {
                String pkgId = getString(action, "package_id", "");
                if (!pkgId.isEmpty()) {
                    return PackageService.givePackage(player, pkgId);
                }
                break;
            }
            case "set_scoreboard_tag": {
                String tag = getString(action, "tag", "");
                if (!tag.isEmpty()) {
                    player.addTag(tag);
                    return true;
                }
                break;
            }
            case "remove_scoreboard_tag": {
                String tag = getString(action, "tag", "");
                if (!tag.isEmpty()) {
                    player.removeTag(tag);
                    return true;
                }
                break;
            }
            case "add_to_team": {
                String teamName = getString(action, "team", "");
                MinecraftServer server = player.getServer();
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
                String teamName = getString(action, "team", "");
                MinecraftServer server = player.getServer();
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
                String perm = getString(action, "permission", "");
                if (!perm.isEmpty() && platform != null) {
                    platform.setPermissionFlagInternal(player, perm, true);
                    return true;
                }
                break;
            }
            case "remove_permission_flag_internal": {
                String perm = getString(action, "permission", "");
                if (!perm.isEmpty() && platform != null) {
                    platform.setPermissionFlagInternal(player, perm, false);
                    return true;
                }
                break;
            }
            case "custom_event_hook": {
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
                    return executeServerCommand(player, cmd);
                }
                break;
            }
            case "add_ftb_rank": {
                String rank = getString(action, "rank", "");
                if (!rank.isEmpty()) {
                    String cmd = renderFtbRankCommand(EasyVipConfig.integrations.ftbRanksAddCommand, context, rank);
                    return executeFtbRankCommand(player, cmd);
                }
                break;
            }
            case "remove_ftb_rank": {
                String rank = getString(action, "rank", "");
                if (!rank.isEmpty()) {
                    String cmd = renderFtbRankCommand(EasyVipConfig.integrations.ftbRanksRemoveCommand, context, rank);
                    return executeFtbRankCommand(player, cmd);
                }
                break;
            }
            case "set_ftb_rank": {
                String rank = getString(action, "rank", "");
                if (!rank.isEmpty()) {
                    String cmd = renderFtbRankCommand(EasyVipConfig.integrations.ftbRanksSetCommand, context, rank);
                    return executeFtbRankCommand(player, cmd);
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

    private static boolean executeServerCommand(ServerPlayer player, String cmd) {
        if (cmd == null || cmd.isEmpty()) {
            return false;
        }
        if (!isCommandAllowed(cmd)) {
            System.err.println("[EasyVip] Command execution blocked by security allowlist: " + cmd);
            return false;
        }
        MinecraftServer server = player.getServer();
        if (server != null) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
            return true;
        }
        return false;
    }

    private static boolean executeFtbRankCommand(ServerPlayer player, String cmd) {
        if (!PermissionBridge.isFtbRanksPresent() || !EasyVipConfig.integrations.ftbRanksEnabled) {
            System.err.println("[EasyVip] FTB Ranks action ignored because FTB Ranks is not available.");
            return false;
        }
        return executeServerCommand(player, cmd);
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
