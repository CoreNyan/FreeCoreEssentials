package io.github.freecoreessentials.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Hides the death screen and respawns players on the next server tick when enabled. */
public final class AutoRespawnListener implements Listener {
   private final JavaPlugin plugin;

   public AutoRespawnListener(JavaPlugin plugin) {
      this.plugin = plugin;
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onDeath(PlayerDeathEvent event) {
      if (!this.plugin.getConfig().getBoolean("auto-respawn.enabled", false)) return;
      this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
         if (event.getPlayer().isOnline() && event.getPlayer().isDead()) {
            event.getPlayer().spigot().respawn();
         }
      });
   }
}
