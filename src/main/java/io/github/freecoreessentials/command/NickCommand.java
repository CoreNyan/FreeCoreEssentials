package io.github.freecoreessentials.command;

import io.github.freecoreessentials.crossserver.CrossServerCommandService;
import io.github.freecoreessentials.lang.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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

public final class NickCommand implements CommandExecutor, TabCompleter {
    private final CrossServerCommandService crossServer;
    private final Lang lang;

    public NickCommand(CrossServerCommandService crossServer, Lang lang) {
        this.crossServer = crossServer;
        this.lang = lang;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("freecoreessentials.nick")) {
            sender.sendMessage(ChatColor.RED + "你没有修改昵称的权限。");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.YELLOW
                    + this.lang.message("nick.usage"));
            return true;
        }

        Player target;
        if (args.length > 1) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) target = Bukkit.getPlayer(args[1]);
            if (target == null || !target.isOnline()) {
                sender.sendMessage(ChatColor.RED + "目标玩家 " + args[1] + " 不在当前服务器。");
                return true;
            }
        } else {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "控制台执行必须指定目标玩家: /nick <昵称> <玩家>");
                return true;
            }
            target = player;
        }

        String rawNick = args[0];
        if (rawNick.equalsIgnoreCase("off") || rawNick.equalsIgnoreCase("reset") || rawNick.equalsIgnoreCase("clear")) {
            applyNick(target, null);
            target.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                    + this.lang.message("nick.reset", target.getName(), target.getName()));
            if (!target.equals(sender)) {
                sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                        + this.lang.message("nick.reset", target.getName(), target.getName()));
            }
            return true;
        }

        String formattedNick = ChatColor.translateAlternateColorCodes('&', rawNick);
        String stripped = ChatColor.stripColor(formattedNick);
        if (stripped != null && stripped.length() > 16) {
            sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.RED
                    + this.lang.message("nick.too-long"));
            return true;
        }

        applyNick(target, rawNick);

        target.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                + this.lang.message("nick.set", target.getName(), formattedNick));
        if (!target.equals(sender)) {
            sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                    + this.lang.message("nick.set", target.getName(), formattedNick));
        }
        return true;
    }

    private void applyNick(Player target, String rawNick) {
        if (rawNick == null || rawNick.isBlank()) {
            target.setDisplayName(target.getName());
            target.setPlayerListName(target.getName());
            try {
                target.displayName(Component.text(target.getName()));
                target.playerListName(Component.text(target.getName()));
                target.customName(null);
                target.setCustomNameVisible(false);
            } catch (Throwable ignored) {}
            if (crossServer != null) {
                crossServer.setPlayerNickname(target.getUniqueId(), null);
            }
        } else {
            String formatted = ChatColor.translateAlternateColorCodes('&', rawNick);
            target.setDisplayName(formatted);
            target.setPlayerListName(formatted);
            try {
                Component comp = LegacyComponentSerializer.legacyAmpersand().deserialize(rawNick);
                target.displayName(comp);
                target.playerListName(comp);
                target.customName(comp);
                target.setCustomNameVisible(true);
            } catch (Throwable ignored) {}
            if (crossServer != null) {
                crossServer.setPlayerNickname(target.getUniqueId(), rawNick);
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reset", "off");
        }
        if (args.length == 2) {
            List<String> list = new ArrayList<>();
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    list.add(p.getName());
                }
            }
            return list;
        }
        return Collections.emptyList();
    }
}
