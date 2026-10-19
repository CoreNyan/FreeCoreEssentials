package io.github.freecoreessentials.command;

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

public final class SpeedCommand implements CommandExecutor, TabCompleter {
    private final Lang lang;
    private final boolean isFly;

    public SpeedCommand(Lang lang, boolean isFly) {
        this.lang = lang;
        this.isFly = isFly;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("freecoreessentials.speed")) {
            sender.sendMessage(ChatColor.RED + "你没有调整移动速度的权限。");
            return true;
        }

        Player target;
        if (args.length > 1) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) target = Bukkit.getPlayer(args[1]);
            if (target == null || !target.isOnline()) {
                sender.sendMessage(ChatColor.RED + "目标玩家 " + args[1] + " 不在线。");
                return true;
            }
        } else {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "控制台执行必须指定玩家: /" + label + " <速度> <玩家>");
                return true;
            }
            target = player;
        }

        // Query mode if no speed argument provided
        if (args.length == 0) {
            float current = isFly ? target.getFlySpeed() : target.getWalkSpeed();
            // Scale to 1-10 range (default fly is 0.1 -> 1.0, walk is 0.2 -> 1.0)
            float display = isFly ? (current * 10.0f) : (current * 5.0f);
            String key = isFly ? "speed.current-fly" : "speed.current-walk";
            sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.YELLOW
                    + this.lang.message(key, String.format(Locale.ROOT, "%.1f", display)));
            return true;
        }

        String speedArg = args[0].toLowerCase(Locale.ROOT);
        float newSpeed;
        if (speedArg.equals("default") || speedArg.equals("reset") || speedArg.equals("0")) {
            newSpeed = isFly ? 0.1f : 0.2f;
        } else {
            try {
                float val = Float.parseFloat(speedArg);
                if (val < 1.0f || val > 10.0f) {
                    sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.RED
                            + this.lang.message("speed.invalid"));
                    return true;
                }
                newSpeed = isFly ? (val / 10.0f) : (val / 5.0f * 0.2f);
            } catch (NumberFormatException e) {
                sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.RED
                        + this.lang.message("speed.invalid"));
                return true;
            }
        }

        // Clamp safety
        newSpeed = Math.max(0.0f, Math.min(1.0f, newSpeed));
        if (isFly) {
            target.setFlySpeed(newSpeed);
            float display = newSpeed * 10.0f;
            target.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                    + this.lang.message("speed.set-fly", target.getName(), String.format(Locale.ROOT, "%.1f", display)));
            if (!target.equals(sender)) {
                sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                        + this.lang.message("speed.set-fly", target.getName(), String.format(Locale.ROOT, "%.1f", display)));
            }
        } else {
            target.setWalkSpeed(newSpeed);
            float display = newSpeed * 5.0f;
            target.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                    + this.lang.message("speed.set-walk", target.getName(), String.format(Locale.ROOT, "%.1f", display)));
            if (!target.equals(sender)) {
                sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                        + this.lang.message("speed.set-walk", target.getName(), String.format(Locale.ROOT, "%.1f", display)));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("1", "2", "3", "4", "5", "default", "reset");
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
