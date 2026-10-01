package io.github.freecoreessentials.command;

import io.github.freecoreessentials.lang.Lang;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.HeightMap;

/** Teleports a player to a safe random location inside the current world's border. */
public final class RandomTeleportCommand implements CommandExecutor {
   private static final String PERMISSION = "freecoreessentials.rtp.use";
   private static final int DEFAULT_ATTEMPTS = 32;
   private final org.bukkit.plugin.java.JavaPlugin plugin;
   private final Lang lang;
   private final Set<UUID> pending = new HashSet<>();

   public RandomTeleportCommand(org.bukkit.plugin.java.JavaPlugin plugin, Lang lang) {
      this.plugin = plugin;
      this.lang = lang;
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
      if (!(sender instanceof Player player)) return this.message(sender, ChatColor.RED, "teleport.player-only");
      if (!this.plugin.getConfig().getBoolean("random-teleport.enabled", true)) {
         return this.message(player, ChatColor.YELLOW, "teleport.rtp.disabled");
      }
      if (!player.hasPermission(PERMISSION)) return this.message(player, ChatColor.RED, "teleport.no-permission");
      if (arguments.length != 0) return this.message(player, ChatColor.YELLOW, "teleport.rtp.usage");

      if (!this.pending.add(player.getUniqueId())) {
         return this.message(player, ChatColor.YELLOW, "teleport.rtp.pending");
      }
      player.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.YELLOW
            + this.lang.message("teleport.rtp.searching"));
      World world = player.getWorld();
      int attempts = Math.max(1, this.plugin.getConfig().getInt("random-teleport.max-attempts", DEFAULT_ATTEMPTS));
      this.tryLocation(player, world, attempts);
      return true;
   }

   private boolean message(CommandSender sender, ChatColor color, String key, Object... values) {
      sender.sendMessage(this.lang.message("prefix.freecoreessentials") + color + this.lang.message(key, values));
      return true;
   }

   private void tryLocation(Player player, World world, int remainingAttempts) {
      if (remainingAttempts <= 0) {
         this.pending.remove(player.getUniqueId());
         this.message(player, ChatColor.RED, "teleport.rtp.failed");
         return;
      }
      WorldBorder border = world.getWorldBorder();
      double size = border.getSize();
      if (size < 4.0D) {
         this.pending.remove(player.getUniqueId());
         this.message(player, ChatColor.RED, "teleport.rtp.failed");
         return;
      }
      Location center = border.getCenter();
      double half = Math.max(1.0D, size / 2.0D - 1.0D);
      int x = (int) Math.floor(center.getX() + ThreadLocalRandom.current().nextDouble(-half, half));
      int z = (int) Math.floor(center.getZ() + ThreadLocalRandom.current().nextDouble(-half, half));
      world.getChunkAtAsync(x >> 4, z >> 4, true).whenComplete((chunk, error) ->
            Bukkit.getScheduler().runTask(this.plugin, () -> {
               if (!player.isOnline() || player.getWorld() != world) {
                  this.pending.remove(player.getUniqueId());
                  return;
               }
               Location target = error == null ? findSafeLocation(world, x, z) : null;
               if (target == null) {
                  this.tryLocation(player, world, remainingAttempts - 1);
                  return;
               }
               this.pending.remove(player.getUniqueId());
               if (!player.teleport(target, TeleportCause.COMMAND)) {
                  this.message(player, ChatColor.RED, "teleport.failed");
                  return;
               }
               player.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                     + this.lang.message("teleport.rtp.teleported"));
            }));
   }

   private static Location findSafeLocation(World world, int x, int z) {
      int highest = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES).getY();
      int startY = world.getEnvironment() == World.Environment.NETHER ? Math.min(highest, 125) : highest;
      for (int y = startY; y >= world.getMinHeight(); y--) {
         if (y + 2 >= world.getMaxHeight()) continue;
         Block ground = world.getBlockAt(x, y, z);
         Block feet = world.getBlockAt(x, y + 1, z);
         Block head = world.getBlockAt(x, y + 2, z);
         if (!ground.getType().isSolid() || unsafeGround(ground.getType())) continue;
         if (!feet.isPassable() || feet.isLiquid() || unsafeSpace(feet.getType())) continue;
         if (!head.isPassable() || head.isLiquid() || unsafeSpace(head.getType())) continue;
         return new Location(world, x + 0.5D, y + 1.0D, z + 0.5D);
      }
      return null;
   }

   private static boolean unsafeGround(Material material) {
      return material == Material.MAGMA_BLOCK || material == Material.CACTUS
            || material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE;
   }

   private static boolean unsafeSpace(Material material) {
      return material == Material.FIRE || material == Material.SOUL_FIRE
            || material == Material.POWDER_SNOW || material == Material.SWEET_BERRY_BUSH
            || material == Material.WITHER_ROSE;
   }
}
