package io.github.freecoreessentials.command;

import io.github.freecoreessentials.lang.Lang;
import io.github.freecoreessentials.teleport.WarpService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

public final class WarpCommand implements CommandExecutor, TabCompleter {
   private static final String USE_PERMISSION = "freecoreessentials.warp.use";
   private static final String ADMIN_PERMISSION = "freecoreessentials.warp.admin";
   private final WarpService warps;
   private final Lang lang;

   public WarpCommand(WarpService warps, Lang lang) {
      this.warps = warps;
      this.lang = lang;
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
      if (!(sender instanceof Player player)) return this.message(sender, ChatColor.RED, "teleport.player-only");
      if (arguments.length == 0) return this.message(player, ChatColor.YELLOW, "teleport.warp.usage");
      String action = arguments[0].toLowerCase(Locale.ROOT);
      if (action.equals("list")) return this.list(player);
      if (action.equals("set")) return this.set(player, arguments);
      if (action.equals("delete") || action.equals("remove")) return this.delete(player, arguments);
      return this.teleport(player, arguments[0]);
   }

   private boolean list(Player player) {
      if (!player.hasPermission(USE_PERMISSION)) return this.message(player, ChatColor.RED, "teleport.no-permission");
      List<String> names = this.warps.names();
      return names.isEmpty()
         ? this.message(player, ChatColor.YELLOW, "teleport.warp.empty")
         : this.message(player, ChatColor.YELLOW, "teleport.warp.list", String.join(", ", names));
   }

   private boolean set(Player player, String[] arguments) {
      if (!player.hasPermission(ADMIN_PERMISSION)) return this.message(player, ChatColor.RED, "teleport.no-permission");
      if (arguments.length != 2) return this.message(player, ChatColor.YELLOW, "teleport.warp.set-usage");
      if (!this.warps.validName(arguments[1])) return this.message(player, ChatColor.RED, "teleport.warp.invalid-name");
      this.warps.setWarp(arguments[1], player.getLocation());
      return this.message(player, ChatColor.GREEN, "teleport.warp.set", arguments[1]);
   }

   private boolean delete(Player player, String[] arguments) {
      if (!player.hasPermission(ADMIN_PERMISSION)) return this.message(player, ChatColor.RED, "teleport.no-permission");
      if (arguments.length != 2) return this.message(player, ChatColor.YELLOW, "teleport.warp.delete-usage");
      return this.warps.removeWarp(arguments[1])
         ? this.message(player, ChatColor.GREEN, "teleport.warp.deleted", arguments[1])
         : this.message(player, ChatColor.RED, "teleport.warp.not-found", arguments[1]);
   }

   private boolean teleport(Player player, String name) {
      if (!player.hasPermission(USE_PERMISSION)) return this.message(player, ChatColor.RED, "teleport.no-permission");
      WarpService.Warp warp = this.warps.findWarp(name);
      if (warp == null) return this.message(player, ChatColor.RED, "teleport.warp.not-found", name);
      return teleport(player, warp.location(), this.lang, "teleport.warp.teleported", warp.name());
   }

   private boolean message(CommandSender sender, ChatColor color, String key, Object... values) {
      sender.sendMessage(this.lang.message("prefix.freecoreessentials") + color + this.lang.message(key, values));
      return true;
   }

   static boolean teleport(Player player, WarpService.StoredLocation stored, Lang lang, String successKey, Object... values) {
      if (stored == null) {
         player.sendMessage(lang.message("prefix.freecoreessentials") + ChatColor.RED + lang.message("teleport.spawn.not-set"));
         return true;
      }
      org.bukkit.Location location = stored.resolve();
      if (location == null) {
         player.sendMessage(lang.message("prefix.freecoreessentials") + ChatColor.RED + lang.message("teleport.world-unavailable"));
         return true;
      }
      if (!player.teleport(location, TeleportCause.COMMAND)) {
         player.sendMessage(lang.message("prefix.freecoreessentials") + ChatColor.RED + lang.message("teleport.failed"));
         return true;
      }
      player.sendMessage(lang.message("prefix.freecoreessentials") + ChatColor.GREEN + lang.message(successKey, values));
      return true;
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] arguments) {
      if (arguments.length != 1) return List.of();
      String prefix = arguments[0].toLowerCase(Locale.ROOT);
      List<String> result = new ArrayList<>();
      if (sender.hasPermission(USE_PERMISSION)) {
         result.add("list");
         result.addAll(this.warps.names());
      }
      if (sender.hasPermission(ADMIN_PERMISSION)) {
         result.add("set");
         result.add("delete");
      }
      return result.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted(String.CASE_INSENSITIVE_ORDER).toList();
   }
}
