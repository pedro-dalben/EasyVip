package br.com.pedrodalben.easyvip.action;

import br.com.pedrodalben.easyvip.config.EasyVipConfig;
import br.com.pedrodalben.easyvip.platform.PlatformBridge;
import br.com.pedrodalben.easyvip.service.PackageService;
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

    public static void execute(ServerPlayer player, List<Map<String, Object>> actions, Map<String, String> context) {
        if (actions == null || actions.isEmpty()) {
            return;
        }

        Map<String, String> fullContext = new HashMap<>(context);
        fullContext.put("player", player.getGameProfile().getName());

        for (Map<String, Object> action : actions) {
            try {
                executeSingle(player, action, fullContext);
            } catch (Exception e) {
                System.err.println("[EasyVip] Error executing action of type " + action.get("type") + ": " + e.getMessage());
                if (EasyVipConfig.common.debug) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static void executeSingle(ServerPlayer player, Map<String, Object> action, Map<String, String> context) {
        String type = getString(action, "type", "");
        if (type.isEmpty()) return;

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
                        }
                    }
                }
                break;
            }
            case "give_experience": {
                int amount = getInt(action, "amount", 0);
                if (amount > 0) {
                    player.giveExperiencePoints(amount);
                }
                break;
            }
            case "give_level": {
                int amount = getInt(action, "amount", 0);
                if (amount > 0) {
                    player.giveExperienceLevels(amount);
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
                        }
                    }
                }
                break;
            }
            case "send_message": {
                String message = getString(action, "message", "");
                if (!message.isEmpty()) {
                    player.sendSystemMessage(Component.literal(resolvePlaceholders(message, context)));
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
                }
                break;
            }
            case "run_server_command": {
                String command = getString(action, "command", "");
                if (!command.isEmpty()) {
                    String cmd = resolvePlaceholders(command, context);
                    if (EasyVipConfig.common.commandAllowlistEnabled) {
                        boolean allowed = false;
                        for (String prefix : EasyVipConfig.common.commandAllowlist) {
                            if (cmd.startsWith(prefix)) {
                                allowed = true;
                                break;
                            }
                        }
                        if (!allowed) {
                            System.err.println("[EasyVip] Command execution blocked by security allowlist: " + cmd);
                            return;
                        }
                    }
                    MinecraftServer server = player.getServer();
                    if (server != null) {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
                    }
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
                    }
                }
                break;
            }
            case "give_package": {
                String pkgId = getString(action, "package_id", "");
                if (!pkgId.isEmpty()) {
                    PackageService.givePackage(player, pkgId);
                }
                break;
            }
            case "set_scoreboard_tag": {
                String tag = getString(action, "tag", "");
                if (!tag.isEmpty()) {
                    player.addTag(tag);
                }
                break;
            }
            case "remove_scoreboard_tag": {
                String tag = getString(action, "tag", "");
                if (!tag.isEmpty()) {
                    player.removeTag(tag);
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
                    }
                }
                break;
            }
            case "give_permission_flag_internal": {
                String perm = getString(action, "permission", "");
                if (!perm.isEmpty() && platform != null) {
                    platform.setPermissionFlagInternal(player, perm, true);
                }
                break;
            }
            case "remove_permission_flag_internal": {
                String perm = getString(action, "permission", "");
                if (!perm.isEmpty() && platform != null) {
                    platform.setPermissionFlagInternal(player, perm, false);
                }
                break;
            }
            case "custom_event_hook": {
                String hook = getString(action, "hook", "");
                if (!hook.isEmpty() && platform != null) {
                    platform.fireCustomEventHook(player, hook, context);
                }
                break;
            }
        }
    }

    public static String resolvePlaceholders(String text, Map<String, String> context) {
        if (text == null) return "";
        String result = text;
        for (Map.Entry<String, String> entry : context.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result.replace('&', '§');
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
