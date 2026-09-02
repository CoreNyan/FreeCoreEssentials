package io.github.freecoreessentials.teleport;

import io.github.freecoreessentials.lang.Lang;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/** Remembers the location immediately before a successful teleport for /back. */
public final class BackService implements Listener {
   private final ConcurrentHashMap<UUID, WarpService.StoredLocation> previousLocations = new ConcurrentHashMap<>();
   private final Set<UUID> ignoredTeleports = ConcurrentHashMap.newKeySet();
   private final Lang lang;

   public BackService(Lang lang) {
      this.lang = lang;
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onTeleport(PlayerTeleportEvent event) {
      UUID playerId = event.getPlayer().getUniqueId();
      if (this.ignoredTeleports.remove(playerId)) return;
      Location from = event.getFrom();
      Location to = event.getTo();
      if (to == null || samePosition(from, to)) return;
      this.previousLocations.put(playerId, WarpService.StoredLocation.from(from));
   }

   public boolean teleportBack(Player player) {
      WarpService.StoredLocation stored = this.previousLocations.get(player.getUniqueId());
      if (stored == null) return message(player, ChatColor.YELLOW, "teleport.back.not-found");
      Location location = stored.resolve();
      if (location == null) return message(player, ChatColor.RED, "teleport.world-unavailable");

      UUID playerId = player.getUniqueId();
      this.ignoredTeleports.add(playerId);
      if (!player.teleport(location, TeleportCause.COMMAND)) {
         this.ignoredTeleports.remove(playerId);
         return message(player, ChatColor.RED, "teleport.failed");
      }
      return message(player, ChatColor.GREEN, "teleport.back.teleported");
   }

   private boolean message(Player player, ChatColor color, String key) {
      player.sendMessage(this.lang.message("prefix.freecoreessentials") + color + this.lang.message(key));
      return true;
   }

   private static boolean samePosition(Location first, Location second) {
      return first.getWorld() == second.getWorld()
         && Double.compare(first.getX(), second.getX()) == 0
         && Double.compare(first.getY(), second.getY()) == 0
         && Double.compare(first.getZ(), second.getZ()) == 0;
   }
}
