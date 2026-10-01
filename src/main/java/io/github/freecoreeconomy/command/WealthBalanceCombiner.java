package io.github.freecoreeconomy.command;

import io.github.freecoreeconomy.database.DatabaseGateway;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Combines wallet balances with the optional FreeCoreBank balance snapshot. */
public final class WealthBalanceCombiner {
   private WealthBalanceCombiner() {
   }

   public static List<DatabaseGateway.LeaderboardEntry> combine(
         JavaPlugin plugin, List<DatabaseGateway.LeaderboardEntry> wallets) {
      Map<String, MutableBalance> combined = new LinkedHashMap<>();
      for (DatabaseGateway.LeaderboardEntry wallet : wallets) {
         if (wallet.playerName() == null || wallet.playerName().isBlank()) continue;
         String key = wallet.playerName().toLowerCase(Locale.ROOT);
         combined.computeIfAbsent(key, ignored -> new MutableBalance(wallet.playerName()))
               .wallet = nonNegative(wallet.balance());
      }

      for (Map.Entry<String, BigDecimal> bank : bankBalances(plugin).entrySet()) {
         if (bank.getKey() == null || bank.getKey().isBlank()) continue;
         String key = bank.getKey().toLowerCase(Locale.ROOT);
         combined.computeIfAbsent(key, ignored -> new MutableBalance(bank.getKey()))
               .bank = nonNegative(bank.getValue());
      }

      List<DatabaseGateway.LeaderboardEntry> result = new ArrayList<>(combined.size());
      for (MutableBalance balance : combined.values()) {
         result.add(new DatabaseGateway.LeaderboardEntry(
               balance.playerName, balance.wallet.add(balance.bank)));
      }
      result.sort(Comparator.comparing(DatabaseGateway.LeaderboardEntry::balance).reversed()
            .thenComparing(DatabaseGateway.LeaderboardEntry::playerName, String.CASE_INSENSITIVE_ORDER));
      return List.copyOf(result);
   }

   private static Map<String, BigDecimal> bankBalances(JavaPlugin plugin) {
      Plugin bank = plugin.getServer().getPluginManager().getPlugin("FreeCoreBank");
      if (bank == null || !bank.isEnabled()) return Map.of();
      try {
         Object rawSnapshot = bank.getClass().getMethod("getBalanceSnapshot").invoke(bank);
         if (!(rawSnapshot instanceof Map<?, ?> rawMap)) return Map.of();
         Map<String, BigDecimal> snapshot = new LinkedHashMap<>();
         for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String name) || name.isBlank()) continue;
            BigDecimal balance = decimal(entry.getValue());
            if (balance != null) snapshot.put(name, balance);
         }
         return snapshot;
      } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
         plugin.getLogger().warning("Could not read FreeCoreBank balances for wealth leaderboard: "
               + exception.getMessage());
         return Map.of();
      }
   }

   private static BigDecimal decimal(Object value) {
      if (value instanceof BigDecimal decimal) return decimal;
      if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
      if (value == null) return null;
      try {
         return new BigDecimal(value.toString());
      } catch (NumberFormatException ignored) {
         return null;
      }
   }

   private static BigDecimal nonNegative(BigDecimal value) {
      return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
   }

   private static final class MutableBalance {
      private final String playerName;
      private BigDecimal wallet = BigDecimal.ZERO;
      private BigDecimal bank = BigDecimal.ZERO;

      private MutableBalance(String playerName) {
         this.playerName = playerName;
      }
   }
}
