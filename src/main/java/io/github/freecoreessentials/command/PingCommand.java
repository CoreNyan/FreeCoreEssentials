package io.github.freecoreessentials.command;

import io.github.freecoreessentials.crossserver.CrossServerCommandService;
import io.github.freecoreessentials.lang.Lang;
import io.github.freecoreessentials.util.IpLocationResolver;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class PingCommand implements CommandExecutor, TabCompleter {
    private final CrossServerCommandService crossServer;
    private final Lang lang;

    public PingCommand(CrossServerCommandService crossServer, Lang lang) {
        this.crossServer = crossServer;
        this.lang = lang;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("freecoreessentials.ping")) {
            sender.sendMessage(ChatColor.RED + "你没有查看延迟的权限。");
            return true;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "控制台执行必须指定目标玩家: /ping <玩家>");
                return true;
            }
            showPing(sender, player.getName(), player, sender.hasPermission("freecoreessentials.ip"));
            return true;
        }

        String targetName = args[0];
        Player target = Bukkit.getPlayerExact(targetName);
        if (target != null && target.isOnline()) {
            showPing(sender, target.getName(), target, sender.hasPermission("freecoreessentials.ip"));
            return true;
        }

        // Check cross-server
        if (crossServer != null && crossServer.isPlayerOnlineNetwork(targetName)) {
            showRemotePing(sender, targetName, sender.hasPermission("freecoreessentials.ip"));
            return true;
        }

        sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.RED
                + this.lang.message("ping.target-not-found", targetName));
        return true;
    }

    private void showPing(CommandSender sender, String name, Player player, boolean showIp) {
        int ping = resolvePing(player);
        sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                + (sender.equals(player)
                ? this.lang.message("ping.self", String.valueOf(ping))
                : this.lang.message("ping.other", name, String.valueOf(ping))));

        if (showIp && player.getAddress() != null) {
            InetSocketAddress addr = player.getAddress();
            String ip = addr.getAddress() != null ? addr.getAddress().getHostAddress() : "";
            if (!ip.isEmpty()) {
                Bukkit.getScheduler().runTaskAsynchronously(crossServer.getPlugin(), () -> {
                    String loc = IpLocationResolver.resolveLocation(ip);
                    Bukkit.getScheduler().runTask(crossServer.getPlugin(), () -> {
                        sender.sendMessage(ChatColor.GRAY + "  └ 登录 IP: " + ChatColor.AQUA + ip
                                + ChatColor.DARK_GRAY + " (" + ChatColor.YELLOW + loc + ChatColor.DARK_GRAY + ")");
                    });
                });
            }
        }
    }

    private void showRemotePing(CommandSender sender, String name, boolean showIp) {
        int remotePing = crossServer.getNetworkPlayerPing(name);
        sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                + this.lang.message("ping.other", name, String.valueOf(remotePing)));

        if (showIp) {
            Bukkit.getScheduler().runTaskAsynchronously(crossServer.getPlugin(), () -> {
                String ip = crossServer.getNetworkPlayerIp(name);
                if (ip != null && !ip.isBlank() && !ip.equals("未知")) {
                    String loc = IpLocationResolver.resolveLocation(ip);
                    Bukkit.getScheduler().runTask(crossServer.getPlugin(), () -> {
                        sender.sendMessage(ChatColor.GRAY + "  └ 登录 IP: " + ChatColor.AQUA + ip
                                + ChatColor.DARK_GRAY + " (" + ChatColor.YELLOW + loc + ChatColor.DARK_GRAY + ")");
                    });
                }
            });
        }
    }

    public int resolvePing(Player player) {
        if (crossServer != null) {
            int brPing = crossServer.getBedrockPing(player.getUniqueId());
            if (brPing > 0) return brPing;
        }
        try {
            org.geysermc.floodgate.api.FloodgateApi fg = org.geysermc.floodgate.api.FloodgateApi.getInstance();
            if (fg != null && fg.isFloodgatePlayer(player.getUniqueId())) {
                if (crossServer != null) {
                    int cached = crossServer.getBedrockPing(player.getUniqueId());
                    if (cached > 0) return cached;
                }
            }
        } catch (Throwable ignored) {}
        int ping = player.getPing();
        if (ping <= 1 && crossServer != null) {
            int cached = crossServer.getBedrockPing(player.getUniqueId());
            if (cached > 0) return cached;
        }
        return ping;
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
        return Collections.emptyList();
    }
}
