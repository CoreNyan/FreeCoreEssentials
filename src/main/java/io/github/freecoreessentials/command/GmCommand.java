package io.github.freecoreessentials.command;

import io.github.freecoreessentials.crossserver.CrossServerCommandService;
import io.github.freecoreessentials.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class GmCommand implements CommandExecutor, TabCompleter {
    private final CrossServerCommandService crossServer;
    private final Lang lang;

    public GmCommand(CrossServerCommandService crossServer, Lang lang) {
        this.crossServer = crossServer;
        this.lang = lang;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("freecoreessentials.gamemode")) {
            sender.sendMessage(ChatColor.RED + "你没有修改游戏模式的权限。");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "用法: /" + label + " <0|1|2|3|s|c|a|sp> [玩家]");
            return true;
        }

        GameMode mode = parseMode(args[0]);
        if (mode == null) {
            sender.sendMessage(ChatColor.RED + "未知游戏模式: " + args[0] + " (支持 0, 1, 2, 3, s, c, a, sp)");
            return true;
        }

        String modeName = switch (mode) {
            case SURVIVAL -> lang.message("game-mode.survival");
            case CREATIVE -> lang.message("game-mode.creative");
            case ADVENTURE -> lang.message("game-mode.adventure");
            case SPECTATOR -> lang.message("game-mode.spectator");
        };

        if (args.length > 1) {
            String targetName = args[1];
            Player localTarget = Bukkit.getPlayerExact(targetName);
            if (localTarget == null) localTarget = Bukkit.getPlayer(targetName);

            if (localTarget != null && localTarget.isOnline()) {
                applyLocalGameMode(localTarget, mode);
                if (!localTarget.equals(sender)) {
                    sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                            + "已将 " + localTarget.getName() + " 的游戏模式切换为 " + modeName + "。");
                }
                return true;
            }

            // Cross-server dispatch
            if (crossServer != null && crossServer.isPlayerOnlineNetwork(targetName)) {
                crossServer.dispatchRemoteGameMode(sender, targetName, mode);
                sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                        + "已向目标子服发送切换指令：将 " + targetName + " 设置为 " + modeName + "。");
                return true;
            }

            sender.sendMessage(ChatColor.RED + "目标玩家 " + targetName + " 不在线。");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "控制台执行必须指定目标玩家: /" + label + " <模式> <玩家>");
            return true;
        }

        applyLocalGameMode(player, mode);
        return true;
    }

    private void applyLocalGameMode(Player target, GameMode mode) {
        if (target.getGameMode() != mode) {
            io.github.freecoreessentials.listener.GameModeFeedbackListener.suppressNextFeedback(target.getUniqueId());
            target.setGameMode(mode);
            String modeName = switch (mode) {
                case SURVIVAL -> lang.message("game-mode.survival");
                case CREATIVE -> lang.message("game-mode.creative");
                case ADVENTURE -> lang.message("game-mode.adventure");
                case SPECTATOR -> lang.message("game-mode.spectator");
            };
            target.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                    + this.lang.message("game-mode.changed", modeName));
        } else {
            String modeName = switch (mode) {
                case SURVIVAL -> lang.message("game-mode.survival");
                case CREATIVE -> lang.message("game-mode.creative");
                case ADVENTURE -> lang.message("game-mode.adventure");
                case SPECTATOR -> lang.message("game-mode.spectator");
            };
            target.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                    + this.lang.message("game-mode.changed", modeName));
        }
        if (crossServer != null) {
            crossServer.rememberRequestedMode(target, mode);
        }
    }

    private GameMode parseMode(String arg) {
        return switch (arg.toLowerCase(Locale.ROOT)) {
            case "0", "s", "survival" -> GameMode.SURVIVAL;
            case "1", "c", "creative" -> GameMode.CREATIVE;
            case "2", "a", "adventure" -> GameMode.ADVENTURE;
            case "3", "sp", "spectator" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("0", "1", "2", "3", "s", "c", "a", "sp");
        }
        if (args.length == 2) {
            List<String> list = new ArrayList<>();
            String prefix = args[1].toLowerCase(Locale.ROOT);
            if (crossServer != null) {
                for (String name : crossServer.getAllNetworkOnlinePlayerNames()) {
                    if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        list.add(name);
                    }
                }
            } else {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        list.add(p.getName());
                    }
                }
            }
            return list;
        }
        return Collections.emptyList();
    }
}
