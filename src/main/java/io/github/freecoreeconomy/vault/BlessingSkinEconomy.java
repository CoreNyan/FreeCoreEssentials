package io.github.freecoreeconomy.vault;

import io.github.freecoreeconomy.database.DatabaseException;
import io.github.freecoreeconomy.database.DatabaseGateway;
import io.github.freecoreeconomy.database.TransactionOutcome;
import io.github.freecoreeconomy.database.TransferOutcome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

public final class BlessingSkinEconomy implements Economy {
   private static final long WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10L);
   private final Plugin plugin;
   private final DatabaseGateway database;
   private final String singularCurrency;
   private final String pluralCurrency;
   private final int fractionalDigits;
   private final AtomicLong lastWarning = new AtomicLong();
   private final ConcurrentMap<String, String> accountAliases = new ConcurrentHashMap<>();
   private volatile boolean enabled = true;

   public BlessingSkinEconomy(Plugin var1, DatabaseGateway var2, FileConfiguration var3) {
      this.plugin = var1;
      this.database = var2;
      this.singularCurrency = var3.getString("economy.currency-singular", "H币");
      this.pluralCurrency = var3.getString("economy.currency-plural", "H币");
      this.fractionalDigits = Math.max(0, Math.min(8, var3.getInt("economy.fractional-digits", 0)));
   }

   public void disable() {
      this.enabled = false;
   }

   public boolean isEnabled() {
      return this.enabled && this.plugin.isEnabled();
   }

   public String getName() {
      return "FreeCoreEssentials";
   }

   public void mapPlayerAccount(String var1, String var2) {
      if (validPlayerName(var1) && validPlayerName(var2)) {
         this.accountAliases.put(var1.toLowerCase(Locale.ROOT), var2);
      }
   }

   public boolean hasBankSupport() {
      return false;
   }

   public int fractionalDigits() {
      return this.fractionalDigits;
   }

   public String format(double var1) {
      return this.formatAmount(var1) + " " + (Math.abs(var1) == 1.0 ? this.singularCurrency : this.pluralCurrency);
   }

   public String formatAmount(double var1) {
      NumberFormat var3 = NumberFormat.getNumberInstance(Locale.ROOT);
      var3.setGroupingUsed(false);
      var3.setMinimumFractionDigits(this.fractionalDigits);
      var3.setMaximumFractionDigits(this.fractionalDigits);
      return var3.format(var1);
   }

   public String currencyNamePlural() {
      return this.pluralCurrency;
   }

   public String currencyNameSingular() {
      return this.singularCurrency;
   }

   public boolean hasAccount(String var1) {
      if (!validPlayerName(var1)) {
         return false;
      }

      try {
         return this.database.hasAccount(this.resolveAccountName(var1));
      } catch (DatabaseException var3) {
         this.warn(var3);
         return false;
      }
   }

   public boolean hasAccount(OfflinePlayer var1) {
      return this.hasAccount(nameOf(var1));
   }

   public boolean hasAccount(String var1, String var2) {
      return this.hasAccount(var1);
   }

   public boolean hasAccount(OfflinePlayer var1, String var2) {
      return this.hasAccount(var1);
   }

   public double getBalance(String var1) {
      if (!validPlayerName(var1)) {
         return 0.0;
      }

      try {
         return this.database.getBalance(this.resolveAccountName(var1)).map(BigDecimal::doubleValue).orElse(0.0);
      } catch (DatabaseException var3) {
         this.warn(var3);
         return 0.0;
      }
   }

   public double getBalance(OfflinePlayer var1) {
      return this.getBalance(nameOf(var1));
   }

   public double getBalance(String var1, String var2) {
      return this.getBalance(var1);
   }

   public double getBalance(OfflinePlayer var1, String var2) {
      return this.getBalance(var1);
   }

   public boolean has(String var1, double var2) {
      Optional var4 = this.normalizeAmount(var2);
      return var4.isPresent() && this.getBalanceDecimal(var1).map(var1x -> var1x.compareTo((BigDecimal)var4.get()) >= 0).orElse(false);
   }

   public boolean has(OfflinePlayer var1, double var2) {
      return this.has(nameOf(var1), var2);
   }

   public boolean has(String var1, String var2, double var3) {
      return this.has(var1, var3);
   }

   public boolean has(OfflinePlayer var1, String var2, double var3) {
      return this.has(var1, var3);
   }

   public EconomyResponse withdrawPlayer(String var1, double var2) {
      return this.transact(var1, var2, false);
   }

   public EconomyResponse withdrawPlayer(OfflinePlayer var1, double var2) {
      return this.withdrawPlayer(nameOf(var1), var2);
   }

   public EconomyResponse withdrawPlayer(String var1, String var2, double var3) {
      return this.withdrawPlayer(var1, var3);
   }

   public EconomyResponse withdrawPlayer(OfflinePlayer var1, String var2, double var3) {
      return this.withdrawPlayer(var1, var3);
   }

   public EconomyResponse depositPlayer(String var1, double var2) {
      return this.transact(var1, var2, true);
   }

   public EconomyResponse depositPlayer(OfflinePlayer var1, double var2) {
      return this.depositPlayer(nameOf(var1), var2);
   }

   public EconomyResponse depositPlayer(String var1, String var2, double var3) {
      return this.depositPlayer(var1, var3);
   }

   public EconomyResponse depositPlayer(OfflinePlayer var1, String var2, double var3) {
      return this.depositPlayer(var1, var3);
   }

   private EconomyResponse transact(String var1, double var2, boolean var4) {
      if (!validPlayerName(var1)) {
         return this.failure(var2, 0.0, "Player has no usable name");
      }

      Optional var5 = this.normalizeAmount(var2);
      if (var5.isEmpty()) {
         return this.failure(var2, this.getBalance(var1), "Amount must be finite, non-negative, and use at most " + this.fractionalDigits + " decimal places");
      }

      BigDecimal var6 = (BigDecimal)var5.get();
      String var7 = this.resolveAccountName(var1);

      try {
         if (var6.signum() == 0) {
            return this.database
               .getBalance(var7)
               .map(var0 -> new EconomyResponse(0.0, var0.doubleValue(), ResponseType.SUCCESS, null))
               .orElseGet(() -> this.failure(0.0, 0.0, "No Blessing Skin account matches player_name=" + var7));
         }

         TransactionOutcome var8 = var4 ? this.database.deposit(var7, var6) : this.database.withdraw(var7, var6);

         return switch (var8.status()) {
            case SUCCESS -> new EconomyResponse(var6.doubleValue(), var8.balance().doubleValue(), ResponseType.SUCCESS, null);
            case ACCOUNT_NOT_FOUND -> this.failure(var6.doubleValue(), 0.0, "No Blessing Skin account matches player_name=" + var7);
            case INSUFFICIENT_FUNDS -> this.failure(var6.doubleValue(), var8.balance().doubleValue(), "Insufficient funds");
         };
      } catch (DatabaseException var9) {
         this.warn(var9);
         return this.failure(var6.doubleValue(), 0.0, userFacingDatabaseError(var9));
      }
   }

   public OptionalDouble findBalance(String var1) {
      return this.getBalanceDecimal(var1).map(var0 -> OptionalDouble.of(var0.doubleValue())).orElseGet(OptionalDouble::empty);
   }

   public EconomyResponse setPlayerBalance(String var1, double var2) {
      if (!validPlayerName(var1)) {
         return this.failure(var2, 0.0, "Player has no usable name");
      }

      Optional var4 = this.normalizeAmount(var2);
      if (var4.isEmpty()) {
         return this.failure(var2, this.getBalance(var1), "Amount must be finite, non-negative, and use at most " + this.fractionalDigits + " decimal places");
      }

      try {
         String var5 = this.resolveAccountName(var1);
         TransactionOutcome var6 = this.database.setBalance(var5, (BigDecimal)var4.get());
         return var6.status() == TransactionOutcome.Status.ACCOUNT_NOT_FOUND
            ? this.failure(var2, 0.0, "No Blessing Skin account matches player_name=" + var5)
            : new EconomyResponse(((BigDecimal)var4.get()).doubleValue(), var6.balance().doubleValue(), ResponseType.SUCCESS, null);
      } catch (DatabaseException var7) {
         this.warn(var7);
         return this.failure(var2, 0.0, userFacingDatabaseError(var7));
      }
   }

   public TransferResponse transferPlayer(String var1, String var2, double var3) {
      if (!validPlayerName(var1)) {
         return this.transferFailure(TransferResponse.Status.SOURCE_NOT_FOUND, var3, "Source account is missing");
      }

      if (!validPlayerName(var2)) {
         return this.transferFailure(TransferResponse.Status.TARGET_NOT_FOUND, var3, "Target account is missing");
      }

      if (var1.equalsIgnoreCase(var2)) {
         return this.transferFailure(TransferResponse.Status.SAME_ACCOUNT, var3, "Source and target accounts are the same");
      }

      Optional var5 = this.normalizeAmount(var3);
      if (!var5.isEmpty() && ((BigDecimal)var5.get()).signum() > 0) {
         try {
            TransferOutcome var6 = this.database.transfer(this.resolveAccountName(var1), this.resolveAccountName(var2), (BigDecimal)var5.get());

            return switch (var6.status()) {
               case SUCCESS -> new TransferResponse(
                  TransferResponse.Status.SUCCESS,
                  ((BigDecimal)var5.get()).doubleValue(),
                  var6.sourceBalance().doubleValue(),
                  var6.targetBalance().doubleValue(),
                  null
               );
               case SOURCE_NOT_FOUND -> this.transferFailure(
                  TransferResponse.Status.SOURCE_NOT_FOUND, ((BigDecimal)var5.get()).doubleValue(), "Source Blessing Skin account was not found"
               );
               case TARGET_NOT_FOUND -> new TransferResponse(
                  TransferResponse.Status.TARGET_NOT_FOUND,
                  ((BigDecimal)var5.get()).doubleValue(),
                  var6.sourceBalance().doubleValue(),
                  0.0,
                  "Target Blessing Skin account was not found"
               );
               case INSUFFICIENT_FUNDS -> new TransferResponse(
                  TransferResponse.Status.INSUFFICIENT_FUNDS,
                  ((BigDecimal)var5.get()).doubleValue(),
                  var6.sourceBalance().doubleValue(),
                  var6.targetBalance().doubleValue(),
                  "Insufficient funds"
               );
            };
         } catch (DatabaseException var7) {
            this.warn(var7);
            return this.transferFailure(TransferResponse.Status.DATABASE_FAILURE, ((BigDecimal)var5.get()).doubleValue(), userFacingDatabaseError(var7));
         }
      } else {
         return this.transferFailure(
            TransferResponse.Status.INVALID_AMOUNT, var3, "Amount must be positive and use at most " + this.fractionalDigits + " decimal places"
         );
      }
   }

   private TransferResponse transferFailure(TransferResponse.Status var1, double var2, String var4) {
      return new TransferResponse(var1, var2, 0.0, 0.0, var4);
   }

   private Optional<BigDecimal> getBalanceDecimal(String var1) {
      if (!validPlayerName(var1)) {
         return Optional.empty();
      }

      try {
         return this.database.getBalance(this.resolveAccountName(var1));
      } catch (DatabaseException var3) {
         this.warn(var3);
         return Optional.empty();
      }
   }

   private Optional<BigDecimal> normalizeAmount(double var1) {
      if (Double.isFinite(var1) && !(var1 < 0.0)) {
         try {
            return Optional.of(BigDecimal.valueOf(var1).setScale(this.fractionalDigits, RoundingMode.UNNECESSARY));
         } catch (ArithmeticException var4) {
            return Optional.empty();
         }
      } else {
         return Optional.empty();
      }
   }

   private EconomyResponse failure(double var1, double var3, String var5) {
      return new EconomyResponse(var1, var3, ResponseType.FAILURE, var5);
   }

   private static String userFacingDatabaseError(DatabaseException var0) {
      String var1 = var0.getMessage();
      return var1 != null && !var1.isBlank() ? var1 : "Database operation failed";
   }

   private EconomyResponse notImplemented() {
      return new EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "Bank accounts are not supported");
   }

   private void warn(DatabaseException var1) {
      long var2 = System.nanoTime();
      long var4 = this.lastWarning.get();
      if ((var4 == 0L || var2 - var4 >= WARNING_INTERVAL_NANOS) && this.lastWarning.compareAndSet(var4, var2)) {
         this.plugin.getLogger().warning(var1.getMessage());
      }
   }

   private static String nameOf(OfflinePlayer var0) {
      return var0 == null ? null : var0.getName();
   }

   private static boolean validPlayerName(String var0) {
      return var0 != null && !var0.isBlank();
   }

   private String resolveAccountName(String var1) {
      return !validPlayerName(var1) ? var1 : this.accountAliases.getOrDefault(var1.toLowerCase(Locale.ROOT), var1);
   }

   public EconomyResponse createBank(String var1, String var2) {
      return this.notImplemented();
   }

   public EconomyResponse createBank(String var1, OfflinePlayer var2) {
      return this.notImplemented();
   }

   public EconomyResponse deleteBank(String var1) {
      return this.notImplemented();
   }

   public EconomyResponse bankBalance(String var1) {
      return this.notImplemented();
   }

   public EconomyResponse bankHas(String var1, double var2) {
      return this.notImplemented();
   }

   public EconomyResponse bankWithdraw(String var1, double var2) {
      return this.notImplemented();
   }

   public EconomyResponse bankDeposit(String var1, double var2) {
      return this.notImplemented();
   }

   public EconomyResponse isBankOwner(String var1, String var2) {
      return this.notImplemented();
   }

   public EconomyResponse isBankOwner(String var1, OfflinePlayer var2) {
      return this.notImplemented();
   }

   public EconomyResponse isBankMember(String var1, String var2) {
      return this.notImplemented();
   }

   public EconomyResponse isBankMember(String var1, OfflinePlayer var2) {
      return this.notImplemented();
   }

   public List<String> getBanks() {
      return Collections.emptyList();
   }

   public boolean createPlayerAccount(String var1) {
      return this.hasAccount(var1);
   }

   public boolean createPlayerAccount(OfflinePlayer var1) {
      return this.hasAccount(var1);
   }

   public boolean createPlayerAccount(String var1, String var2) {
      return this.hasAccount(var1);
   }

   public boolean createPlayerAccount(OfflinePlayer var1, String var2) {
      return this.hasAccount(var1);
   }
}
