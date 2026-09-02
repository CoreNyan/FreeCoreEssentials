package io.github.freecoreeconomy.bedrock;

import io.github.freecoreeconomy.database.BedrockAccountResult;
import io.github.freecoreeconomy.database.DatabaseGateway;
import io.github.freecoreeconomy.vault.BlessingSkinEconomy;
import io.github.freecoreessentials.lang.Lang;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

public final class BedrockAccountManager implements Listener, CommandExecutor, AutoCloseable {
   private final Plugin plugin;
   private final DatabaseGateway database;
   private final BlessingSkinEconomy economy;
   private final Lang lang;
   private final FloodgateApi floodgate;
   private final Map<UUID, BukkitTask> reminders = new ConcurrentHashMap<>();
   private final int bcryptCost;
   private final int minimumPasswordLength;
   private final long reminderPeriodTicks;

   public BedrockAccountManager(Plugin var1, DatabaseGateway var2, BlessingSkinEconomy var3, Lang var4, FileConfiguration var5) {
      this.plugin = var1;
      this.database = var2;
      this.economy = var3;
      this.lang = var4;
      this.floodgate = FloodgateApi.getInstance();
      this.bcryptCost = bounded(var5.getInt("bedrock-accounts.bcrypt-cost", 12), 10, 15);
      this.minimumPasswordLength = bounded(var5.getInt("bedrock-accounts.password-min-length", 8), 1, 72);
      long var6 = Math.max(1L, var5.getLong("bedrock-accounts.password-reminder-minutes", 5L));
      this.reminderPeriodTicks = var6 * 60L * 20L;
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerJoin(PlayerJoinEvent var1) {
      Player var2 = var1.getPlayer();
      UUID var3 = var2.getUniqueId();
      if (this.floodgate.isFloodgatePlayer(var3)) {
         FloodgatePlayer var4 = this.floodgate.getPlayer(var3);
         if (var4 != null && var4.getXuid() != null && !var4.getXuid().isBlank()) {
            String var5 = var4.getXuid();
            String var6 = var2.getName();
            String var7 = var4.getUsername();
            if (var7 == null || var7.isBlank()) {
               var7 = var6;
            }

            String var8 = var7;
            this.database
               .syncBedrockAccountAsync(var5, var8)
               .whenComplete((var4x, var5x) -> this.runOnServerThread(() -> this.finishAccountSync(var3, var6, var8, var4x, var5x)));
         } else {
            this.plugin.getLogger().warning("Floodgate did not expose an XUID for " + var2.getName());
         }
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerQuit(PlayerQuitEvent var1) {
      this.cancelReminder(var1.getPlayer().getUniqueId());
   }

   public boolean onCommand(CommandSender var1, Command var2, String var3, String[] var4) {
      if (var1 instanceof Player var5) {
         if (!this.floodgate.isFloodgatePlayer(var5.getUniqueId())) {
            this.sendError(var1, "bedrock-account.command.only-floodgate");
            return true;
         } else if (var4.length != 1) {
            this.sendError(var1, "bedrock-account.command.usage");
            return true;
         } else {
            byte[] var6 = var4[0].getBytes(StandardCharsets.UTF_8);
            if (var4[0].length() < this.minimumPasswordLength) {
                this.sendError(var1, "bedrock-account.command.password-too-short", this.minimumPasswordLength);
               return true;
            } else if (var6.length > 72) {
                this.sendError(var1, "bedrock-account.command.password-too-long");
               return true;
            } else {
               FloodgatePlayer var7 = this.floodgate.getPlayer(var5.getUniqueId());
               if (var7 != null && var7.getXuid() != null) {
                  UUID var8 = var5.getUniqueId();
                  char[] var9 = var4[0].toCharArray();
                   this.sendInfo(var1, "bedrock-account.command.password-updating");
                  this.database.setBedrockPasswordAsync(var7.getXuid(), var9, this.bcryptCost).whenComplete((var3x, var4x) -> this.runOnServerThread(() -> {
                     Player var5x = Bukkit.getPlayer(var8);
                     if (var4x != null) {
                        this.logFailure("Could not update the Bedrock website password for " + var5.getName(), var4x);
                        if (var5x != null) {
                            this.sendError(var5x, "bedrock-account.command.password-update-failed");
                        }
                     } else if (!Boolean.TRUE.equals(var3x)) {
                        if (var5x != null) {
                            this.sendError(var5x, "bedrock-account.command.account-not-found");
                        }
                     } else {
                        this.cancelReminder(var8);
                        if (var5x != null) {
                            this.sendSuccess(var5x, "bedrock-account.command.password-updated");
                        }
                     }
                  }));
                  return true;
               } else {
                   this.sendError(var1, "bedrock-account.command.xuid-unavailable");
                  return true;
               }
            }
         }
      } else {
          this.sendError(var1, "bedrock-account.command.player-only");
         return true;
      }
   }

   private void finishAccountSync(UUID var1, String var2, String var3, BedrockAccountResult var4, Throwable var5) {
      if (var5 != null) {
         this.logFailure("Could not synchronize the Bedrock account for " + var3, var5);
         Player var7 = Bukkit.getPlayer(var1);
         if (var7 != null) {
            this.sendError(var7, "bedrock-account.sync.failed");
         }
      } else {
         this.economy.mapPlayerAccount(var2, var4.accountName());
         Player var6 = Bukkit.getPlayer(var1);
         if (var6 != null) {
            if (var4.status() == BedrockAccountResult.Status.CREATED) {
                this.sendSuccess(var6, "bedrock-account.sync.created");
            } else if (var4.status() == BedrockAccountResult.Status.RENAMED) {
                this.sendSuccess(var6, "bedrock-account.sync.renamed");
            }

            if (var4.nameAdjusted()) {
                this.sendInfo(var6, "bedrock-account.sync.name-adjusted", var4.accountName());
            }

             this.sendInfo(var6, "bedrock-account.sync.account-details", var4.accountName(), var4.email());
            if (var4.passwordSet()) {
               this.cancelReminder(var1);
            } else {
                this.sendWarning(var6, "bedrock-account.sync.password-reminder");
               this.scheduleReminder(var1);
            }
         }
      }
   }

   private void scheduleReminder(UUID var1) {
      this.cancelReminder(var1);
      BukkitTask var2 = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
         Player var2x = Bukkit.getPlayer(var1);
         if (var2x != null && this.floodgate.isFloodgatePlayer(var1)) {
            this.sendWarning(var2x, "bedrock-account.sync.password-reminder");
         }
      }, this.reminderPeriodTicks, this.reminderPeriodTicks);
      this.reminders.put(var1, var2);
   }

   private void cancelReminder(UUID var1) {
      BukkitTask var2 = this.reminders.remove(var1);
      if (var2 != null) {
         var2.cancel();
      }
   }

   private void runOnServerThread(Runnable var1) {
      if (this.plugin.isEnabled()) {
         Bukkit.getScheduler().runTask(this.plugin, var1);
      }
   }

   private void logFailure(String var1, Throwable var2) {
      Throwable var3 = var2;

      while (var3.getCause() != null) {
         var3 = var3.getCause();
      }

      this.plugin.getLogger().log(Level.WARNING, var1 + ": " + var3.getMessage(), var3);
   }

   private static int bounded(int var0, int var1, int var2) {
      return Math.max(var1, Math.min(var2, var0));
   }

   private void sendSuccess(CommandSender var0, String var1, Object... var2) {
      var0.sendMessage(this.lang.message("prefix.bedrock-account") + ChatColor.GREEN + this.lang.message(var1, var2));
   }

   private void sendInfo(CommandSender var0, String var1, Object... var2) {
      var0.sendMessage(this.lang.message("prefix.bedrock-account") + ChatColor.AQUA + this.lang.message(var1, var2));
   }

   private void sendWarning(CommandSender var0, String var1, Object... var2) {
      var0.sendMessage(this.lang.message("prefix.bedrock-account") + ChatColor.YELLOW + this.lang.message(var1, var2));
   }

   private void sendError(CommandSender var0, String var1, Object... var2) {
      var0.sendMessage(this.lang.message("prefix.bedrock-account") + ChatColor.RED + this.lang.message(var1, var2));
   }

   @Override
   public void close() {
      HandlerList.unregisterAll(this);
      this.reminders.values().forEach(BukkitTask::cancel);
      this.reminders.clear();
   }
}
