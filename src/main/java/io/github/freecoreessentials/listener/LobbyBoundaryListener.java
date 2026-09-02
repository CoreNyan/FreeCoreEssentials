package io.github.freecoreessentials.listener;

import io.github.freecoreessentials.lang.Lang;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Restricts non-bypass players to the configured lobby rectangle. */
public final class LobbyBoundaryListener implements Listener {
   private final JavaPlugin plugin;
   private final Lang lang;
   private final Map<UUID, Long> lastMessage = new HashMap<>();

   public LobbyBoundaryListener(JavaPlugin plugin, Lang lang) {
      this.plugin = plugin;
      this.lang = lang;
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onMove(PlayerMoveEvent event) {
      Location from = event.getFrom();
      Location to = event.getTo();
      if (to == null || samePosition(from, to) || !this.isRestricted(event.getPlayer(), to)) return;
      event.setTo(from);
      this.notifyDenied(event.getPlayer());
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onTeleport(PlayerTeleportEvent event) {
      if (!this.isRestricted(event.getPlayer(), event.getTo())) return;
      event.setCancelled(true);
      this.notifyDenied(event.getPlayer());
   }

   /** PlayerMoveEvent is not guaranteed to fire while a player rides a vehicle. */
   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onMinecartMove(VehicleMoveEvent event) {
      if (!(event.getVehicle() instanceof Minecart)) return;
      Location from = event.getFrom();
      Location to = event.getTo();
      if (to == null || samePosition(from, to)) return;
      for (Entity passenger : event.getVehicle().getPassengers()) {
         if (!(passenger instanceof Player player) || !this.isRestricted(player, to)) continue;
         Location safe = this.isRestricted(player, from) ? clampInside(from) : from;
         event.getVehicle().setVelocity(event.getVehicle().getVelocity().multiply(0.0D));
         event.getVehicle().teleport(safe);
         this.notifyDenied(player);
         break;
      }
   }

   private boolean isRestricted(Player player, Location target) {
      if (!this.plugin.getConfig().getBoolean("lobby-boundary.enabled", false)
            || (player.isOp() && this.plugin.getConfig().getBoolean("lobby-boundary.op-bypass", true))
            || player.hasPermission(this.plugin.getConfig().getString("lobby-boundary.bypass-permission", "freecoreessentials.lobby-boundary.bypass"))) {
         return false;
      }
      World world = target.getWorld();
      String configuredWorld = this.plugin.getConfig().getString("lobby-boundary.world", "lobby");
      if (world == null || configuredWorld == null || !world.getName().equalsIgnoreCase(configuredWorld)) return false;
      double centerX = this.plugin.getConfig().getDouble("lobby-boundary.center-x", 0.0D);
      double centerZ = this.plugin.getConfig().getDouble("lobby-boundary.center-z", 0.0D);
      double halfSize = Math.max(0.5D, this.plugin.getConfig().getDouble("lobby-boundary.size", 1.0D) / 2.0D);
      return target.getX() < centerX - halfSize || target.getX() > centerX + halfSize
            || target.getZ() < centerZ - halfSize || target.getZ() > centerZ + halfSize;
   }

   private void notifyDenied(Player player) {
      if (!this.plugin.getConfig().getBoolean("lobby-boundary.notify", true)) return;
      long now = System.currentTimeMillis();
      long previous = this.lastMessage.getOrDefault(player.getUniqueId(), 0L);
      if (now - previous < 1500L) return;
      this.lastMessage.put(player.getUniqueId(), now);
      player.sendMessage(this.lang.message("prefix.freecoreessentials") + this.lang.message("lobby-boundary.denied"));
   }

   private Location clampInside(Location location) {
      double centerX = this.plugin.getConfig().getDouble("lobby-boundary.center-x", 0.0D);
      double centerZ = this.plugin.getConfig().getDouble("lobby-boundary.center-z", 0.0D);
      double halfSize = Math.max(0.5D, this.plugin.getConfig().getDouble("lobby-boundary.size", 1.0D) / 2.0D);
      Location clamped = location.clone();
      clamped.setX(Math.max(centerX - halfSize, Math.min(centerX + halfSize, clamped.getX())));
      clamped.setZ(Math.max(centerZ - halfSize, Math.min(centerZ + halfSize, clamped.getZ())));
      return clamped;
   }

   private static boolean samePosition(Location from, Location to) {
      return from.getWorld() == to.getWorld()
            && from.getX() == to.getX()
            && from.getY() == to.getY()
            && from.getZ() == to.getZ();
   }
}
