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

import java.util.Collections;
import java.util.List;

public final class SayCommand implements CommandExecutor, TabCompleter {
    private final CrossServerCommandService crossServer;
    private final Lang lang;

    public SayCommand(CrossServerCommandService crossServer, Lang lang) {
        this.crossServer = crossServer;
        this.lang = lang;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("freecoreessentials.say")) {
            sender.sendMessage(ChatColor.RED + "你没有发送广播消息的权限。");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.YELLOW
                    + this.lang.message("broadcast.usage"));
            return true;
        }

        String targetServer = null;
        int msgStartIndex = 0;

        if (args[0].toLowerCase().startsWith("-s:")) {
            targetServer = args[0].substring(3).trim().toLowerCase();
            msgStartIndex = 1;
        }

        if (msgStartIndex >= args.length) {
            sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.YELLOW
                    + this.lang.message("broadcast.usage"));
            return true;
        }

        StringBuilder msgBuilder = new StringBuilder();
        for (int i = msgStartIndex; i < args.length; i++) {
            if (i > msgStartIndex) msgBuilder.append(" ");
            msgBuilder.append(args[i]);
        }
        String message = msgBuilder.toString();
        String senderName = (sender instanceof Player p) ? p.getName() : "服务器控制台";

        String formatted = ChatColor.translateAlternateColorCodes('&',
                this.lang.message("broadcast.format", senderName, message));

        if (targetServer != null && !targetServer.isEmpty() && !targetServer.equals("all")) {
            // Target specific server
            if (targetServer.equalsIgnoreCase(crossServer.getCurrentServerId())) {
                Bukkit.broadcastMessage(formatted);
            } else {
                crossServer.broadcastToServer(targetServer, formatted);
                sender.sendMessage(ChatColor.GRAY + "[已发送至 " + targetServer + " 子服]");
            }
        } else {
            // Broadcast to all servers across network
            Bukkit.broadcastMessage(formatted);
            crossServer.broadcastToAllPeers(formatted);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("-s:survival", "-s:technical", "-s:all");
        }
        return Collections.emptyList();
    }
}
