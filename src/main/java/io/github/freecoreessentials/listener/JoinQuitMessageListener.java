package io.github.freecoreessentials.listener;

import io.github.freecoreessentials.lang.Lang;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Replaces vanilla join/quit broadcasts with language-configurable messages. */
public final class JoinQuitMessageListener implements Listener {
    private final Lang lang;

    public JoinQuitMessageListener(Lang lang) {
        this.lang = lang;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        event.setJoinMessage(message("player.join", event.getPlayer().getName()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        event.setQuitMessage(message("player.quit", event.getPlayer().getName()));
    }

    private String message(String key, String playerName) {
        String value = lang.message(key, playerName);
        return value.isBlank() ? null : value;
    }
}
