package io.github.freecoreessentials.command;

import io.github.freecoreessentials.crossserver.CrossServerCommandService;
import io.github.freecoreessentials.lang.Lang;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MaintenanceCommand implements CommandExecutor, TabCompleter {
    private final CrossServerCommandService crossServer;
    private final Lang lang;

    public MaintenanceCommand(CrossServerCommandService crossServer, Lang lang) {
        this.crossServer = crossServer;
        this.lang = lang;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("freecoreessentials.maintenance")) {
            sender.sendMessage(ChatColor.RED + "你没有管理维护模式的权限。");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("list") || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    this.lang.message("maintenance.list-header")));
            Map<String, Boolean> status = crossServer.getMaintenanceStatusAll();
            for (Map.Entry<String, Boolean> entry : status.entrySet()) {
                String state = entry.getValue()
                        ? lang.message("maintenance.status-on")
                        : lang.message("maintenance.status-off");
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        this.lang.message("maintenance.list-entry", entry.getKey(), state)));
            }
            return true;
        }

        String targetTarget = args[0].toLowerCase(Locale.ROOT);
        boolean enable = true;
        if (args.length > 1) {
            String val = args[1].toLowerCase(Locale.ROOT);
            enable = val.equals("true") || val.equals("on") || val.equals("enable") || val.equals("1");
        } else {
            // Toggle
            enable = !crossServer.isMaintenanceActive(targetTarget);
        }

        crossServer.setMaintenanceMode(targetTarget, enable);

        String msgKey = enable ? "maintenance.enabled" : "maintenance.disabled";
        sender.sendMessage(this.lang.message("prefix.freecoreessentials") + (enable ? ChatColor.YELLOW : ChatColor.GREEN)
                + this.lang.message(msgKey, targetTarget));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("all", "survival", "technical", "list");
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("list")) {
            return List.of("true", "false", "on", "off");
        }
        return Collections.emptyList();
    }
}
