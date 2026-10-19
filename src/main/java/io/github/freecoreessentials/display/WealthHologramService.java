package io.github.freecoreessentials.display;

import io.github.freecoreeconomy.database.DatabaseGateway;
import io.github.freecoreeconomy.command.WealthBalanceCombiner;
import io.github.freecoreeconomy.vault.BlessingSkinEconomy;
import io.github.freecoreessentials.lang.Lang;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Persistent, database-backed TextDisplays for the H-coin leaderboard. */
public final class WealthHologramService implements Listener, AutoCloseable {
   private static final int MIN_TOP_SIZE = 1;
   private static final int MAX_TOP_SIZE = 100;
   private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

   private final JavaPlugin plugin;
   private final DatabaseGateway database;
   private final BlessingSkinEconomy economy;
   private final Lang lang;
   private final File file;
   private final NamespacedKey markerKey;
   private final NamespacedKey hologramIdKey;
   private final Map<UUID, Hologram> holograms = new ConcurrentHashMap<>();
   private final Map<UUID, UUID> entities = new ConcurrentHashMap<>();
   private final AtomicBoolean refreshInProgress = new AtomicBoolean();
   private volatile List<DatabaseGateway.LeaderboardEntry> leaderboard = List.of();
   private BukkitTask refreshTask;
   private boolean closed;

   public WealthHologramService(JavaPlugin plugin, DatabaseGateway database, BlessingSkinEconomy economy, Lang lang) {
      this.plugin = plugin;
      this.database = database;
      this.economy = economy;
      this.lang = lang;
      this.file = new File(plugin.getDataFolder(), "wealth-holograms.yml");
      this.markerKey = new NamespacedKey(plugin, "wealth_hologram");
      this.hologramIdKey = new NamespacedKey(plugin, "wealth_hologram_id");
   }

   public void start() {
      this.closed = false;
      this.load();
      this.restoreLoadedChunks();
      this.refreshAll();
      this.scheduleRefresh();
   }

   public void reload() {
      this.cancelRefresh();
      this.refreshAll();
      this.scheduleRefresh();
   }

   public boolean create(Location playerLocation) {
      World world = playerLocation.getWorld();
      if (world == null) return false;
      double height = this.plugin.getConfig().getDouble("wealth-hologram.height", 2.0D);
      Hologram hologram = new Hologram(UUID.randomUUID(), world.getUID(), playerLocation.getX(), playerLocation.getY() + height, playerLocation.getZ());
      this.holograms.put(hologram.id(), hologram);
      this.save();
      this.render(hologram);
      this.refreshAll();
      return true;
   }

   public boolean removeNearby(Location location) {
      Hologram hologram = this.nearest(location);
      if (hologram == null) return false;
      this.remove(hologram);
      this.save();
      return true;
   }

   public boolean refreshNearby(Location location) {
      if (this.nearest(location) == null) return false;
      this.refreshAll();
      return true;
   }

   public boolean isHologram(Entity entity) {
      return entity instanceof TextDisplay && entity.getPersistentDataContainer().has(this.markerKey, PersistentDataType.BYTE);
   }

   @EventHandler
   public void onChunkLoad(ChunkLoadEvent event) {
      Bukkit.getScheduler().runTask(this.plugin, () -> this.restoreChunk(event.getChunk()));
   }

   @EventHandler(ignoreCancelled = true)
   public void onDamage(EntityDamageEvent event) {
      if (this.isHologram(event.getEntity())) event.setCancelled(true);
   }

   @EventHandler
   public void onInteract(PlayerInteractEntityEvent event) {
      if (this.isHologram(event.getRightClicked())) event.setCancelled(true);
   }

   @EventHandler
   public void onInteractAt(PlayerInteractAtEntityEvent event) {
      if (this.isHologram(event.getRightClicked())) event.setCancelled(true);
   }

   public void refreshAll() {
      if (this.closed || !this.refreshInProgress.compareAndSet(false, true)) return;
      this.database.fetchLeaderboardAsync(this.candidateLimit()).whenComplete((entries, failure) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
         this.refreshInProgress.set(false);
         if (this.closed || !this.plugin.isEnabled()) return;
         if (failure != null) {
            this.plugin.getLogger().warning("Could not refresh wealth holograms: " + failure.getMessage());
            return;
         }
         this.leaderboard = this.filter(WealthBalanceCombiner.combine(this.plugin, entries));
         this.holograms.values().forEach(this::render);
      }));
   }

   @Override
   public void close() {
      this.closed = true;
      this.cancelRefresh();
      this.entities.clear();
      this.save();
   }

   private void scheduleRefresh() {
      long seconds = Math.clamp(this.plugin.getConfig().getLong("wealth-hologram.refresh-seconds", 60L), 5L, 86400L);
      this.refreshTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::refreshAll, seconds * 20L, seconds * 20L);
   }

   private void cancelRefresh() {
      if (this.refreshTask != null) {
         this.refreshTask.cancel();
         this.refreshTask = null;
      }
   }

   private List<DatabaseGateway.LeaderboardEntry> filter(List<DatabaseGateway.LeaderboardEntry> candidates) {
      Set<String> excluded = new HashSet<>();
      for (String name : this.plugin.getConfig().getStringList("wealth-hologram.excluded-players")) {
         if (name != null && !name.isBlank()) excluded.add(name.toLowerCase(Locale.ROOT));
      }
      Set<String> operators = new HashSet<>();
      for (OfflinePlayer operator : Bukkit.getOperators()) {
         String name = operator.getName();
         if (name != null) operators.add(name.toLowerCase(Locale.ROOT));
      }

      List<DatabaseGateway.LeaderboardEntry> result = new ArrayList<>();
      for (DatabaseGateway.LeaderboardEntry entry : candidates) {
         String normalizedName = entry.playerName().toLowerCase(Locale.ROOT);
         if (excluded.contains(normalizedName) || operators.contains(normalizedName)) continue;
         result.add(entry);
         if (result.size() >= this.topSize()) break;
      }
      return List.copyOf(result);
   }

   private void render(Hologram hologram) {
      Location location = hologram.location();
      World world = location.getWorld();
      if (world == null || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;
      TextDisplay display = this.find(hologram);
      if (display == null) {
         display = world.spawn(location, TextDisplay.class, entity -> this.configure(entity, hologram));
         this.entities.put(hologram.id(), display.getUniqueId());
      }
      display.text(this.text());
      display.setBillboard(Display.Billboard.CENTER);
      display.setSeeThrough(this.plugin.getConfig().getBoolean("wealth-hologram.see-through", false));
      display.setLineWidth(Math.clamp(this.plugin.getConfig().getInt("wealth-hologram.line-width", 512), 128, 4096));
      display.setShadowed(false);
   }

   private Component text() {
      List<Component> lines = new ArrayList<>();
      lines.add(this.component("wealth-hologram.title"));
      if (this.leaderboard.isEmpty()) {
         lines.add(this.component("wealth-hologram.empty"));
      } else {
         for (int index = 0; index < this.leaderboard.size(); index++) {
            DatabaseGateway.LeaderboardEntry entry = this.leaderboard.get(index);
            lines.add(this.component("wealth-hologram.entry", index + 1, entry.playerName(), this.economy.format(entry.balance().doubleValue())));
         }
      }
      return Component.join(JoinConfiguration.newlines(), lines);
   }

   private Component component(String key, Object... arguments) {
      return LEGACY.deserialize(this.lang.message(key, arguments));
   }

   private int topSize() {
      return Math.clamp(this.plugin.getConfig().getInt("wealth-hologram.top-size", 10), MIN_TOP_SIZE, MAX_TOP_SIZE);
   }

   private int candidateLimit() {
      return Math.max(this.topSize(), Math.clamp(this.plugin.getConfig().getInt("wealth-hologram.candidate-limit", 500), MIN_TOP_SIZE, 1000));
   }

   private Hologram nearest(Location location) {
      World world = location.getWorld();
      if (world == null) return null;
      return this.holograms.values().stream().filter(hologram -> hologram.worldId().equals(world.getUID()))
         .filter(hologram -> hologram.location().distanceSquared(location) <= 64.0D)
         .min(Comparator.comparingDouble(hologram -> hologram.location().distanceSquared(location))).orElse(null);
   }

   private void remove(Hologram hologram) {
      UUID entityId = this.entities.remove(hologram.id());
      Entity entity = entityId == null ? null : Bukkit.getEntity(entityId);
      if (this.isHologram(entity, hologram.id())) entity.remove();
      Location location = hologram.location();
      World world = location.getWorld();
      if (world != null && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
         for (Entity nearby : world.getNearbyEntities(location, 0.5D, 0.5D, 0.5D)) {
            if (this.isHologram(nearby, hologram.id())) nearby.remove();
         }
      }
      this.holograms.remove(hologram.id());
   }

   private TextDisplay find(Hologram hologram) {
      UUID entityId = this.entities.get(hologram.id());
      Entity entity = entityId == null ? null : Bukkit.getEntity(entityId);
      if (this.isHologram(entity, hologram.id())) return (TextDisplay)entity;
      this.entities.remove(hologram.id(), entityId);

      Location location = hologram.location();
      World world = location.getWorld();
      if (world == null || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return null;
      TextDisplay found = null;
      for (Entity candidate : world.getChunkAt(location).getEntities()) {
         if (!this.isHologram(candidate, hologram.id())) continue;
         if (found == null) {
            found = (TextDisplay)candidate;
            this.entities.put(hologram.id(), candidate.getUniqueId());
         } else {
            candidate.remove();
         }
      }
      return found;
   }

   private boolean isHologram(Entity entity, UUID hologramId) {
      return this.isHologram(entity) && hologramId.toString().equals(entity.getPersistentDataContainer().get(this.hologramIdKey, PersistentDataType.STRING));
   }

   private void configure(TextDisplay display, Hologram hologram) {
      display.getPersistentDataContainer().set(this.markerKey, PersistentDataType.BYTE, (byte)1);
      display.getPersistentDataContainer().set(this.hologramIdKey, PersistentDataType.STRING, hologram.id().toString());
      display.setPersistent(true);
      display.setInvulnerable(true);
      display.setGravity(false);
      display.setBillboard(Display.Billboard.CENTER);
      display.setAlignment(TextDisplay.TextAlignment.CENTER);
      display.setLineWidth(Math.clamp(this.plugin.getConfig().getInt("wealth-hologram.line-width", 512), 128, 4096));
      display.setSeeThrough(this.plugin.getConfig().getBoolean("wealth-hologram.see-through", false));
      display.setShadowed(false);
      display.setDefaultBackground(false);
   }

   private void restoreChunk(Chunk chunk) {
      for (Entity entity : chunk.getEntities()) {
         if (!this.isHologram(entity)) continue;
         String rawId = entity.getPersistentDataContainer().get(this.hologramIdKey, PersistentDataType.STRING);
         try {
            Hologram hologram = rawId == null ? null : this.holograms.get(UUID.fromString(rawId));
            if (hologram == null) entity.remove();
            else {
               UUID existingId = this.entities.putIfAbsent(hologram.id(), entity.getUniqueId());
               Entity existing = existingId == null ? null : Bukkit.getEntity(existingId);
               if (existingId != null && !existingId.equals(entity.getUniqueId())) {
                  if (this.isHologram(existing, hologram.id())) entity.remove();
                  else this.entities.put(hologram.id(), entity.getUniqueId());
               }
            }
         } catch (IllegalArgumentException ignored) {
            entity.remove();
         }
      }
      this.holograms.values().stream().filter(hologram -> hologram.worldId().equals(chunk.getWorld().getUID()))
         .filter(hologram -> (int)Math.floor(hologram.x()) >> 4 == chunk.getX() && (int)Math.floor(hologram.z()) >> 4 == chunk.getZ())
         .forEach(this::render);
   }

   private void restoreLoadedChunks() {
      for (World world : Bukkit.getWorlds()) {
         for (Chunk chunk : world.getLoadedChunks()) {
            this.restoreChunk(chunk);
         }
      }
   }

   private void load() {
      if (!this.file.isFile()) return;
      YamlConfiguration yaml = YamlConfiguration.loadConfiguration(this.file);
      ConfigurationSection section = yaml.getConfigurationSection("holograms");
      if (section == null) return;
      for (String rawId : section.getKeys(false)) {
         try {
            String path = "holograms." + rawId + ".";
            UUID id = UUID.fromString(rawId);
            this.holograms.put(id, new Hologram(id, UUID.fromString(yaml.getString(path + "world")), yaml.getDouble(path + "x"), yaml.getDouble(path + "y"), yaml.getDouble(path + "z")));
         } catch (RuntimeException ignored) {
            this.plugin.getLogger().warning("Ignoring invalid wealth hologram " + rawId);
         }
      }
   }

   private void save() {
      YamlConfiguration yaml = new YamlConfiguration();
      for (Hologram hologram : this.holograms.values()) {
         String path = "holograms." + hologram.id() + ".";
         yaml.set(path + "world", hologram.worldId().toString());
         yaml.set(path + "x", hologram.x());
         yaml.set(path + "y", hologram.y());
         yaml.set(path + "z", hologram.z());
      }
      try {
         yaml.save(this.file);
      } catch (IOException exception) {
         this.plugin.getLogger().warning("Could not save wealth holograms: " + exception.getMessage());
      }
   }

   private record Hologram(UUID id, UUID worldId, double x, double y, double z) {
      private Location location() {
         return new Location(Bukkit.getWorld(this.worldId), this.x, this.y, this.z);
      }
   }
}
