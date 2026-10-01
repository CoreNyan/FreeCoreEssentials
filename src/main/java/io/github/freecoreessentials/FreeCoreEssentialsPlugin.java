package io.github.freecoreessentials;

import io.github.freecoreeconomy.bedrock.BedrockAccountManager;
import io.github.freecoreeconomy.command.EconomyCommands;
import io.github.freecoreeconomy.command.BalanceTopCommand;
import io.github.freecoreeconomy.database.DatabaseGateway;
import io.github.freecoreeconomy.skin.SkinIntegration;
import io.github.freecoreeconomy.vault.BlessingSkinEconomy;
import io.github.freecoreessentials.command.FreeCoreEssentialsCommand;
import io.github.freecoreessentials.command.BackCommand;
import io.github.freecoreessentials.command.HomeCommand;
import io.github.freecoreessentials.command.SpawnCommand;
import io.github.freecoreessentials.command.WarpCommand;
import io.github.freecoreessentials.command.MenuCommand;
import io.github.freecoreessentials.command.RandomTeleportCommand;
import io.github.freecoreessentials.display.WealthHologramService;
import io.github.freecoreessentials.display.ScoreboardCompatibilityService;
import io.github.freecoreessentials.crossserver.CrossServerCommandService;
import io.github.freecoreessentials.crossserver.FotiaTagsSyncService;
import io.github.freecoreessentials.crossserver.LastLocationService;
import io.github.freecoreessentials.lang.Lang;
import io.github.freecoreessentials.listener.GameModeFeedbackListener;
import io.github.freecoreessentials.listener.LobbyBoundaryListener;
import io.github.freecoreessentials.listener.JoinQuitMessageListener;
import io.github.freecoreessentials.listener.AutoRespawnListener;
import io.github.freecoreessentials.listener.FirstJoinSpawnListener;
import io.github.freecoreessentials.listener.MenuShortcutListener;
import io.github.freecoreessentials.listener.VanishSyncListener;
import io.github.freecoreessentials.placeholder.FreeCorePlaceholderExpansion;
import io.github.freecoreessentials.teleport.WarpService;
import io.github.freecoreessentials.teleport.BackService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class FreeCoreEssentialsPlugin extends JavaPlugin {
   private DatabaseGateway database;
   private BlessingSkinEconomy economy;
   private BedrockAccountManager bedrockAccounts;
   private SkinIntegration skinIntegration;
   private Lang lang;
   private WealthHologramService wealthHolograms;
   private WarpService warps;
   private BackService backs;
   private FreeCorePlaceholderExpansion placeholders;
   private ScoreboardCompatibilityService scoreboardCompatibility;
   private CrossServerCommandService crossServer;
   private LastLocationService lastLocations;
   private FotiaTagsSyncService fotiaTagsSync;

   public void onEnable() {
      this.migrateLegacyConfig();
      this.saveDefaultConfig();
      this.lang = new Lang(this);
      this.registerMainCommand();
      this.registerMenuCommand();
      this.enableCrossServerCommands();
      this.enableTeleports();
      this.getServer().getPluginManager().registerEvents(new GameModeFeedbackListener(this.lang), this);
      this.getServer().getPluginManager().registerEvents(new LobbyBoundaryListener(this, this.lang), this);
      this.getServer().getPluginManager().registerEvents(new JoinQuitMessageListener(this.lang), this);
      this.getServer().getPluginManager().registerEvents(new AutoRespawnListener(this), this);
      this.getServer().getPluginManager().registerEvents(new FirstJoinSpawnListener(this), this);
       this.lastLocations = new LastLocationService(this);
       this.getServer().getPluginManager().registerEvents(this.lastLocations, this);
      this.getServer().getPluginManager().registerEvents(new MenuShortcutListener(this), this);
      if (this.getServer().getPluginManager().isPluginEnabled("SimpleVanish")) {
         this.getServer().getPluginManager().registerEvents(new VanishSyncListener(this), this);
         this.getLogger().info("Enabled SimpleVanish state sync for proxy TAB.");
      }
      this.getLogger().info("Initializing MySQL-backed Vault economy provider...");

      try {
         this.database = DatabaseGateway.create(this, this.getConfig());
         this.economy = new BlessingSkinEconomy(this, this.database, this.getConfig());
         this.getServer().getServicesManager().register(Economy.class, this.economy, this, ServicePriority.Highest);
         RegisteredServiceProvider var1 = this.getServer().getServicesManager().getRegistration(Economy.class);
         if (var1 == null || var1.getProvider() != this.economy) {
            throw new IllegalStateException("FreeCoreEssentials was registered, but is not Vault's active highest-priority provider");
         }

         this.registerEconomyCommands();
         this.getLogger().info("Registered as Vault Economy provider with Highest priority.");
         this.database.verifySchemaAsync().whenComplete((var1x, var2x) -> this.runOnServerThread(() -> {
            if (var2x != null) {
               this.failStartup("Database schema verification failed", var2x);
            } else {
               this.getLogger().info("Balances are read from and written directly to Blessing Skin users.score.");
               this.enableSkinIntegration();
               this.enableBedrockAccounts();
               this.enableWealthHolograms();
               this.enablePlaceholders();
               this.enableScoreboardCompatibility();
            }
         }));
      } catch (Exception var2) {
         this.failStartup("Failed to start FreeCoreEssentials", var2);
      }
   }

   public void onDisable() {
      if (this.lastLocations != null) {
         this.lastLocations.close();
         this.lastLocations = null;
      }
      this.getServer().getServicesManager().unregisterAll(this);
      if (this.placeholders != null) {
         this.placeholders.close();
         this.placeholders = null;
      }
      if (this.scoreboardCompatibility != null) {
         this.scoreboardCompatibility.close();
         this.scoreboardCompatibility = null;
      }
      if (this.crossServer != null) {
         this.crossServer.close();
         this.crossServer = null;
      }
      if (this.fotiaTagsSync != null) {
         this.fotiaTagsSync.close();
         this.fotiaTagsSync = null;
      }
      if (this.wealthHolograms != null) {
         this.wealthHolograms.close();
         this.wealthHolograms = null;
      }
      if (this.bedrockAccounts != null) {
         this.bedrockAccounts.close();
         this.bedrockAccounts = null;
      }

      if (this.skinIntegration != null) {
         this.skinIntegration.close();
         this.skinIntegration = null;
      }

      if (this.economy != null) {
         this.economy.disable();
      }

      this.closeDatabase();
   }

   private void enableBedrockAccounts() {
      if (!this.getConfig().getBoolean("bedrock-accounts.enabled", true)) {
         this.getLogger().info("Floodgate account integration is disabled in config.yml.");
      } else if (!this.getServer().getPluginManager().isPluginEnabled("floodgate")) {
         this.getLogger().warning("Floodgate was not found; Bedrock account integration is disabled.");
      } else {
         this.bedrockAccounts = new BedrockAccountManager(this, this.database, this.economy, this.lang, this.getConfig());
         this.getServer().getPluginManager().registerEvents(this.bedrockAccounts, this);
         PluginCommand var1 = this.requireCommand("setpassword");
         var1.setExecutor(this.bedrockAccounts);
         this.getLogger().info("Floodgate Bedrock account takeover and password management are enabled.");
      }
   }

   private void enableSkinIntegration() {
      if (!this.getConfig().getBoolean("skins.enabled", false)) {
         this.getLogger().info("Cross-edition Blessing Skin integration is disabled in config.yml.");
      } else {
         String var1 = this.getConfig().getString("skins.texture-url-template", "");
         if (var1 == null || var1.isBlank() || !var1.contains("{hash}")) {
            this.getLogger().warning("Skin integration requires skins.texture-url-template containing {hash}.");
         } else if (!this.database.skinIntegrationAvailable()) {
            this.getLogger().warning("Skin integration is enabled, but players.tid_skin and textures.hash/type are unavailable.");
         } else {
            if (!this.getServer().getPluginManager().isPluginEnabled("Geyser-Spigot")) {
               this.getLogger().warning("Geyser-Spigot was not found; only Java GameProfile skin publishing is available.");
            }

            this.skinIntegration = new SkinIntegration(this, this.database, this.getConfig());
         }
      }
   }

   private void enableWealthHolograms() {
      if (!this.getConfig().getBoolean("wealth-hologram.enabled", true)) {
         this.getLogger().info("Wealth holograms are disabled in config.yml.");
         return;
      }
      this.wealthHolograms = new WealthHologramService(this, this.database, this.economy, this.lang);
      this.getServer().getPluginManager().registerEvents(this.wealthHolograms, this);
      this.wealthHolograms.start();
   }

   private void enablePlaceholders() {
      if (!this.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
         this.getLogger().info("PlaceholderAPI was not found; FreeCore placeholders are disabled.");
         return;
      }

      this.placeholders = new FreeCorePlaceholderExpansion(this, this.economy);
      if (this.placeholders.register()) {
         this.placeholders.start();
         this.getLogger().info("Registered PlaceholderAPI placeholders: %freecore_world%, %freecore_hcoin%, %freecore_title%, %freecore_tab_name%.");
      } else {
         this.getLogger().warning("Could not register FreeCore PlaceholderAPI placeholders.");
         this.placeholders = null;
      }
   }

   private void enableScoreboardCompatibility() {
      if (!this.getServer().getPluginManager().isPluginEnabled("FotiaTags")
              || !this.getServer().getPluginManager().isPluginEnabled("DonutScoreboard")) {
         return;
      }
      this.scoreboardCompatibility = new ScoreboardCompatibilityService(this);
      this.scoreboardCompatibility.start();
      this.getLogger().info("Enabled FotiaTags and DonutScoreboard compatibility for nametags and Bedrock boards.");
   }

   private void registerEconomyCommands() {
      EconomyCommands var1 = new EconomyCommands(this.economy, this.lang);

      for (String var5 : new String[]{"balance", "pay", "eco"}) {
         PluginCommand var6 = this.requireCommand(var5);
         var6.setExecutor(var1);
         var6.setTabCompleter(var1);
      }

      BalanceTopCommand balanceTop = new BalanceTopCommand(this, this.database, this.economy, this.lang);
      PluginCommand balanceTopCommand = this.requireCommand("baltop");
      balanceTopCommand.setExecutor(balanceTop);
      balanceTopCommand.setTabCompleter(balanceTop);
   }

   private void registerMainCommand() {
      PluginCommand var1 = this.requireCommand("fce");
      FreeCoreEssentialsCommand var2 = new FreeCoreEssentialsCommand(this, this.lang);
      var1.setExecutor(var2);
      var1.setTabCompleter(var2);
   }

   private void registerMenuCommand() {
      MenuCommand menuCommand = new MenuCommand(this, this.lang);
      this.requireCommand("menu").setExecutor(menuCommand);
      this.requireCommand("m").setExecutor(menuCommand);
   }

   private void enableCrossServerCommands() {
      this.crossServer = new CrossServerCommandService(this, this.lang);
      this.getServer().getPluginManager().registerEvents(this.crossServer, this);
      this.requireCommand("invsee").setExecutor(this.crossServer);
      this.crossServer.start();
      this.fotiaTagsSync = new FotiaTagsSyncService(this);
      this.fotiaTagsSync.start();
      this.getLogger().info("Enabled cross-server /give, /tp and /invsee bridge.");
   }


   private void enableTeleports() {
      this.warps = new WarpService(this);
      this.warps.load();
      this.backs = new BackService(this.lang);
      this.getServer().getPluginManager().registerEvents(this.backs, this);
      WarpCommand warpCommand = new WarpCommand(this.warps, this.lang);
      SpawnCommand spawnCommand = new SpawnCommand(this.warps, this.lang, false);
      SpawnCommand setSpawnCommand = new SpawnCommand(this.warps, this.lang, true);
      BackCommand backCommand = new BackCommand(this.backs, this.lang);
      HomeCommand homeCommand = new HomeCommand(this.warps, this.lang, false);
      HomeCommand setHomeCommand = new HomeCommand(this.warps, this.lang, true);
      this.requireCommand("warp").setExecutor(warpCommand);
      this.requireCommand("warp").setTabCompleter(warpCommand);
      this.requireCommand("spawn").setExecutor(spawnCommand);
      this.requireCommand("spawn").setTabCompleter(spawnCommand);
      this.requireCommand("setspawn").setExecutor(setSpawnCommand);
      this.requireCommand("setspawn").setTabCompleter(setSpawnCommand);
      this.requireCommand("back").setExecutor(backCommand);
      this.requireCommand("back").setTabCompleter(backCommand);
      this.requireCommand("home").setExecutor(homeCommand);
      this.requireCommand("home").setTabCompleter(homeCommand);
      this.requireCommand("sethome").setExecutor(setHomeCommand);
      this.requireCommand("sethome").setTabCompleter(setHomeCommand);
      this.requireCommand("rtp").setExecutor(new RandomTeleportCommand(this, this.lang));
   }

   public boolean createWealthHologram(org.bukkit.Location location) {
      return this.wealthHolograms != null && this.wealthHolograms.create(location);
   }

   public boolean removeWealthHologram(org.bukkit.Location location) {
      return this.wealthHolograms != null && this.wealthHolograms.removeNearby(location);
   }

   public boolean refreshWealthHologram(org.bukkit.Location location) {
      return this.wealthHolograms != null && this.wealthHolograms.refreshNearby(location);
   }

   public void reloadPluginConfiguration() {
      this.reloadConfig();
      this.lang.reload();
      if (this.warps != null) {
         this.warps.load();
      }
      if (this.wealthHolograms != null) {
         this.wealthHolograms.reload();
      }
   }

   private PluginCommand requireCommand(String var1) {
      PluginCommand var2 = this.getCommand(var1);
      if (var2 == null) {
         throw new IllegalStateException("Command is missing from plugin.yml: " + var1);
      } else {
         return var2;
      }
   }

   private void migrateLegacyConfig() {
      Path var1 = this.getDataFolder().toPath().resolve("config.yml");
      Path var2 = this.getDataFolder().toPath().getParent().resolve("FreeCoreEconomy").resolve("config.yml");
      if (!Files.exists(var1) && Files.isRegularFile(var2)) {
         try {
            Files.createDirectories(var1.getParent());
            Files.copy(var2, var1);
            this.getLogger().info("Migrated database configuration from plugins/FreeCoreEconomy/config.yml.");
         } catch (IOException var4) {
            this.getLogger().log(Level.WARNING, "Could not migrate the old FreeCoreEconomy config.yml", var4);
         }
      }
   }

   private void runOnServerThread(Runnable var1) {
      if (this.isEnabled()) {
         this.getServer().getScheduler().runTask(this, var1);
      }
   }

   private void failStartup(String var1, Throwable var2) {
      Throwable var3 = var2.getCause() == null ? var2 : var2.getCause();
      this.getLogger().log(Level.SEVERE, var1, var3);
      this.getServer().getServicesManager().unregisterAll(this);
      this.closeDatabase();
      if (this.isEnabled()) {
         this.getServer().getPluginManager().disablePlugin(this);
      }
   }

   private void closeDatabase() {
      if (this.database != null) {
         this.database.close();
         this.database = null;
      }
   }
}
