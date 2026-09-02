package io.github.freecoreessentials.listener;

import io.github.freecoreessentials.lang.Lang;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;

/** Sends a reliable, localized confirmation for game mode changes on both editions. */
public final class GameModeFeedbackListener implements Listener {
   private final Lang lang;

   public GameModeFeedbackListener(Lang lang) {
      this.lang = lang;
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onGameModeChange(PlayerGameModeChangeEvent event) {
      Player player = event.getPlayer();
      if (player.getGameMode() == event.getNewGameMode()) return;
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
