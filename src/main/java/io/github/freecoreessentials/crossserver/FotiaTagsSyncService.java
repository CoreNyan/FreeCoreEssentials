package io.github.freecoreessentials.crossserver;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Keeps FotiaTags' per-server cache refreshed from its shared database. */
public final class FotiaTagsSyncService {
    private final JavaPlugin plugin;
    private BukkitTask task;

    public FotiaTagsSyncService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (Bukkit.getPluginManager().getPlugin("FotiaTags") == null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOnlinePlayers, 40L, 20L);
        refreshOnlinePlayers();
    }

    public void close() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void refreshOnlinePlayers() {
        Plugin fotiaTags = Bukkit.getPluginManager().getPlugin("FotiaTags");
        if (fotiaTags == null || !fotiaTags.isEnabled()) return;
        try {
            Object tagManager = fotiaTags.getClass().getMethod("getTagManager").invoke(fotiaTags);
            Method loadPlayer = tagManager.getClass().getMethod("loadPlayer", Player.class);
            for (Player player : Bukkit.getOnlinePlayers()) {
                loadPlayer.invoke(tagManager, player);
            }
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Unable to refresh FotiaTags data from MySQL: " + ex.getMessage());
        }
    }
}
