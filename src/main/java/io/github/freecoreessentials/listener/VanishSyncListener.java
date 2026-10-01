package io.github.freecoreessentials.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import simplexity.simplevanish.events.PlayerUnvanishEvent;
import simplexity.simplevanish.events.PlayerVanishEvent;
import simplexity.simplevanish.handling.UnvanishHandler;
import simplexity.simplevanish.handling.VanishHandler;
import simplexity.simplevanish.saving.Cache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

/** Publishes SimpleVanish state so the proxy-owned global TAB can hide staff. */
public final class VanishSyncListener implements Listener {
    private static final String CHANNEL = "fctransfer:vanish";
    private final org.bukkit.plugin.java.JavaPlugin plugin;

    public VanishSyncListener(org.bukkit.plugin.java.JavaPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::publishCurrent, 20L, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVanish(PlayerVanishEvent event) {
        setState(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnvanish(PlayerUnvanishEvent event) {
        setState(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            boolean vanished = "1".equals(redis("GET", stateKey(player)));
            Bukkit.getScheduler().runTask(plugin, () -> applyRemoteState(player, vanished));
        }, 20L);
    }

    private void publishCurrent() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            publish(player, Cache.getVanishedPlayers().contains(player));
        }
    }

    private void setState(Player player, boolean vanished) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (vanished) redis("SET", stateKey(player), "1");
            else redis("DEL", stateKey(player));
            publishNow(player, vanished);
        });
    }

    private void applyRemoteState(Player player, boolean vanished) {
        if (!player.isOnline()) return;
        boolean current = Cache.getVanishedPlayers().contains(player);
        if (current == vanished) {
            publish(player, vanished);
            return;
        }
        if (vanished) VanishHandler.getInstance().runVanishEvent(player, false, "");
        else UnvanishHandler.getInstance().runUnvanishEvent(player, false, "");
    }

    private String stateKey(Player player) {
        return "freecore:vanish:" + player.getUniqueId();
    }

    private void publish(Player player, boolean vanished) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> publishNow(player, vanished));
    }

    private void publishNow(Player player, boolean vanished) {
        String payload = player.getUniqueId() + "|" + (vanished ? "1" : "0");
        redis("PUBLISH", CHANNEL, payload);
    }

    private String redis(String... command) {
        try {
            org.bukkit.configuration.file.YamlConfiguration c = org.bukkit.configuration.file.YamlConfiguration
                    .loadConfiguration(new java.io.File(plugin.getDataFolder().getParentFile(), "HuskSync/config.yml"));
            String host = c.getString("redis.credentials.host", "127.0.0.1");
            int port = c.getInt("redis.credentials.port", 6379);
            int database = c.getInt("redis.credentials.database", 0);
            String password = c.getString("redis.credentials.password", "");
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 2000);
                socket.setSoTimeout(2000);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                if (!password.isEmpty() && send(out, in, "AUTH", password) == null) return null;
                if (database != 0 && send(out, in, "SELECT", Integer.toString(database)) == null) return null;
                return send(out, in, command);
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.FINE, "Vanish Redis publish failed", ex);
            return null;
        }
    }

    private String send(OutputStream out, InputStream in, String... values) throws IOException {
        StringBuilder request = new StringBuilder("*").append(values.length).append("\r\n");
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            request.append("$").append(bytes.length).append("\r\n").append(value).append("\r\n");
        }
        out.write(request.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        int type = in.read();
        if (type == -1 || type == '-') { readLine(in); return null; }
        String line = readLine(in);
        if (type == '$') {
            int length = Integer.parseInt(line);
            if (length < 0) return null;
            byte[] data = in.readNBytes(length);
            in.read(); in.read();
            return new String(data, StandardCharsets.UTF_8);
        }
        return line;
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int next;
        while ((next = in.read()) != -1 && next != '\r') bytes.write(next);
        if (next == -1 || in.read() != '\n') throw new IOException("Malformed Redis response");
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
