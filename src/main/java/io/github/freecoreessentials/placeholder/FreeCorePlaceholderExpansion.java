package io.github.freecoreessentials.placeholder;

import io.github.freecoreeconomy.vault.BlessingSkinEconomy;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Supplies lightweight, server-owned placeholders for scoreboards and menus. */
public final class FreeCorePlaceholderExpansion extends PlaceholderExpansion implements Listener, AutoCloseable {
   private static final long REFRESH_INTERVAL_TICKS = 40L;
   private final JavaPlugin plugin;
   private final BlessingSkinEconomy economy;
   private final Map<UUID, String> balances = new ConcurrentHashMap<>();
   private int refreshTaskId = -1;

   public FreeCorePlaceholderExpansion(JavaPlugin plugin, BlessingSkinEconomy economy) {
      this.plugin = plugin;
      this.economy = economy;
   }

   @Override
   public String getIdentifier() {
      return "freecore";
   }

   @Override
   public String getAuthor() {
      return String.join(", ", this.plugin.getDescription().getAuthors());
   }

   @Override
   public String getVersion() {
      return this.plugin.getDescription().getVersion();
   }

   @Override
   public boolean persist() {
      return true;
   }

   public void start() {
      this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
      this.refreshTaskId = this.plugin.getServer().getScheduler().scheduleSyncRepeatingTask(this.plugin, this::refreshOnlinePlayers, 1L, REFRESH_INTERVAL_TICKS);
      this.refreshOnlinePlayers();
   }

   @Override
   public String onPlaceholderRequest(Player player, String params) {
      if (player == null || params == null) {
         return "";
      }

      return switch (params.toLowerCase(Locale.ROOT)) {
         case "world" -> player.getWorld().getName();
         case "hcoin" -> this.balances.computeIfAbsent(player.getUniqueId(), ignored -> {
            this.refreshBalance(player);
            return this.economy.formatAmount(0.0D);
         });
         case "title" -> this.title(player);
         case "tab_name" -> this.tabName(player);
         default -> null;
      };
   }

   private String title(Player player) {
      String name = PlaceholderAPI.setPlaceholders(player, "%fotiatags_tag_name%");
      if (name == null || name.isBlank() || name.equals("%fotiatags_tag_name%")) return "&7无";

      String prefix = PlaceholderAPI.setPlaceholders(player, "%fotiatags_prefix2%");
      return prefix == null || prefix.isBlank() || prefix.equals("%fotiatags_prefix2%") ? name : prefix;
   }

   /**
    * Returns title and player name in one legacy-formatted string so TAB keeps
    * the title's trailing color code when it renders the player name.
    */
    private String tabName(Player player) {
      String prefix = rawFotiaPrefix2(player);
      if (prefix == null) {
         prefix = PlaceholderAPI.setPlaceholders(player, "%fotiatags_prefix2%");
      }
      if (prefix == null || prefix.isBlank() || prefix.equals("%fotiatags_prefix2%")) {
         return ChatColor.WHITE + player.getName();
      }
      // FotiaTags' PAPI serializer drops a trailing color code when no text
      // follows it. Reading the raw prefix keeps that code for the player name.
      return ChatColor.translateAlternateColorCodes('&', prefix) + player.getName();
    }

   private String rawFotiaPrefix2(Player player) {
      Plugin fotiaTags = plugin.getServer().getPluginManager().getPlugin("FotiaTags");
      if (fotiaTags == null || !fotiaTags.isEnabled()) return null;
      try {
         Object tagManager = fotiaTags.getClass().getMethod("getTagManager").invoke(fotiaTags);
         Object prefix = tagManager.getClass()
                 .getMethod("getCurrentPrefix2", UUID.class)
                 .invoke(tagManager, player.getUniqueId());
         return prefix instanceof String value ? value : null;
      } catch (ReflectiveOperationException ignored) {
         return null;
      }
   }

   @EventHandler
   public void onPlayerJoin(PlayerJoinEvent event) {
      this.refreshBalance(event.getPlayer());
   }

   @EventHandler
   public void onPlayerQuit(PlayerQuitEvent event) {
      this.balances.remove(event.getPlayer().getUniqueId());
   }

   private void refreshOnlinePlayers() {
      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         this.refreshBalance(player);
      }
   }

   private void refreshBalance(Player player) {
      UUID playerId = player.getUniqueId();
      String playerName = player.getName();
      this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, () -> {
         double balance = this.economy.getBalance(playerName);
         this.balances.put(playerId, this.economy.formatAmount(balance));
      });
   }

   @Override
   public void close() {
      if (this.refreshTaskId != -1) {
         this.plugin.getServer().getScheduler().cancelTask(this.refreshTaskId);
         this.refreshTaskId = -1;
      }
      this.balances.clear();
      this.unregister();
   }
}
