package io.github.freecoreessentials.listener;

import io.github.freecoreessentials.lang.Lang;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends exactly one localized confirmation for game mode changes, preventing duplicate messages.
 */
public final class GameModeFeedbackListener implements Listener {
    private static final Map<UUID, Long> SUPPRESSED_PLAYERS = new ConcurrentHashMap<>();
    private final Lang lang;

    public GameModeFeedbackListener(Lang lang) {
        this.lang = lang;
    }

    public static void suppressNextFeedback(UUID uuid) {
        SUPPRESSED_PLAYERS.put(uuid, System.currentTimeMillis() + 2000L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == event.getNewGameMode()) return;

        Long until = SUPPRESSED_PLAYERS.remove(player.getUniqueId());
        if (until != null && System.currentTimeMillis() < until) {
            // GmCommand has already sent a custom feedback message!
            return;
        }

        String name = this.lang.message("game-mode." + key(event.getNewGameMode()));
        player.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN + this.lang.message("game-mode.changed", name));
    }

    private static String key(GameMode gameMode) {
        return switch (gameMode) {
            case SURVIVAL -> "survival";
            case CREATIVE -> "creative";
            case ADVENTURE -> "adventure";
            case SPECTATOR -> "spectator";
        };
    }
}
