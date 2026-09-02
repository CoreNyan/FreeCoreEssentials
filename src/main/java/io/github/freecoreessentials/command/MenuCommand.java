package io.github.freecoreessentials.command;

import io.github.freecoreessentials.FreeCoreEssentialsPlugin;
import io.github.freecoreessentials.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Opens the configured CommandPanels menu for a player. */
public final class MenuCommand implements CommandExecutor {
   private final FreeCoreEssentialsPlugin plugin;
   private final Lang lang;

   public MenuCommand(FreeCoreEssentialsPlugin plugin, Lang lang) {
      this.plugin = plugin;
      this.lang = lang;
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
      if (!(sender instanceof Player player)) {
         sender.sendMessage(this.lang.message("prefix.freecoreessentials")
               + ChatColor.RED + this.lang.message("menu.player-only"));
         return true;
      }
      if (!this.plugin.getConfig().getBoolean("menu.enabled", true)) {
         player.sendMessage(this.lang.message("prefix.freecoreessentials")
               + ChatColor.YELLOW + this.lang.message("menu.disabled"));
         return true;
      }
      if (!this.plugin.getServer().getPluginManager().isPluginEnabled("CommandPanels")) {
         player.sendMessage(this.lang.message("prefix.freecoreessentials")
               + ChatColor.RED + this.lang.message("menu.unavailable"));
         return true;
      }
      String configured = this.plugin.getConfig().getString("menu.open-command", "pa open main_menu");
      if (configured == null || configured.isBlank()) {
         player.sendMessage(this.lang.message("prefix.freecoreessentials")
               + ChatColor.RED + this.lang.message("menu.invalid-command"));
         return true;
      }
      String commandLine = configured.trim();
      while (commandLine.startsWith("/")) commandLine = commandLine.substring(1).trim();
      if (commandLine.isEmpty() || !Bukkit.dispatchCommand(player, commandLine)) {
         player.sendMessage(this.lang.message("prefix.freecoreessentials")
               + ChatColor.RED + this.lang.message("menu.open-failed"));
      }
      return true;
   }
}
