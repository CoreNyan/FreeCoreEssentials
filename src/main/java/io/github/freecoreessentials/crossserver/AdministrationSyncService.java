package io.github.freecoreessentials.crossserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Broadcasts vanilla operator and ban-list mutations to the peer backend. */
public final class AdministrationSyncService implements Listener {
    private static final String CHANNEL = "fce:crossserver";
    private static final Set<String> ADMIN_COMMANDS = Set.of(
            "op", "deop", "ban", "pardon", "ban-ip", "pardon-ip");
    private final JavaPlugin plugin;
    private volatile boolean running;
    private Thread listener;
    private final ThreadLocal<Boolean> applyingRemote = ThreadLocal.withInitial(() -> false);

    public AdministrationSyncService(JavaPlugin plugin) { this.plugin = plugin; }

    public void start() {
        if (!plugin.getConfig().getBoolean("administration-sync.enabled", true)) {
            plugin.getLogger().info("Cross-server operator and ban-list synchronization is disabled in config.yml.");
            return;
        }
        running = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        listener = new Thread(this::listen, "FreeCoreEssentials-AdminSync");
        listener.setDaemon(true);
        listener.start();
        plugin.getLogger().info("Cross-server operator and ban-list synchronization enabled.");
    }

    public void close() {
        running = false;
        if (listener != null) listener.interrupt();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (event.isCancelled()) return;
        Player player = event.getPlayer();
        if (!player.isOp() && !player.hasPermission("minecraft.command.op")) return;
        String command = normalize(event.getMessage());
        if (isAdminCommand(command)) publishToPeer("ADMIN|" + encode(command));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onServerCommand(ServerCommandEvent event) {
        if (applyingRemote.get()) return;
        String command = normalize(event.getCommand());
        if (isAdminCommand(command)) publishToPeer("ADMIN|" + encode(command));
    }

    private void handle(String message) {
        String[] parts = message.split("\\|", 3);
        if (parts.length < 3 || !serverId().equals(parts[0]) || !"ADMIN".equals(parts[1])) return;
        final String command;
        try { command = decode(parts[2]); } catch (IllegalArgumentException ignored) { return; }
        if (!isAdminCommand(command)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            applyingRemote.set(true);
            try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command); }
            finally { applyingRemote.set(false); }
        });
    }

    private boolean isAdminCommand(String command) {
        if (command == null || command.isBlank()) return false;
        String root = command.trim().split("\\s+", 2)[0].toLowerCase();
        if (root.startsWith("minecraft:")) root = root.substring("minecraft:".length());
        return ADMIN_COMMANDS.contains(root);
    }

    private String normalize(String command) {
        if (command == null) return "";
        String value = command.trim();
        return value.startsWith("/") ? value.substring(1).trim() : value;
    }

    private void publishToPeer(String payload) {
        String target = "survival".equals(serverId()) ? "technical" : "survival";
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> redis("PUBLISH", CHANNEL, target + "|" + payload));
    }

    private void listen() {
        while (running) try (Socket socket = new Socket()) {
            Config cfg = config();
            socket.connect(new InetSocketAddress(cfg.host, cfg.port), 2000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            if (!cfg.password.isEmpty() && send(out, in, "AUTH", cfg.password) == null) continue;
            write(out, "SUBSCRIBE", CHANNEL);
            String[] subscribed = array(in);
            if (subscribed.length != 3 || !"subscribe".equals(subscribed[0])) throw new IOException("redis subscribe");
            while (running) {
                String[] packet = array(in);
                if (packet.length == 3 && "message".equals(packet[0])) handle(packet[2]);
            }
        } catch (Exception ex) {
            if (!running) return;
            plugin.getLogger().log(Level.WARNING, "Cross-server admin synchronization retry", ex);
            try { Thread.sleep(1000L); } catch (InterruptedException ignored) { return; }
        }
    }

    private String serverId() { return Bukkit.getPort() == 25570 ? "technical" : "survival"; }

    private Config config() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(
                new java.io.File(plugin.getDataFolder().getParentFile(), "HuskSync/config.yml"));
        return new Config(c.getString("redis.credentials.host", "127.0.0.1"),
                c.getInt("redis.credentials.port", 6379), c.getString("redis.credentials.password", ""));
    }

    private String redis(String... command) {
        try {
            Config cfg = config();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(cfg.host, cfg.port), 2000);
                socket.setSoTimeout(2000);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                if (!cfg.password.isEmpty() && send(out, in, "AUTH", cfg.password) == null) return null;
                return send(out, in, command);
            }
        } catch (Exception ignored) { return null; }
    }

    private String send(OutputStream out, InputStream in, String... values) throws IOException {
        write(out, values);
        int type = in.read();
        if (type < 0 || type == '-') { line(in); return null; }
        String header = line(in);
        if (type == '$') {
            int length = Integer.parseInt(header);
            if (length < 0) return null;
            byte[] body = in.readNBytes(length);
            in.read(); in.read();
            return new String(body, StandardCharsets.UTF_8);
        }
        return header;
    }

    private void write(OutputStream out, String... values) throws IOException {
        StringBuilder request = new StringBuilder("*").append(values.length).append("\\r\\n");
        for (String value : values) {
            request.append("$").append(value.getBytes(StandardCharsets.UTF_8).length).append("\\r\\n")
                    .append(value).append("\\r\\n");
        }
        out.write(request.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private String line(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int value;
        while ((value = in.read()) != -1 && value != '\r') buffer.write(value);
        if (value < 0 || in.read() != '\n') throw new IOException("redis response");
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private String[] array(InputStream in) throws IOException {
        if (in.read() != '*') throw new IOException("redis array");
        int count = Integer.parseInt(line(in));
        String[] values = new String[count];
        for (int i = 0; i < count; i++) {
            int type = in.read();
            if (type == ':') { values[i] = line(in); continue; }
            if (type != '$') throw new IOException("redis bulk");
            int length = Integer.parseInt(line(in));
            byte[] body = in.readNBytes(length);
            in.read(); in.read();
            values[i] = new String(body, StandardCharsets.UTF_8);
        }
        return values;
    }

    private String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private String decode(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
    private record Config(String host, int port, String password) {}
}
