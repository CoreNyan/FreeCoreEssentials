package io.github.freecoreeconomy.command;

import io.github.freecoreeconomy.vault.BlessingSkinEconomy;
import io.github.freecoreeconomy.vault.TransferResponse;
import io.github.freecoreessentials.lang.Lang;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class EconomyCommands implements CommandExecutor, TabCompleter {
   private final BlessingSkinEconomy economy;
   private final Lang lang;

   public EconomyCommands(BlessingSkinEconomy var1, Lang var2) {
      this.economy = var1;
      this.lang = var2;
   }

   public boolean onCommand(CommandSender var1, Command var2, String var3, String[] var4) {
      return switch (var2.getName().toLowerCase(Locale.ROOT)) {
         case "balance" -> this.balance(var1, var4);
         case "pay" -> this.pay(var1, var4);
         case "eco" -> this.admin(var1, var4);
         default -> false;
      };
   }

   private boolean balance(CommandSender var1, String[] var2) {
      if (var2.length > 1) {
         error(var1, "economy.usage.balance");
         return true;
      }

      String var3;
      boolean var4;
      if (var2.length == 1) {
         var3 = var2[0];
         var4 = !(var1 instanceof Player var6 && var6.getName().equalsIgnoreCase(var3));
      } else {
         if (!(var1 instanceof Player var5)) {
            error(var1, "economy.usage.balance-console");
            return true;
         }

         var3 = var5.getName();
         var4 = false;
      }

      if (var4 && !var1.hasPermission("freecoreeconomy.balance.others")) {
         error(var1, "economy.error.no-balance-others-permission");
         return true;
      }

      OptionalDouble var7 = this.economy.findBalance(var3);
      if (var7.isEmpty()) {
         error(var1, "economy.balance.not-found", var3);
      } else if (var4) {
         success(var1, "economy.balance.other", var3, this.economy.format(var7.getAsDouble()));
      } else {
         success(var1, "economy.balance.self", this.economy.format(var7.getAsDouble()));
      }

      return true;
   }

   private boolean pay(CommandSender var1, String[] var2) {
      if (var1 instanceof Player var3) {
         if (var2.length != 2) {
            error(var1, "economy.usage.pay");
            return true;
         }

         Double var4 = parseAmount(var2[1]);
         if (var4 != null && !(var4 <= 0.0)) {
            TransferResponse var5 = this.economy.transferPlayer(var3.getName(), var2[0], var4);
            switch (var5.status()) {
               case SUCCESS:
                  success(var1, "economy.pay.sent", var2[0], this.economy.format(var5.amount()), this.economy.format(var5.sourceBalance()));
                  Player var6 = onlinePlayer(var2[0]);
                  if (var6 != null) {
                     success(var6, "economy.pay.received", var3.getName(), this.economy.format(var5.amount()), this.economy.format(var5.targetBalance()));
                  }
                  break;
               case SAME_ACCOUNT:
                  error(var1, "economy.pay.same-account");
                  break;
               case TARGET_NOT_FOUND:
                  error(var1, "economy.pay.target-not-found", var2[0]);
                  break;
               case SOURCE_NOT_FOUND:
                  error(var1, "economy.pay.source-not-found");
                  break;
               case INSUFFICIENT_FUNDS:
                  error(var1, "economy.pay.insufficient-funds", this.economy.format(var5.sourceBalance()));
                  break;
               case INVALID_AMOUNT:
                  error(var1, "economy.pay.invalid-precision", this.economy.fractionalDigits());
                  break;
               case DATABASE_FAILURE:
                  error(var1, "economy.pay.database-failure");
            }

            return true;
         } else {
            error(var1, "economy.pay.invalid-amount");
            return true;
         }
      } else {
         error(var1, "economy.pay.player-only");
         return true;
      }
   }

   private boolean admin(CommandSender var1, String[] var2) {
      if (!var1.hasPermission("freecoreeconomy.admin")) {
         error(var1, "economy.error.no-admin-permission");
         return true;
      }

      if (var2.length != 3) {
         error(var1, "economy.usage.admin");
         return true;
      }

      String var3 = var2[0].toLowerCase(Locale.ROOT);
      Double var4 = parseAmount(var2[2]);
      if (var4 != null && !(var4 < 0.0) && (var3.equals("set") || var4 != 0.0)) {
         String var5 = resolvePlayerName(var1, var2[1]);

         EconomyResponse var6 = switch (var3) {
            case "give" -> this.economy.depositPlayer(var5, var4);
            case "take" -> this.economy.withdrawPlayer(var5, var4);
            case "set" -> this.economy.setPlayerBalance(var5, var4);
            default -> null;
         };
         if (var6 == null) {
            error(var1, "economy.error.unknown-operation");
         } else if (!var6.transactionSuccess()) {
            error(var1, translateFailure(var6));
         } else {
            success(var1, "economy.admin.completed", var5, var3, this.economy.format(var4), this.economy.format(var6.balance));
         }

         return true;
      } else {
         error(var1, "economy.admin.invalid-amount");
         return true;
      }
   }

   public List<String> onTabComplete(CommandSender var1, Command var2, String var3, String[] var4) {
      if (var2.getName().equalsIgnoreCase("eco")) {
         if (var4.length == 1) {
            return matches(var4[0], List.of("give", "take", "set"));
         } else {
            return var4.length == 2 ? onlineNames(var4[1]) : List.of();
         }
      } else {
         return var4.length == 1 ? onlineNames(var4[0]) : List.of();
      }
   }

   private static Double parseAmount(String var0) {
      try {
         double var1 = Double.parseDouble(var0);
         return Double.isFinite(var1) ? var1 : null;
      } catch (NumberFormatException var3) {
         return null;
      }
   }

   private static String translateFailure(EconomyResponse var0) {
      String var1 = var0.errorMessage;
      if (var1 == null || var1.isBlank()) {
         return "economy.error.operation-failed";
      } else if (var1.contains("No Blessing Skin account")) {
         return "economy.error.account-not-found";
      } else if (var1.contains("Insufficient funds")) {
         return "economy.error.insufficient-funds";
      } else {
         return var1.contains("decimal places") ? "economy.error.invalid-precision" : "economy.error.operation-failed";
      }
   }

   private static Player onlinePlayer(String var0) {
      return Bukkit.getOnlinePlayers().stream().filter(var1 -> var1.getName().equalsIgnoreCase(var0)).findFirst().orElse(null);
   }

   private static String resolvePlayerName(CommandSender var0, String var1) {
      return !(var0 instanceof Player var2 && (var1.equalsIgnoreCase("me") || var1.equalsIgnoreCase("@s"))) ? var1 : var2.getName();
   }

   private static List<String> onlineNames(String var0) {
      ArrayList var1 = new ArrayList();

      for (Player var3 : Bukkit.getOnlinePlayers()) {
         var1.add(var3.getName());
      }

      return matches(var0, var1);
   }

   private static List<String> matches(String var0, List<String> var1) {
      String var2 = var0.toLowerCase(Locale.ROOT);
      return var1.stream().filter(var1x -> var1x.toLowerCase(Locale.ROOT).startsWith(var2)).sorted(String.CASE_INSENSITIVE_ORDER).toList();
   }

   private void success(CommandSender var0, String var1, Object... var2) {
      var0.sendMessage(this.lang.message("prefix.economy") + ChatColor.GREEN + this.lang.message(var1, var2));
   }

   private void error(CommandSender var0, String var1, Object... var2) {
      var0.sendMessage(this.lang.message("prefix.economy") + ChatColor.RED + this.lang.message(var1, var2));
   }
}
