package io.github.freecoreessentials.display;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.sirsnaryo.donutscoreboard.API.Events.DonutScoreboardBuildEvent;
import com.sirsnaryo.donutscoreboard.API.Events.DonutScoreboardRefreshEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.geysermc.floodgate.api.FloodgateApi;

/** Keeps FotiaTags nametag teams visible on DonutScoreboard's per-player boards. */
public final class ScoreboardCompatibilityService implements Listener, AutoCloseable {
   private static final String FOTIA_TEAM_PREFIX = "ft_";
   private final JavaPlugin plugin;
   private BukkitTask task;

   public ScoreboardCompatibilityService(JavaPlugin plugin) {
      this.plugin = plugin;
   }

   public void start() {
      Bukkit.getPluginManager().registerEvents(this, plugin);
      task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 1L, 5L);
   }

   private void refresh() {
      Scoreboard main = Bukkit.getScoreboardManager() == null ? null : Bukkit.getScoreboardManager().getMainScoreboard();
      if (main == null) return;
      Set<String> fotiaTeams = new HashSet<>();
      for (Team team : main.getTeams()) {
         if (team.getName().startsWith(FOTIA_TEAM_PREFIX)) {
            applyPlayerNameColor(team);
            fotiaTeams.add(team.getName());
         }
      }
      for (Player viewer : Bukkit.getOnlinePlayers()) {
         Scoreboard board = viewer.getScoreboard();
         if (board == main) continue;
         syncFotiaTeams(main, board, fotiaTeams);
      }
   }

   private void syncFotiaTeams(Scoreboard main, Scoreboard board, Set<String> names) {
      for (Team team : List.copyOf(board.getTeams())) {
         if (team.getName().startsWith(FOTIA_TEAM_PREFIX) && !names.contains(team.getName())) team.unregister();
      }
      for (String name : names) {
         Team source = main.getTeam(name);
         if (source == null) continue;
         Team target = board.getTeam(name);
         if (target == null) target = board.registerNewTeam(name);
         target.prefix(source.prefix());
         target.suffix(source.suffix());
         target.setColor(source.getColor());
         target.setOption(Team.Option.NAME_TAG_VISIBILITY, source.getOption(Team.Option.NAME_TAG_VISIBILITY));
         for (String entry : source.getEntries()) {
            if (!target.hasEntry(entry)) target.addEntry(entry);
         }
      }
   }

   private void applyPlayerNameColor(Team source) {
      String entry = source.getEntries().stream().findFirst().orElse(null);
      Player player = entry == null ? null : Bukkit.getPlayerExact(entry);
      String raw = player == null ? null : rawFotiaPrefix2(player);
      if (raw == null || raw.isBlank()) return;
      ChatColor color = trailingColor(raw);
      if (color != null) source.setColor(color);
   }

   private ChatColor trailingColor(String raw) {
      ChatColor result = null;
      for (int index = 0; index + 1 < raw.length(); index++) {
         if (raw.charAt(index) != '&' && raw.charAt(index) != '\u00A7') continue;
         ChatColor parsed = ChatColor.getByChar(raw.charAt(index + 1));
         if (parsed != null && parsed.isColor()) result = parsed;
         index++;
      }
      return result;
   }

   private String rawFotiaPrefix2(Player player) {
      org.bukkit.plugin.Plugin fotiaTags = plugin.getServer().getPluginManager().getPlugin("FotiaTags");
      if (fotiaTags == null || !fotiaTags.isEnabled()) return null;
      try {
         Object tagManager = fotiaTags.getClass().getMethod("getTagManager").invoke(fotiaTags);
         Object prefix = tagManager.getClass().getMethod("getCurrentPrefix2", java.util.UUID.class)
                 .invoke(tagManager, player.getUniqueId());
         return prefix instanceof String value ? value : null;
      } catch (ReflectiveOperationException ignored) {
         return null;
      }
   }

   @EventHandler(priority = EventPriority.HIGH)
   public void onBoardBuild(DonutScoreboardBuildEvent event) {
      compactBedrockLines(event.getPlayer(), event.getLines());
   }

   @EventHandler(priority = EventPriority.HIGH)
   public void onBoardRefresh(DonutScoreboardRefreshEvent event) {
      compactBedrockLines(event.getPlayer(), event.getLines());
   }

   private void compactBedrockLines(Player player, List<String> lines) {
      if (!isBedrock(player)) return;
      // Keep the separators between title, world and H-coin. Remove only the
      // player row and its two leading spacers from the standard layout.
      if (lines.size() > 2) lines.subList(0, Math.min(3, lines.size())).clear();
   }

   private boolean isBedrock(Player player) {
      try {
         return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
      } catch (IllegalStateException ignored) {
         return false;
      }
   }

   @Override
   public void close() {
      if (task != null) {
         task.cancel();
         task = null;
      }
   }
}
