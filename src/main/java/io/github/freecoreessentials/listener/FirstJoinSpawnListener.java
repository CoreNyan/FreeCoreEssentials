package io.github.freecoreessentials.listener;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Sends genuinely new players to the configured lobby world spawn. */
public final class FirstJoinSpawnListener implements Listener {
   private final JavaPlugin plugin;

   public FirstJoinSpawnListener(JavaPlugin plugin) {
      this.plugin = plugin;
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onSpawn(PlayerSpawnLocationEvent event) {
      if (!this.plugin.getConfig().getBoolean("first-join-spawn.enabled", true)
            || event.getPlayer().hasPlayedBefore()) return;
      String worldName = this.plugin.getConfig().getString("first-join-spawn.world", "lobby");
      World world = worldName == null ? null : Bukkit.getWorld(worldName);
      if (world == null) {
         this.plugin.getLogger().warning("First-join spawn world is not loaded: " + worldName);
         return;
      }
      Location spawn = world.getSpawnLocation().clone();
      spawn.setYaw(event.getSpawnLocation().getYaw());
      spawn.setPitch(event.getSpawnLocation().getPitch());
      event.setSpawnLocation(spawn);
   }
}
