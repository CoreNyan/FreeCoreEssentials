package io.github.freecoreeconomy.command;

import io.github.freecoreeconomy.database.DatabaseGateway;
import io.github.freecoreeconomy.vault.BlessingSkinEconomy;
import io.github.freecoreessentials.lang.Lang;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Asynchronous, paginated chat leaderboard for the richest 100 players. */
public final class BalanceTopCommand implements CommandExecutor, TabCompleter {
   private static final int PAGE_SIZE = 10;
   private static final int MAX_ENTRIES = 100;
   private static final int MAX_PAGES = MAX_ENTRIES / PAGE_SIZE;

   private final JavaPlugin plugin;
   private final DatabaseGateway database;
   private final BlessingSkinEconomy economy;
   private final Lang lang;

   public BalanceTopCommand(JavaPlugin plugin, DatabaseGateway database, BlessingSkinEconomy economy, Lang lang) {
      this.plugin = plugin;
      this.database = database;
      this.economy = economy;
      this.lang = lang;
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
      if (arguments.length > 1) {
         this.error(sender, "baltop.usage");
         return true;
      }

      int requestedPage = 1;
      if (arguments.length == 1) {
         try {
            requestedPage = Integer.parseInt(arguments[0]);
         } catch (NumberFormatException ignored) {
            this.error(sender, "baltop.usage");
            return true;
         }
      }
      if (requestedPage < 1 || requestedPage > MAX_PAGES) {
         this.error(sender, "baltop.invalid-page", MAX_PAGES);
         return true;
      }

      int page = requestedPage;
      sender.sendMessage(this.lang.message("prefix.economy") + ChatColor.GRAY + this.lang.message("baltop.loading"));
      this.database.fetchLeaderboardAsync(1000).whenComplete((entries, failure) ->
         Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (!this.plugin.isEnabled() || sender instanceof Player player && !player.isOnline()) return;
            if (failure != null) {
               this.plugin.getLogger().warning("Could not load /baltop: " + failure.getMessage());
               this.error(sender, "baltop.failed");
               return;
            }
            this.sendPage(sender, this.filter(WealthBalanceCombiner.combine(this.plugin, entries)), page);
         })
      );
      return true;
   }

   private void sendPage(CommandSender sender, List<DatabaseGateway.LeaderboardEntry> entries, int page) {
      if (entries.isEmpty()) {
         this.error(sender, "baltop.empty");
         return;
      }
      int pageCount = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
      if (page > pageCount) {
         this.error(sender, "baltop.invalid-page", pageCount);
         return;
      }

      sender.sendMessage(this.lang.message("baltop.header", page, pageCount));
      int from = (page - 1) * PAGE_SIZE;
      int to = Math.min(from + PAGE_SIZE, entries.size());
      for (int index = from; index < to; index++) {
         DatabaseGateway.LeaderboardEntry entry = entries.get(index);
         sender.sendMessage(this.lang.message("baltop.entry", index + 1, entry.playerName(),
               this.economy.format(entry.balance().doubleValue())));
      }
      sender.sendMessage(this.lang.message("baltop.footer", page, pageCount));
   }

   private List<DatabaseGateway.LeaderboardEntry> filter(List<DatabaseGateway.LeaderboardEntry> candidates) {
      Set<String> excluded = new HashSet<>();
      for (String name : this.plugin.getConfig().getStringList("wealth-hologram.excluded-players")) {
         if (name != null && !name.isBlank()) excluded.add(name.toLowerCase(Locale.ROOT));
      }
      for (OfflinePlayer operator : Bukkit.getOperators()) {
         String name = operator.getName();
         if (name != null) excluded.add(name.toLowerCase(Locale.ROOT));
      }

      List<DatabaseGateway.LeaderboardEntry> result = new ArrayList<>();
      for (DatabaseGateway.LeaderboardEntry entry : candidates) {
         if (excluded.contains(entry.playerName().toLowerCase(Locale.ROOT))) continue;
         result.add(entry);
         if (result.size() >= MAX_ENTRIES) break;
      }
      return List.copyOf(result);
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] arguments) {
      if (arguments.length != 1) return List.of();
      String input = arguments[0];
      List<String> pages = new ArrayList<>();
      for (int page = 1; page <= MAX_PAGES; page++) {
         String value = Integer.toString(page);
         if (value.startsWith(input)) pages.add(value);
      }
      return pages;
   }

   private void error(CommandSender sender, String key, Object... arguments) {
      sender.sendMessage(this.lang.message("prefix.economy") + ChatColor.RED + this.lang.message(key, arguments));
   }
}
