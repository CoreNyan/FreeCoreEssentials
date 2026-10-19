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
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class SudoCommand implements CommandExecutor, TabCompleter {
    private final CrossServerCommandService crossServer;
    private final Lang lang;

    public SudoCommand(CrossServerCommandService crossServer, Lang lang) {
        this.crossServer = crossServer;
        this.lang = lang;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("freecoreessentials.sudo")) {
            sender.sendMessage(ChatColor.RED + "你没有执行此命令的权限。");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.YELLOW
                    + this.lang.message("sudo.usage"));
            return true;
        }

        String targetName = args[0];
        StringBuilder actionBuilder = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) actionBuilder.append(" ");
            actionBuilder.append(args[i]);
        }
        String action = actionBuilder.toString();

        Player target = Bukkit.getPlayerExact(targetName);
        if (target != null && target.isOnline()) {
            executeLocalSudo(sender, target, action);
            return true;
        }

        // Try cross-server sudo dispatch
        if (this.crossServer != null && this.crossServer.isPlayerOnlineNetwork(targetName)) {
            this.crossServer.dispatchSudo(sender, targetName, action);
            sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                    + "已向跨服玩家 " + targetName + " 派发执行命令。");
            return true;
        }

        sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.RED
                + this.lang.message("sudo.target-not-found", targetName));
        return true;
    }

    public void executeLocalSudo(CommandSender initiator, Player target, String action) {
        if (action.startsWith("c:") || action.startsWith("C:")) {
            String cmd = action.substring(2).trim();
            if (!cmd.startsWith("/")) cmd = "/" + cmd;

            // Trigger via full Bukkit command pipeline so intercepted commands (like /tpa) work flawlessly!
            PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(target, cmd);
            Bukkit.getPluginManager().callEvent(event);
            if (!event.isCancelled()) {
                target.performCommand(cmd.substring(1));
            }

            if (initiator != null) {
                initiator.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                        + this.lang.message("sudo.executed-command", target.getName(), cmd.substring(1)));
            }
        } else {
            target.chat(action);
            if (initiator != null) {
                initiator.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                        + this.lang.message("sudo.executed-chat", target.getName(), action));
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            if (crossServer != null) {
                for (String name : crossServer.getAllNetworkOnlinePlayerNames()) {
                    if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        matches.add(name);
                    }
                }
            } else {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        matches.add(p.getName());
                    }
                }
            }
            return matches;
        }
        if (args.length == 2) {
            return List.of("c:", "你好！");
        }
        return Collections.emptyList();
    }
}
