package io.github.freecoreessentials.command;

import io.github.freecoreessentials.FreeCoreEssentialsPlugin;
import io.github.freecoreessentials.lang.Lang;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class FreeCoreEssentialsCommand implements CommandExecutor, TabCompleter {
   private static final String RELOAD_PERMISSION = "freecoreessentials.reload";
   private static final String WEALTH_HOLOGRAM_PERMISSION = "freecoreessentials.wealthhologram";
   private final FreeCoreEssentialsPlugin plugin;
   private final Lang lang;

   public FreeCoreEssentialsCommand(FreeCoreEssentialsPlugin plugin, Lang lang) {
      this.plugin = plugin;
      this.lang = lang;
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
      if (arguments.length == 1 && arguments[0].equalsIgnoreCase("reload")) {
         return this.reload(sender);
      }
      if (arguments.length >= 2 && arguments[0].equalsIgnoreCase("wealth") && arguments[1].equalsIgnoreCase("hologram")) {
         return this.wealthHologram(sender, arguments);
      }
      sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.YELLOW + this.lang.message("freecoreessentials.usage"));
      return true;
   }

   private boolean reload(CommandSender sender) {
      if (!sender.hasPermission(RELOAD_PERMISSION)) {
         sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.RED + this.lang.message("freecoreessentials.no-permission"));
         return true;
      }
      this.plugin.reloadPluginConfiguration();
      sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN + this.lang.message("freecoreessentials.reload-success"));
      return true;
   }

   private boolean wealthHologram(CommandSender sender, String[] arguments) {
      if (!sender.hasPermission(WEALTH_HOLOGRAM_PERMISSION)) {
         sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.RED + this.lang.message("freecoreessentials.no-permission"));
         return true;
      }
      if (!(sender instanceof Player player)) {
         sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.RED + this.lang.message("wealth-hologram.player-only"));
         return true;
      }
      String action = arguments.length >= 3 ? arguments[2].toLowerCase() : "create";
      boolean changed = switch (action) {
         case "create" -> this.plugin.createWealthHologram(player.getLocation());
         case "remove" -> this.plugin.removeWealthHologram(player.getLocation());
         case "refresh" -> this.plugin.refreshWealthHologram(player.getLocation());
         default -> false;
      };
      String key = switch (action) {
         case "create" -> changed ? "wealth-hologram.created" : "wealth-hologram.unavailable";
         case "remove" -> changed ? "wealth-hologram.removed" : "wealth-hologram.not-found";
         case "refresh" -> changed ? "wealth-hologram.refreshing" : "wealth-hologram.not-found";
         default -> "wealth-hologram.usage";
      };
      ChatColor color = changed ? ChatColor.GREEN : ChatColor.YELLOW;
      sender.sendMessage(this.lang.message("prefix.freecoreessentials") + color + this.lang.message(key));
      return true;
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] arguments) {
      if (arguments.length == 1) return List.of("reload", "wealth").stream().filter(value -> value.startsWith(arguments[0].toLowerCase())).toList();
      if (arguments.length == 2 && arguments[0].equalsIgnoreCase("wealth")) return "hologram".startsWith(arguments[1].toLowerCase()) ? List.of("hologram") : List.of();
      if (arguments.length == 3 && arguments[0].equalsIgnoreCase("wealth") && arguments[1].equalsIgnoreCase("hologram")) {
         return List.of("create", "remove", "refresh").stream().filter(value -> value.startsWith(arguments[2].toLowerCase())).toList();
      }
      return List.of();
   }
}
