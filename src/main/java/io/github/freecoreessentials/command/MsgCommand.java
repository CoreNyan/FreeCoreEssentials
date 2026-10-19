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

public final class MsgCommand implements CommandExecutor, TabCompleter {
    private final CrossServerCommandService crossServer;
    private final Lang lang;

    public MsgCommand(CrossServerCommandService crossServer, Lang lang) {
        this.crossServer = crossServer;
        this.lang = lang;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("freecoreessentials.msg")) {
            sender.sendMessage(ChatColor.RED + "你没有发送私聊消息的权限。");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.YELLOW
                    + this.lang.message("private-message.usage"));
            return true;
        }

        // Check if sender is muted
        if (sender instanceof Player player && crossServer != null && crossServer.isMuted(player.getUniqueId())) {
            String remaining = crossServer.getMuteRemainingDisplay(player.getUniqueId());
            String reason = crossServer.getMuteReason(player.getUniqueId());
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    this.lang.message("mute.chat-blocked", remaining, reason)));
            return true;
        }

        String targetName = args[0];
        if (sender instanceof Player p && p.getName().equalsIgnoreCase(targetName)) {
            sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.RED
                    + this.lang.message("private-message.cannot-msg-self"));
            return true;
        }

        StringBuilder msgBuilder = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) msgBuilder.append(" ");
            msgBuilder.append(args[i]);
        }
        String message = msgBuilder.toString();

        String senderDisplayName = (sender instanceof Player p)
                ? (crossServer != null ? crossServer.getPlayerDisplayName(p) : p.getDisplayName())
                : "控制台";

        Player localTarget = Bukkit.getPlayerExact(targetName);
        if (localTarget == null) localTarget = Bukkit.getPlayer(targetName);

        if (localTarget != null && localTarget.isOnline()) {
            String targetDisplayName = (crossServer != null)
                    ? crossServer.getPlayerDisplayName(localTarget) : localTarget.getDisplayName();

            String formatSender = ChatColor.translateAlternateColorCodes('&',
                    this.lang.message("private-message.format-sender", targetDisplayName, message));
            String formatReceiver = ChatColor.translateAlternateColorCodes('&',
                    this.lang.message("private-message.format-receiver", senderDisplayName, message));

            sender.sendMessage(formatSender);
            localTarget.sendMessage(formatReceiver);
            return true;
        }

        // Cross-server send
        if (crossServer != null && crossServer.isPlayerOnlineNetwork(targetName)) {
            crossServer.sendPrivateMessage(sender, targetName, senderDisplayName, message);
            return true;
        }

        sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.RED
                + this.lang.message("private-message.target-not-found", targetName));
        return true;
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
        return Collections.emptyList();
    }
}
