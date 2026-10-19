package io.github.freecoreessentials.command;

import io.github.freecoreessentials.crossserver.CrossServerCommandService;
import io.github.freecoreessentials.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class MuteCommand implements CommandExecutor, TabCompleter {
    private final CrossServerCommandService crossServer;
    private final Lang lang;
    private final boolean isUnmute;

    public MuteCommand(CrossServerCommandService crossServer, Lang lang, boolean isUnmute) {
        this.crossServer = crossServer;
        this.lang = lang;
        this.isUnmute = isUnmute;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("freecoreessentials.mute")) {
            sender.sendMessage(ChatColor.RED + "你没有管理禁言的权限。");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.YELLOW
                    + this.lang.message(isUnmute ? "mute.unmute-usage" : "mute.usage"));
            return true;
        }

        String targetName = args[0];
        UUID targetUuid = null;
        Player localPlayer = Bukkit.getPlayerExact(targetName);
        if (localPlayer != null) {
            targetUuid = localPlayer.getUniqueId();
        } else if (crossServer != null) {
            targetUuid = crossServer.resolveNetworkUuid(targetName);
        }

        if (targetUuid == null) {
            sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.RED
                    + this.lang.message("mute.target-not-found", targetName));
            return true;
        }

        if (isUnmute) {
            if (crossServer != null) {
                crossServer.unmutePlayer(targetUuid);
            }
            sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                    + this.lang.message("mute.unmuted", targetName));

            // Notify unmuted player locally or remotely
            if (localPlayer != null && localPlayer.isOnline()) {
                localPlayer.sendMessage(ChatColor.GREEN + "你的禁言已被管理员解除，请文明发言。");
            } else if (crossServer != null) {
                crossServer.notifyPlayerUnmuted(targetName);
            }
            return true;
        }

        // Parse duration and reason
        long durationMillis = -1; // -1 = permanent
        String durationDisplay = lang.message("mute.forever");
        int reasonIndex = 1;

        if (args.length > 1) {
            long parsed = parseDuration(args[1]);
            if (parsed > 0) {
                durationMillis = parsed;
                durationDisplay = args[1];
                reasonIndex = 2;
            }
        }

        StringBuilder reasonBuilder = new StringBuilder();
        for (int i = reasonIndex; i < args.length; i++) {
            if (i > reasonIndex) reasonBuilder.append(" ");
            reasonBuilder.append(args[i]);
        }
        String reason = reasonBuilder.isEmpty() ? "违反服务器聊天规范" : reasonBuilder.toString();

        if (crossServer != null) {
            crossServer.mutePlayer(targetUuid, targetName, durationMillis, reason);
        }

        sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                + this.lang.message("mute.muted", targetName, durationDisplay, reason));

        String blockedMsg = ChatColor.translateAlternateColorCodes('&',
                this.lang.message("mute.chat-blocked", durationDisplay, reason));

        if (localPlayer != null && localPlayer.isOnline()) {
            localPlayer.sendMessage(blockedMsg);
        } else if (crossServer != null) {
            crossServer.notifyPlayerMuted(targetName, blockedMsg);
        }

        return true;
    }

    private long parseDuration(String input) {
        if (input == null || input.isEmpty()) return -1;
        try {
            char unit = input.charAt(input.length() - 1);
            long num = Long.parseLong(input.substring(0, input.length() - 1));
            return switch (Character.toLowerCase(unit)) {
                case 's' -> num * 1000L;
                case 'm' -> num * 60L * 1000L;
                case 'h' -> num * 3600L * 1000L;
                case 'd' -> num * 86400L * 1000L;
                default -> -1;
            };
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> list = new ArrayList<>();
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
        if (!isUnmute && args.length == 2) {
            return List.of("10m", "30m", "1h", "1d", "7d");
        }
        return Collections.emptyList();
    }
}
