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
      double borderSize = border.getSize();
      if (borderSize < 4.0D) {
         this.pending.remove(player.getUniqueId());
         this.message(player, ChatColor.RED, "teleport.rtp.failed");
         return;
      }
      Location center = border.getCenter();
      double borderHalf = Math.max(1.0D, borderSize / 2.0D - 1.0D);

      double minRadius = this.plugin.getConfig().getDouble("random-teleport.min-radius", 0.0D);
      double maxRadius = this.plugin.getConfig().getDouble("random-teleport.max-radius", 0.0D);

      // If min-radius and max-radius are both 0, default to the world border
      if (minRadius <= 0.0D && maxRadius <= 0.0D) {
         minRadius = 0.0D;
         maxRadius = borderHalf;
      } else {
         if (maxRadius <= 0.0D) maxRadius = borderHalf;
         else maxRadius = Math.min(maxRadius, borderHalf);
         minRadius = Math.max(0.0D, Math.min(minRadius, maxRadius - 1.0D));
      }

      double angle = ThreadLocalRandom.current().nextDouble(0.0D, 2.0D * Math.PI);
      double distance = Math.sqrt(ThreadLocalRandom.current().nextDouble(minRadius * minRadius, maxRadius * maxRadius));
      int x = (int) Math.floor(center.getX() + distance * Math.cos(angle));
      int z = (int) Math.floor(center.getZ() + distance * Math.sin(angle));

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
      boolean isNether = world.getEnvironment() == World.Environment.NETHER;
      if (isNether) {
         // In the Nether, avoid ceiling bedrock (Y >= 120) and lava oceans (Y <= 31)
         for (int y = 115; y >= 33; y--) {
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

      // For Overworld and other environments: stay on the surface!
      // Do not drill down into caves.
      int highest = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES).getY();
      // Scan at most 10 blocks down from the surface (for small overhangs or tree canopy)
      int minY = Math.max(world.getMinHeight(), highest - 10);
      for (int y = highest; y >= minY; y--) {
         if (y + 2 >= world.getMaxHeight()) continue;
         Block ground = world.getBlockAt(x, y, z);
         Block feet = world.getBlockAt(x, y + 1, z);
         Block head = world.getBlockAt(x, y + 2, z);
         if (!ground.getType().isSolid() || unsafeGround(ground.getType())) continue;
         if (!feet.isPassable() || feet.isLiquid() || unsafeSpace(feet.getType())) continue;
         if (!head.isPassable() || head.isLiquid() || unsafeSpace(head.getType())) continue;
         // Ensure it has access to sky light so we never drop players into pitch black caverns
         if (feet.getLightFromSky() < 4) continue;
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
