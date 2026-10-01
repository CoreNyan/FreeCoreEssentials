package io.github.freecoreessentials.listener;

import io.github.freecoreessentials.FreeCoreEssentialsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/** Opens the configured CommandPanels menu when the player presses the off-hand key (F). */
public final class MenuShortcutListener implements Listener {
    private final FreeCoreEssentialsPlugin plugin;

    public MenuShortcutListener(FreeCoreEssentialsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (!plugin.getConfig().getBoolean("menu.shortcut-enabled", true)) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }
        if (!plugin.getServer().getPluginManager().isPluginEnabled("CommandPanels")) {
            return;
        }
        String panel = plugin.getConfig().getString("menu.shortcut-menu", "main_manu");
        if (panel == null || panel.isBlank()) {
            return;
        }
        event.setCancelled(true);
        String command = "pa open " + panel.trim();
        Bukkit.dispatchCommand(player, command);
    }
}
