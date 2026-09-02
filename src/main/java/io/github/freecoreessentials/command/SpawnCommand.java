package io.github.freecoreessentials.command;

import io.github.freecoreessentials.lang.Lang;
import io.github.freecoreessentials.teleport.WarpService;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class SpawnCommand implements CommandExecutor, TabCompleter {
   private final WarpService warps;
   private final Lang lang;
   private final boolean setSpawn;

   public SpawnCommand(WarpService warps, Lang lang, boolean setSpawn) {
      this.warps = warps;
      this.lang = lang;
      this.setSpawn = setSpawn;
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
      if (!(sender instanceof Player player)) {
         sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.RED + this.lang.message("teleport.player-only"));
         return true;
      }
      if (arguments.length != 0) {
         player.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.YELLOW + this.lang.message(this.setSpawn ? "teleport.setspawn.usage" : "teleport.spawn.usage"));
         return true;
      }
      if (this.setSpawn) {
         if (!player.hasPermission("freecoreessentials.spawn.set")) {
            player.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.RED + this.lang.message("teleport.no-permission"));
            return true;
         }
         this.warps.setSpawn(player.getLocation());
         player.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN + this.lang.message("teleport.setspawn.set"));
         return true;
      }
      if (!player.hasPermission("freecoreessentials.spawn.use")) {
         player.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.RED + this.lang.message("teleport.no-permission"));
         return true;
      }
      return WarpCommand.teleport(player, this.warps.spawn(), this.lang, "teleport.spawn.teleported");
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] arguments) {
      return List.of();
   }
}
