package io.github.freecoreeconomy.skin;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.github.freecoreeconomy.database.BlessingSkinTexture;
import io.github.freecoreeconomy.database.DatabaseGateway;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.event.FloodgateSubscriber;
import org.geysermc.floodgate.api.event.skin.SkinApplyEvent;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

public final class SkinIntegration implements Listener, AutoCloseable {
   private final Plugin plugin;
   private final DatabaseGateway database;
   private final FloodgateApi floodgate;
   private final String textureUrlTemplate;
   private final String yggProfileLookupTemplate;
   private final String yggSessionProfileTemplate;
   private final long lookupTimeoutMs;
   private final Map<String, CompletableFuture<Optional<BlessingSkinTexture>>> prefetched = new ConcurrentHashMap<>();
   private final Map<String, CompletableFuture<Optional<SkinIntegration.SignedTextureProfile>>> signedProfiles = new ConcurrentHashMap<>();
   private final Map<String, CompletableFuture<Optional<SkinIntegration.SignedTextureProfile>>> playerProfiles = new ConcurrentHashMap<>();
   private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2L)).build();
   private FloodgateSubscriber<SkinApplyEvent> floodgateSubscriber;

   public SkinIntegration(Plugin var1, DatabaseGateway var2, FileConfiguration var3) {
      this.plugin = var1;
      this.database = var2;
      this.textureUrlTemplate = var3.getString("skins.texture-url-template", "").trim();
      this.yggProfileLookupTemplate = var3.getString(
            "skins.yggdrasil-profile-url-template", "http://skin.freecore.cc/api/yggdrasil/api/users/profiles/minecraft/{name}"
         )
         .trim();
      this.yggSessionProfileTemplate = var3.getString(
            "skins.yggdrasil-session-url-template", "http://skin.freecore.cc/api/yggdrasil/sessionserver/session/minecraft/profile/{uuid}"
         )
         .trim();
      this.lookupTimeoutMs = Math.max(250L, var3.getLong("database.operation-timeout-ms", 2500L));
      this.floodgate = var1.getServer().getPluginManager().isPluginEnabled("floodgate") ? FloodgateApi.getInstance() : null;
      var1.getServer().getPluginManager().registerEvents(this, var1);
      if (this.floodgate != null) {
         this.floodgateSubscriber = (FloodgateSubscriber<SkinApplyEvent>)this.floodgate
            .getEventBus()
            .subscribe(SkinApplyEvent.class, this::onFloodgateSkinApply);
      }

      var1.getLogger().info("Blessing Skin texture bridge enabled (template configured: " + !this.textureUrlTemplate.isBlank() + ").");
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onJoin(PlayerJoinEvent var1) {
      Player var2 = var1.getPlayer();
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         for (Player var3x : Bukkit.getOnlinePlayers()) {
            if (!var3x.equals(var2)) {
               this.refreshPlayerForViewer(var2, var3x);
            }
         }
      }, 2L);
      if (this.floodgate != null && this.floodgate.isFloodgatePlayer(var2.getUniqueId())) {
         FloodgatePlayer var5 = this.floodgate.getPlayer(var2.getUniqueId());
         if (var5 != null && var5.getXuid() != null && !var5.getXuid().isBlank()) {
            CompletableFuture<Optional<BlessingSkinTexture>> var4 = this.database.findSkinByXuidAsync(var5.getXuid())
               .thenCompose(found -> found.isPresent()
                  ? CompletableFuture.completedFuture(found)
                  : this.database.findSkinByUsernameAsync(var2.getName()));
            this.prefetched.put(var5.getXuid(), var4);
            this.applyBedrockTextureWhenReady(var2, var5, var4);
         }
      } else {
         CompletableFuture<Optional<BlessingSkinTexture>> var3 = this.database.findSkinByUsernameAsync(var2.getName());
      var3.whenComplete((var2x, var3x) -> this.applyForPlayer(var2.getUniqueId(), var2.getName(), (Optional<BlessingSkinTexture>)var2x, var3x));
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onLogin(PlayerLoginEvent var1) {
      Player var2 = var1.getPlayer();
      this.playerProfiles.put(var2.getName().toLowerCase(Locale.ROOT), this.signedProfileAsync(var2.getName()));
      if (this.floodgate != null && this.floodgate.isFloodgatePlayer(var2.getUniqueId())) {
         FloodgatePlayer floodgatePlayer = this.floodgate.getPlayer(var2.getUniqueId());
         if (floodgatePlayer != null && floodgatePlayer.getUsername() != null && !floodgatePlayer.getUsername().isBlank()) {
            this.playerProfiles.putIfAbsent(floodgatePlayer.getUsername().toLowerCase(Locale.ROOT), this.signedProfileAsync(floodgatePlayer.getUsername()));
         }
      }
      CompletableFuture<Optional<BlessingSkinTexture>> var3;
      if (this.floodgate != null && this.floodgate.isFloodgatePlayer(var2.getUniqueId())) {
         FloodgatePlayer var4 = this.floodgate.getPlayer(var2.getUniqueId());
         if (var4 != null && var4.getXuid() != null && !var4.getXuid().isBlank()) {
            var3 = this.database.findSkinByXuidAsync(var4.getXuid());
            this.prefetched.put(var4.getXuid(), var3);
         } else {
            var3 = this.database.findSkinByUsernameAsync(var2.getName());
         }
      } else {
         var3 = this.database.findSkinByUsernameAsync(var2.getName());
      }

      // The proxy/Geyser path consumes the GameProfile sent during login.  Updating
      // PlayerProfile only from PlayerJoinEvent is too late: Geyser has already
      // cached the default profile and Bedrock viewers will keep seeing Steve.
      // Resolve the selected Blessing Skin before login completes and publish it
      // in the login profile.  The timeout is deliberately bounded so a database
      // outage cannot stall the login indefinitely; the existing join handler is
      // retained as a fallback.
      try {
         Optional<BlessingSkinTexture> selected = var3.get(this.lookupTimeoutMs, TimeUnit.MILLISECONDS);
         if (selected.isPresent() && !this.textureUrlTemplate.isBlank()) {
            Optional<SignedTextureProfile> signed = this.signedProfileAsync(var2.getName())
               .get(Math.max(this.lookupTimeoutMs, 3000L), TimeUnit.MILLISECONDS);
            this.setPaperProfile(var2, var2.getName(), selected.get(), signed.orElse(null));
            this.plugin.getLogger().info("Published Blessing Skin " + (signed.isPresent() ? "signed" : "fallback")
               + " profile during login for " + var2.getName() + ".");
         }
      } catch (InterruptedException interrupted) {
         Thread.currentThread().interrupt();
      } catch (ExecutionException | TimeoutException failure) {
         this.plugin.getLogger().log(Level.FINE, "Could not publish Blessing Skin profile during login for " + var2.getName(), failure);
      }

   }

   public void onFloodgateSkinApply(SkinApplyEvent event) {
      FloodgatePlayer floodgatePlayer = event.player();
      String name = floodgatePlayer.getUsername();
      String xuid = floodgatePlayer.getXuid();
      CompletableFuture<Optional<BlessingSkinTexture>> future = this.findBedrockSkin(floodgatePlayer);
      try {
         Optional<BlessingSkinTexture> skin = future.get(this.lookupTimeoutMs, TimeUnit.MILLISECONDS);
         if (skin.isPresent() && !this.textureUrlTemplate.isBlank()) {
            Optional<SignedTextureProfile> signed = this.signedProfileAsync(name)
               .get(Math.max(this.lookupTimeoutMs, 3000L), TimeUnit.MILLISECONDS);
            if (signed.isPresent()) {
               event.newSkin(new SkinPropertyData(signed.get().value(), signed.get().signature()));
               this.plugin.getLogger().info("Applied signed FreeCoreSkin Floodgate texture to " + name + (xuid == null || xuid.isBlank() ? " (username)." : " (XUID)."));
            } else {
               String value = this.createTextureProperty(floodgatePlayer.getCorrectUniqueId(), name, skin.get());
               event.newSkin(new SkinPropertyData(value, ""));
               this.plugin.getLogger().warning("Applied unsigned FreeCoreSkin Floodgate fallback to " + name + "; Geyser may reject it.");
            }
         } else {
            this.plugin.getLogger().warning("FreeCoreSkin has no selected texture for Bedrock player " + name);
         }
      } catch (Exception failure) {
         this.plugin.getLogger().log(Level.WARNING, "Unable to load FreeCoreSkin texture for Bedrock player " + name, failure);
      }
   }

   private CompletableFuture<Optional<BlessingSkinTexture>> findBedrockSkin(FloodgatePlayer player) {
      String xuid = player.getXuid();
      if (xuid == null || xuid.isBlank()) {
         return this.database.findSkinByUsernameAsync(player.getUsername());
      }
      return this.prefetched.computeIfAbsent(xuid, key -> this.database.findSkinByXuidAsync(key)
         .thenCompose(found -> found.isPresent()
            ? CompletableFuture.completedFuture(found)
            : this.database.findSkinByUsernameAsync(player.getUsername())));
   }

   private void applyBedrockTextureWhenReady(Player player, FloodgatePlayer floodgatePlayer, CompletableFuture<Optional<BlessingSkinTexture>> future) {
      String name = floodgatePlayer.getUsername();
      future.whenComplete((skin, failure) -> {
         if (failure != null || skin == null || skin.isEmpty()) {
            return;
         }
         this.signedProfileAsync(name).whenComplete((signedProfile, signedFailure) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
               if (!player.isOnline()) {
                  return;
               }
               SignedTextureProfile signed = signedFailure == null && signedProfile != null && signedProfile.isPresent()
                  ? signedProfile.get()
                  : null;
               this.setPaperProfile(player, name, skin.get(), signed);
               for (Player viewer : Bukkit.getOnlinePlayers()) if (!viewer.equals(player)) this.refreshPlayerForViewer(viewer, player);
               this.plugin.getLogger().info("Applied Blessing Skin " + (signed == null ? "fallback" : "signed") + " profile to Bedrock player " + name + ".");
            }));
      });
   }
   private Optional<BlessingSkinTexture> lookupWithoutMainThread(String var1) {
      CompletableFuture var2 = this.prefetched.get(var1);
      if (var2 == null) {
         if (Bukkit.isPrimaryThread()) {
            return Optional.empty();
         }

         var2 = this.database.findSkinByXuidAsync(var1);
      }

      try {
         return (Optional<BlessingSkinTexture>)var2.get(this.lookupTimeoutMs, TimeUnit.MILLISECONDS);
      } catch (InterruptedException var4) {
         Thread.currentThread().interrupt();
         return Optional.empty();
      } catch (TimeoutException | ExecutionException var5) {
         this.plugin.getLogger().log(Level.FINE, "Timed out loading Blessing Skin for XUID " + var1, var5);
         return Optional.empty();
      }
   }

   private void applyForPlayer(UUID var1, String var2, Optional<BlessingSkinTexture> var3, Throwable var4) {
      if (var4 == null && var3 != null && !var3.isEmpty() && !this.textureUrlTemplate.isBlank()) {
         Bukkit.getScheduler()
            .runTask(
               this.plugin,
               () -> {
                  Player var4x = Bukkit.getPlayer(var1);
                  if (var4x != null) {
                     this.signedProfileAsync(var2)
                        .whenComplete(
                           (var4xx, var5) -> Bukkit.getScheduler()
                              .runTask(
                                 this.plugin,
                                 () -> {
                                    Player var6 = Bukkit.getPlayer(var1);
                                    if (var6 != null) {
                                       SignedTextureProfile signed = var5 == null || var4xx == null || var4xx.isEmpty()
                                          ? null
                                          : var4xx.get();
                                       // Keep the Yggdrasil signature whenever it is available. Geyser
                                       // and Java clients both preserve signed profile properties across
                                       // player-info updates; an ad-hoc unsigned replacement does not.
                                       this.setPaperProfile(var6, var2, (BlessingSkinTexture)var3.get(), signed);
                                       this.plugin
                                          .getLogger()
                                          .info(
                                             "Applied Blessing Skin " + (signed == null ? "fallback" : "signed") + " Paper profile to "
                                                + var2
                                                + "."
                                          );

                                       for (Player var8 : Bukkit.getOnlinePlayers()) {
                                          if (!var8.equals(var6)) this.refreshPlayerForViewer(var8, var6);
                                       }
                                    }
                                 }
                              )
                        );
                  }
               }
            );
      }
   }

   private void setPaperProfile(Player var1, String var2, BlessingSkinTexture var3, SkinIntegration.SignedTextureProfile var4) {
      PlayerProfile var5 = var1.getPlayerProfile();
      // Never overwrite a valid signed property with an unsigned fallback. The
      // latter is rejected by Geyser when it builds Bedrock player skins.
      if (var4 == null) {
         for (ProfileProperty property : var5.getProperties()) {
            if ("textures".equals(property.getName()) && property.getSignature() != null
               && !property.getSignature().isBlank()) {
               this.plugin.getLogger().warning("Keeping existing signed profile for " + var2 + "; signed FreeCore profile lookup was unavailable.");
               return;
            }
         }
      }
      String var6 = var4 == null ? this.createTextureProperty(var1.getUniqueId(), var2, var3) : var4.value();
      String var7 = var4 == null ? "" : var4.signature();
      var5.setProperty(new ProfileProperty("textures", var6, var7));
      var1.setPlayerProfile(var5);
   }

   private Optional<SkinIntegration.SignedTextureProfile> signedProfile(String var1) {
      try {
         Exception last = null;
         for (int attempt = 0; attempt < 3; attempt++) {
          try {
         String var2 = URLEncoder.encode(var1, StandardCharsets.UTF_8);
         String var3 = this.yggProfileLookupTemplate.replace("{name}", var2);
         HttpRequest var4 = HttpRequest.newBuilder(URI.create(var3)).timeout(Duration.ofSeconds(2L)).GET().build();
         HttpResponse var5 = this.httpClient.send(var4, BodyHandlers.ofString());
         if (var5.statusCode() != 200) { Thread.sleep(150L * (attempt + 1)); continue; }

         Matcher var6 = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([0-9a-fA-F-]+)\\\"").matcher((CharSequence)var5.body());
         if (!var6.find()) {
            Thread.sleep(150L * (attempt + 1)); continue;
         }

         String var7 = this.yggSessionProfileTemplate.replace("{uuid}", var6.group(1));
         if (!var7.contains("?")) {
            var7 = var7 + "?unsigned=false";
         }

         HttpRequest var8 = HttpRequest.newBuilder(URI.create(var7)).timeout(Duration.ofSeconds(2L)).GET().build();
         HttpResponse var9 = this.httpClient.send(var8, BodyHandlers.ofString());
         if (var9.statusCode() != 200) { Thread.sleep(150L * (attempt + 1)); continue; }

         Matcher var10 = Pattern.compile(
               "\\\"name\\\"\\s*:\\s*\\\"textures\\\"[^}]*?\\\"value\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"[^}]*?\\\"signature\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""
            )
            .matcher((CharSequence)var9.body());
         if (var10.find() && !var10.group(2).isBlank()) return Optional.of(new SkinIntegration.SignedTextureProfile(var10.group(1), var10.group(2)));
         Thread.sleep(150L * (attempt + 1));
          } catch (Exception retryFailure) { last = retryFailure; Thread.sleep(150L * (attempt + 1)); }
         }
         if (last != null) throw last;
         return Optional.empty();
      } catch (Exception var11) {
         this.plugin.getLogger().log(Level.FINE, "Could not load signed Blessing Skin profile for " + var1, var11);
         return Optional.empty();
      }
   }

   private CompletableFuture<Optional<SkinIntegration.SignedTextureProfile>> signedProfileAsync(String var1) {
      String key = var1.toLowerCase(Locale.ROOT);
      return this.signedProfiles.computeIfAbsent(key, var2 -> {
         CompletableFuture<Optional<SkinIntegration.SignedTextureProfile>> future = CompletableFuture.supplyAsync(() -> this.signedProfile(var1));
         future.whenComplete((value, failure) -> {
            if (failure != null || value == null || value.isEmpty()) this.signedProfiles.remove(key, future);
         });
         return future;
      });
   }

   private void refreshPlayerForViewer(Player var1, Player var2) {
      if (!var1.isOnline() || !var2.isOnline()) return;
      refreshPlayerForViewer(var1, var2, 0);
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> refreshPlayerForViewer(var1, var2, 1), 3L);
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> refreshPlayerForViewer(var1, var2, 2), 8L);
   }

   private void refreshPlayerForViewer(Player viewer, Player target, int pass) {
      if (!viewer.isOnline() || !target.isOnline()) return;
      viewer.hidePlayer(this.plugin, target);
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         if (viewer.isOnline() && target.isOnline()) viewer.showPlayer(this.plugin, target);
      }, 1L);
   }

   private String createTextureProperty(UUID var1, String var2, BlessingSkinTexture var3) {
      String var4 = this.textureUrlTemplate.replace("{hash}", var3.hash()).replace("{type}", var3.type());
      if (var4.startsWith("HTTP://")) {
         var4 = "http://" + var4.substring(7);
      }
      String var5 = "{\"timestamp\":"
         + System.currentTimeMillis()
         + ",\"profileId\":\""
         + var1.toString().replace("-", "")
         + "\",\"profileName\":\""
         + escapeJson(var2)
         + "\",\"signatureRequired\":false,\"textures\":{\"SKIN\":{\"url\":\""
         + escapeJson(var4)
         + "\"";
      if (var3.slim()) {
         var5 = var5 + ",\"metadata\":{\"model\":\"slim\"}";
      }

      var5 = var5 + "}}}";
      return Base64.getEncoder().encodeToString(var5.getBytes(StandardCharsets.UTF_8));
   }

   private static String escapeJson(String var0) {
      return var0.replace("\\", "\\\\").replace("\"", "\\\"");
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent var1) {
      if (this.floodgate != null) {
         FloodgatePlayer var2 = this.floodgate.getPlayer(var1.getPlayer().getUniqueId());
         if (var2 != null && var2.getXuid() != null) {
            this.prefetched.remove(var2.getXuid());
         }
      }
   }

   @Override
   public void close() {
      HandlerList.unregisterAll(this);
      if (this.floodgateSubscriber != null) {
         this.floodgate.getEventBus().unsubscribe(this.floodgateSubscriber);
         this.floodgateSubscriber = null;
      }

      this.prefetched.clear();
      this.signedProfiles.clear();
      this.playerProfiles.clear();
   }

   private record SignedTextureProfile(String value, String signature) {
   }

   private record SkinPropertyData(String value, String signature) implements org.geysermc.floodgate.api.event.skin.SkinApplyEvent.SkinData {
   }
}

