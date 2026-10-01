package io.github.freecoreessentials.crossserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Persists the last safe position per player and backend in the shared Redis. */
public final class LastLocationService implements Listener, AutoCloseable {
    private static final String PREFIX = "fce:last-location:";
    private final JavaPlugin plugin;
    private final String server;
    private final java.util.concurrent.ConcurrentHashMap<UUID, String> pending = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<UUID, Integer> joinEpoch = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean closing;

    public LastLocationService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.server = Bukkit.getPort() == 25570 ? "technical" : "survival";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Let Bukkit/HuskSync restore returning players from their own stored world
        // position. A delayed teleport would overwrite death, /spawn or movement.
        // Redis is only a fallback for arrivals at the backend's spawn point.
        UUID id = player.getUniqueId();
        int epoch = joinEpoch.merge(id, 1, Integer::sum);
        if (!player.hasPlayedBefore()) return;
        Location initial = player.getLocation().clone();
        if (initial.getWorld() == null) return;
        // Survival has an additional lobby world. Returning players have been
        // observed spawning there at a fixed coordinate, not at zy world spawn.
        // Only arrivals at either world's actual spawn may use the fallback.
        if (initial.distanceSquared(initial.getWorld().getSpawnLocation()) > 16.0) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> restore(player, initial, epoch), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        joinEpoch.merge(player.getUniqueId(), 1, Integer::sum);
        Location location = player.getLocation();
        if (location.getWorld() == null || !isSafe(location)) return;
        String encoded = encode(location);
        UUID id = player.getUniqueId();
        pending.put(id, encoded);
        if (!closing) Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> persist(id, encoded));
    }

    private void persist(UUID id, String encoded) {
        String reply = redis("SET", key(id), encoded, "EX", "2592000");
        if ("OK".equals(reply)) pending.remove(id, encoded);
        else plugin.getLogger().warning("Could not persist last position for " + id);
    }

    /** Flush outstanding quit positions before Bukkit cancels scheduled tasks. */
    @Override
    public void close() {
        closing = true;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location position = player.getLocation();
            if (position.getWorld() != null && isSafe(position))
                pending.put(player.getUniqueId(), encode(position));
        }
        pending.forEach(this::persist);
    }

    private void restore(Player player, Location initial, int epoch) {
        if (!player.isOnline() || !java.util.Objects.equals(joinEpoch.get(player.getUniqueId()), epoch)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String value = pending.get(player.getUniqueId());
            if (value == null) value = redis("GET", key(player.getUniqueId()));
            if (value == null || value.isBlank()) return;
            final String saved = value;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || !java.util.Objects.equals(joinEpoch.get(player.getUniqueId()), epoch)
                        || player.getWorld() != initial.getWorld()
                        || player.getLocation().distanceSquared(initial) > 1.0) return;
                Location location = decode(saved);
                if (location == null || !isSafe(location)) return;
                if (initial.distanceSquared(initial.getWorld().getSpawnLocation()) > 16.0) return;
                if (!location.getWorld().getWorldBorder().isInside(location)) return;
                boolean moved = player.teleport(location);
                if (moved) plugin.getLogger().info("Restored " + server + " position for " + player.getName() + ".");
            });
        });
    }

    private String key(UUID uuid) { return PREFIX + server + ":" + uuid; }

    private String encode(Location location) {
        String world = location.getWorld().getName();
        String data = world + "|" + location.getX() + "|" + location.getY() + "|" + location.getZ()
                + "|" + location.getYaw() + "|" + location.getPitch();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data.getBytes(StandardCharsets.UTF_8));
    }

    private Location decode(String value) {
        try {
            String[] p = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
            if (p.length != 6) return null;
            org.bukkit.World world = Bukkit.getWorld(p[0]);
            if (world == null) return null;
            return new Location(world, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    Float.parseFloat(p[4]), Float.parseFloat(p[5]));
        } catch (RuntimeException ignored) { return null; }
    }

    private boolean isSafe(Location location) {
        return Double.isFinite(location.getX()) && Double.isFinite(location.getY()) && Double.isFinite(location.getZ())
                && location.getY() > -64 && location.getY() < 320;
    }

    private String redis(String... command) {
        try {
            org.bukkit.configuration.file.YamlConfiguration c = org.bukkit.configuration.file.YamlConfiguration
                    .loadConfiguration(new java.io.File(plugin.getDataFolder().getParentFile(), "HuskSync/config.yml"));
            String host = c.getString("redis.credentials.host", "127.0.0.1");
            int port = c.getInt("redis.credentials.port", 6379);
            String password = c.getString("redis.credentials.password", "");
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 2000);
                socket.setSoTimeout(2000);
                InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream();
                if (!password.isBlank() && send(out, in, "AUTH", password) == null) return null;
                return send(out, in, command);
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.FINE, "Last location Redis operation failed", ex);
            return null;
        }
    }

    private String send(OutputStream out, InputStream in, String... values) throws IOException {
        StringBuilder q = new StringBuilder("*").append(values.length).append("\r\n");
        for (String value : values) q.append("$").append(value.getBytes(StandardCharsets.UTF_8).length)
                .append("\r\n").append(value).append("\r\n");
        out.write(q.toString().getBytes(StandardCharsets.UTF_8)); out.flush();
        int type = in.read(); if (type < 0 || type == '-') { line(in); return null; }
        String header = line(in);
        if (type == '$') { int length = Integer.parseInt(header); if (length < 0) return null; byte[] b = in.readNBytes(length); in.read(); in.read(); return new String(b, StandardCharsets.UTF_8); }
        return header;
    }
    private String line(InputStream in) throws IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); int n; while ((n = in.read()) != -1 && n != '\r') b.write(n); if (n < 0 || in.read() != '\n') throw new IOException("Malformed Redis response"); return b.toString(StandardCharsets.UTF_8); }
}
