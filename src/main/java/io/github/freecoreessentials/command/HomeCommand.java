package io.github.freecoreessentials.command;

import io.github.freecoreessentials.lang.Lang;
import io.github.freecoreessentials.teleport.WarpService;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class HomeCommand implements CommandExecutor, TabCompleter {
   private final WarpService warps;
   private final Lang lang;
   private final boolean setHome;

   public HomeCommand(WarpService warps, Lang lang, boolean setHome) {
      this.warps = warps;
      this.lang = lang;
      this.setHome = setHome;
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
      if (!(sender instanceof Player player)) return message(sender, ChatColor.RED, "teleport.player-only");
      String permission = this.setHome ? "freecoreessentials.sethome.use" : "freecoreessentials.home.use";
      if (!player.hasPermission(permission)) return message(player, ChatColor.RED, "teleport.no-permission");
      if (this.setHome) {
         if (arguments.length != 1) return message(player, ChatColor.YELLOW, "teleport.sethome.usage");
         if (!this.warps.validName(arguments[0])) return message(player, ChatColor.RED, "teleport.home.invalid-name");
         int maximumHomes = this.warps.maximumHomes();
         WarpService.SetHomeResult result = this.warps.setHome(player.getUniqueId(), arguments[0], player.getLocation(), maximumHomes);
         if (result == WarpService.SetHomeResult.LIMIT_REACHED) return message(player, ChatColor.RED, "teleport.sethome.limit-reached", maximumHomes);
         if (result != WarpService.SetHomeResult.SET) return message(player, ChatColor.RED, "teleport.home.invalid-name");
         return message(player, ChatColor.GREEN, "teleport.sethome.set", arguments[0]);
      }

      if (arguments.length == 1 && arguments[0].equalsIgnoreCase("list")) {
         List<String> homes = this.warps.homeNames(player.getUniqueId());
         return homes.isEmpty()
            ? message(player, ChatColor.YELLOW, "teleport.home.empty")
            : message(player, ChatColor.AQUA, "teleport.home.list", String.join(", ", homes));
      }
      if (arguments.length >= 1 && arguments[0].equalsIgnoreCase("remove")) {
         if (arguments.length != 2) return message(player, ChatColor.YELLOW, "teleport.home.remove-usage");
         if (!this.warps.validName(arguments[1])) return message(player, ChatColor.RED, "teleport.home.invalid-name");
         if (!this.warps.removeHome(player.getUniqueId(), arguments[1])) return message(player, ChatColor.RED, "teleport.home.not-found", arguments[1]);
         return message(player, ChatColor.GREEN, "teleport.home.removed", arguments[1]);
      }
      if (arguments.length != 1) return message(player, ChatColor.YELLOW, "teleport.home.usage");
      if (!this.warps.validName(arguments[0])) return message(player, ChatColor.RED, "teleport.home.invalid-name");
      WarpService.Home home = this.warps.findHome(player.getUniqueId(), arguments[0]);
      if (home == null) return message(player, ChatColor.RED, "teleport.home.not-found", arguments[0]);
      return WarpCommand.teleport(player, home.location(), this.lang, "teleport.home.teleported", home.name());
   }

   private boolean message(CommandSender sender, ChatColor color, String key, Object... values) {
      sender.sendMessage(this.lang.message("prefix.freecoreessentials") + color + this.lang.message(key, values));
      return true;
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] arguments) {
      if (this.setHome || !(sender instanceof Player player) || !player.hasPermission("freecoreessentials.home.use")) return List.of();
      if (arguments.length == 1) {
         String prefix = arguments[0].toLowerCase(Locale.ROOT);
         List<String> results = new ArrayList<>();
         results.add("list");
         results.add("remove");
         results.addAll(this.warps.homeNames(player.getUniqueId()));
         return results.stream().filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
      }
      if (arguments.length == 2 && arguments[0].equalsIgnoreCase("remove")) {
         String prefix = arguments[1].toLowerCase(Locale.ROOT);
         return this.warps.homeNames(player.getUniqueId()).stream()
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
      }
      return List.of();
   }
}
