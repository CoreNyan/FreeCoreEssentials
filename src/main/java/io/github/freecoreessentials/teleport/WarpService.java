package io.github.freecoreessentials.teleport;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Stores named, cross-world locations and the server spawn independently of config.yml. */
public final class WarpService {
   private final JavaPlugin plugin;
   private final File file;
   private final Map<String, Warp> warps = new LinkedHashMap<>();
   private final Map<UUID, Map<String, Home>> homes = new LinkedHashMap<>();
   private StoredLocation spawn;

   public WarpService(JavaPlugin plugin) {
      this.plugin = plugin;
      this.file = new File(plugin.getDataFolder(), "teleports.yml");
   }

   public void load() {
      this.warps.clear();
      this.homes.clear();
      this.spawn = null;
      if (!this.file.isFile()) return;

      YamlConfiguration yaml = YamlConfiguration.loadConfiguration(this.file);
      this.spawn = this.readLocation(yaml, "spawn.");
      ConfigurationSection section = yaml.getConfigurationSection("warps");
      if (section != null) {
         for (String key : section.getKeys(false)) {
            String path = "warps." + key + ".";
            String name = yaml.getString(path + "name", key);
            String normalized = normalize(name);
            StoredLocation location = this.readLocation(yaml, path);
            if (normalized == null || location == null) {
               this.plugin.getLogger().warning("Ignoring invalid warp entry " + key + " in teleports.yml.");
               continue;
            }
            this.warps.put(normalized, new Warp(normalized, name, location));
         }
      }

      ConfigurationSection homesSection = yaml.getConfigurationSection("homes");
      if (homesSection == null) return;
      for (String rawOwnerId : homesSection.getKeys(false)) {
         UUID ownerId;
         try {
            ownerId = UUID.fromString(rawOwnerId);
         } catch (IllegalArgumentException ignored) {
            this.plugin.getLogger().warning("Ignoring invalid home owner UUID " + rawOwnerId + " in teleports.yml.");
            continue;
         }
         ConfigurationSection playerHomes = homesSection.getConfigurationSection(rawOwnerId);
         if (playerHomes == null) continue;
         Map<String, Home> values = new LinkedHashMap<>();
         for (String key : playerHomes.getKeys(false)) {
            String path = "homes." + rawOwnerId + "." + key + ".";
            String name = yaml.getString(path + "name", key);
            String normalized = normalize(name);
            StoredLocation location = this.readLocation(yaml, path);
            if (normalized == null || location == null) {
               this.plugin.getLogger().warning("Ignoring invalid home entry " + rawOwnerId + "." + key + " in teleports.yml.");
               continue;
            }
            values.put(normalized, new Home(normalized, name, location));
         }
         if (!values.isEmpty()) this.homes.put(ownerId, values);
      }
   }

   public boolean setWarp(String name, Location location) {
      String normalized = normalize(name);
      if (normalized == null || location.getWorld() == null) return false;
      this.warps.put(normalized, new Warp(normalized, name, StoredLocation.from(location)));
      this.save();
      return true;
   }

   public boolean removeWarp(String name) {
      String normalized = normalize(name);
      if (normalized == null || this.warps.remove(normalized) == null) return false;
      this.save();
      return true;
   }

   public Warp findWarp(String name) {
      String normalized = normalize(name);
      return normalized == null ? null : this.warps.get(normalized);
   }

   public List<String> names() {
      List<String> names = new ArrayList<>();
      for (Warp warp : this.warps.values()) names.add(warp.name());
      names.sort(String.CASE_INSENSITIVE_ORDER);
      return List.copyOf(names);
   }

   public boolean validName(String name) {
      return normalize(name) != null;
   }

   public int maximumHomes() {
      return this.plugin.getConfig().getInt("homes.max-per-player", 5);
   }

   public SetHomeResult setHome(UUID ownerId, String name, Location location, int maximumHomes) {
      String normalized = normalize(name);
      if (ownerId == null || normalized == null || location.getWorld() == null) return SetHomeResult.INVALID;
      Map<String, Home> playerHomes = this.homes.computeIfAbsent(ownerId, unused -> new LinkedHashMap<>());
      if (!playerHomes.containsKey(normalized) && maximumHomes >= 0 && playerHomes.size() >= maximumHomes) {
         return SetHomeResult.LIMIT_REACHED;
      }
      playerHomes.put(normalized, new Home(normalized, name, StoredLocation.from(location)));
      this.save();
      return SetHomeResult.SET;
   }

   public boolean removeHome(UUID ownerId, String name) {
      String normalized = normalize(name);
      if (ownerId == null || normalized == null) return false;
      Map<String, Home> playerHomes = this.homes.get(ownerId);
      if (playerHomes == null || playerHomes.remove(normalized) == null) return false;
      if (playerHomes.isEmpty()) this.homes.remove(ownerId);
      this.save();
      return true;
   }

   public Home findHome(UUID ownerId, String name) {
      String normalized = normalize(name);
      if (ownerId == null || normalized == null) return null;
      Map<String, Home> values = this.homes.get(ownerId);
      return values == null ? null : values.get(normalized);
   }

   public List<String> homeNames(UUID ownerId) {
      Map<String, Home> values = this.homes.get(ownerId);
      if (values == null || values.isEmpty()) return List.of();
      List<String> names = new ArrayList<>();
      for (Home home : values.values()) names.add(home.name());
      names.sort(String.CASE_INSENSITIVE_ORDER);
      return List.copyOf(names);
   }

   public void setSpawn(Location location) {
      if (location.getWorld() == null) throw new IllegalArgumentException("Spawn world is unavailable");
      this.spawn = StoredLocation.from(location);
      this.save();
   }

   public StoredLocation spawn() {
      if (this.spawn != null) return this.spawn;
      return Bukkit.getWorlds().isEmpty() ? null : StoredLocation.from(Bukkit.getWorlds().getFirst().getSpawnLocation());
   }

   private void save() {
      YamlConfiguration yaml = new YamlConfiguration();
      if (this.spawn != null) this.writeLocation(yaml, "spawn.", this.spawn);
      for (Warp warp : this.warps.values()) {
         String path = "warps." + warp.key() + ".";
         yaml.set(path + "name", warp.name());
         this.writeLocation(yaml, path, warp.location());
      }
      for (Map.Entry<UUID, Map<String, Home>> playerHomes : this.homes.entrySet()) {
         for (Home home : playerHomes.getValue().values()) {
            String path = "homes." + playerHomes.getKey() + "." + home.key() + ".";
            yaml.set(path + "name", home.name());
            this.writeLocation(yaml, path, home.location());
         }
      }
      try {
         yaml.save(this.file);
      } catch (IOException exception) {
         throw new IllegalStateException("Could not save teleports.yml", exception);
      }
   }

   private StoredLocation readLocation(YamlConfiguration yaml, String path) {
      String worldName = yaml.getString(path + "world-name");
      String rawWorldId = yaml.getString(path + "world");
      if ((worldName == null || worldName.isBlank()) && (rawWorldId == null || rawWorldId.isBlank())) return null;
      UUID worldId = null;
      if (rawWorldId != null && !rawWorldId.isBlank()) {
         try {
            worldId = UUID.fromString(rawWorldId);
         } catch (IllegalArgumentException ignored) {
            this.plugin.getLogger().warning("Invalid world UUID in teleports.yml at " + path);
         }
      }
      return new StoredLocation(worldId, worldName, yaml.getDouble(path + "x"), yaml.getDouble(path + "y"), yaml.getDouble(path + "z"),
         (float)yaml.getDouble(path + "yaw"), (float)yaml.getDouble(path + "pitch"));
   }

   private void writeLocation(YamlConfiguration yaml, String path, StoredLocation location) {
      if (location.worldId() != null) yaml.set(path + "world", location.worldId().toString());
      yaml.set(path + "world-name", location.worldName());
      yaml.set(path + "x", location.x());
      yaml.set(path + "y", location.y());
      yaml.set(path + "z", location.z());
      yaml.set(path + "yaw", location.yaw());
      yaml.set(path + "pitch", location.pitch());
   }

   private static String normalize(String name) {
      if (name == null) return null;
      String trimmed = name.trim();
      if (trimmed.isEmpty() || trimmed.length() > 32 || !trimmed.matches("[\\p{L}\\p{N}_-]+")) return null;
      return trimmed.toLowerCase(Locale.ROOT);
   }

   public record Warp(String key, String name, StoredLocation location) {
   }

   public record Home(String key, String name, StoredLocation location) {
   }

   public enum SetHomeResult {
      SET,
      LIMIT_REACHED,
      INVALID
   }

   public record StoredLocation(UUID worldId, String worldName, double x, double y, double z, float yaw, float pitch) {
      public static StoredLocation from(Location location) {
         World world = location.getWorld();
         if (world == null) throw new IllegalArgumentException("Location world is unavailable");
         return new StoredLocation(world.getUID(), world.getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
      }

      public Location resolve() {
         World world = this.worldId == null ? null : Bukkit.getWorld(this.worldId);
         if (world == null && this.worldName != null && !this.worldName.isBlank()) world = Bukkit.getWorld(this.worldName);
         return world == null ? null : new Location(world, this.x, this.y, this.z, this.yaw, this.pitch);
      }
   }
}
