package io.github.freecoreessentials.command;

import io.github.freecoreessentials.lang.Lang;
import io.github.freecoreessentials.teleport.BackService;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class BackCommand implements CommandExecutor, TabCompleter {
   private final BackService backs;
   private final Lang lang;

   public BackCommand(BackService backs, Lang lang) {
      this.backs = backs;
      this.lang = lang;
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
      if (!(sender instanceof Player player)) return message(sender, ChatColor.RED, "teleport.player-only");
      if (!player.hasPermission("freecoreessentials.back.use")) return message(player, ChatColor.RED, "teleport.no-permission");
      if (arguments.length != 0) return message(player, ChatColor.YELLOW, "teleport.back.usage");
      return this.backs.teleportBack(player);
   }

   private boolean message(CommandSender sender, ChatColor color, String key) {
      sender.sendMessage(this.lang.message("prefix.freecoreessentials") + color + this.lang.message(key));
      return true;
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] arguments) {
      return List.of();
   }
}
