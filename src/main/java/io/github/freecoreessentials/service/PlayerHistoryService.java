package io.github.freecoreessentials.service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Tracks player login IPs and last seen timestamps across proxy servers using Redis.
 * Provides /seen and /ip commands.
 */
public final class PlayerHistoryService implements Listener, CommandExecutor, TabCompleter {
    private static final String PREFIX_LAST_SEEN = "fce:user:lastseen:"; // hash: server, time, name
    private static final String PREFIX_IP_CURRENT = "fce:user:currentip:";
    private static final String PREFIX_IP_HISTORY = "fce:user:iphystory:"; // set of IPs
    private static final String PREFIX_IP_TO_PLAYERS = "fce:ip:players:"; // set of player names
    private static final String PREFIX_NAME_TO_UUID = "fce:user:nametouuid:";

    private final JavaPlugin plugin;

    public PlayerHistoryService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private String serverId() {
        return Bukkit.getPort() == 25570 ? "technical" : "survival";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                ? player.getAddress().getAddress().getHostAddress()
                : "未知";

        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            // Save name -> UUID mapping
            redis("SET", PREFIX_NAME_TO_UUID + name.toLowerCase(Locale.ROOT), uuid.toString());
            // Record current IP & add to IP history set
            if (!ip.equals("未知")) {
                redis("SET", PREFIX_IP_CURRENT + uuid, ip);
                redis("SADD", PREFIX_IP_HISTORY + uuid, ip);
                redis("SADD", PREFIX_IP_TO_PLAYERS + ip, name);
            }
            // Update last seen (online now)
            redis("HSET", PREFIX_LAST_SEEN + uuid,
                    "server", serverId(),
                    "time", String.valueOf(System.currentTimeMillis()),
                    "name", name,
                    "online", "true");
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            redis("HSET", PREFIX_LAST_SEEN + uuid,
                    "server", serverId(),
                    "time", String.valueOf(System.currentTimeMillis()),
                    "name", name,
                    "online", "false");
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("seen")) {
            return handleSeen(sender, args);
        } else if (cmd.equals("ip")) {
            return handleIp(sender, args);
        }
        return false;
    }

    private boolean handleSeen(CommandSender sender, String[] args) {
        if (!sender.hasPermission("freecoreessentials.seen")) {
            sender.sendMessage(ChatColor.RED + "你没有使用 /seen 的权限。");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "用法: /seen <玩家名称>");
            return true;
        }

        String targetName = args[0];
        sender.sendMessage(ChatColor.GRAY + "正在查询玩家 " + targetName + " 的跨服动态...");

        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            // Check if player is locally online
            Player local = Bukkit.getPlayerExact(targetName);
            String uuidStr = local != null ? local.getUniqueId().toString()
                    : redis("GET", "fce:online:name:" + targetName.toLowerCase(Locale.ROOT) + ":uuid");
            if (uuidStr == null) {
                uuidStr = redis("GET", PREFIX_NAME_TO_UUID + targetName.toLowerCase(Locale.ROOT));
            }

            if (uuidStr == null) {
                Bukkit.getScheduler().runTask(this.plugin, () ->
                        sender.sendMessage(ChatColor.RED + "未找到玩家 " + targetName + " 的任何游玩记录。"));
                return;
            }

            String onlineServer = redis("GET", "fce:online:uuid:" + uuidStr);
            String lastServer = redis("HGET", PREFIX_LAST_SEEN + uuidStr, "server");
            String lastTimeStr = redis("HGET", PREFIX_LAST_SEEN + uuidStr, "time");
            String realName = redis("HGET", PREFIX_LAST_SEEN + uuidStr, "name");
            if (realName == null) realName = targetName;

            String finalUuid = uuidStr;
            String displayName = realName;
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                sender.sendMessage(ChatColor.GOLD + "========== [ 玩家 " + displayName + " 记录 ] ==========");
                sender.sendMessage(ChatColor.YELLOW + "UUID: " + ChatColor.WHITE + finalUuid);

                if (onlineServer != null && !onlineServer.isBlank()) {
                    String serverDisplay = "survival".equalsIgnoreCase(onlineServer) ? "生存服 (Survival)" : "生电服 (Technical)";
                    sender.sendMessage(ChatColor.YELLOW + "状态: " + ChatColor.GREEN + "在线"
                            + ChatColor.GRAY + " [当前所在: " + ChatColor.AQUA + serverDisplay + ChatColor.GRAY + "]");
                } else {
                    String serverDisplay = lastServer != null ? ("survival".equalsIgnoreCase(lastServer) ? "生存服 (Survival)" : "生电服 (Technical)") : "未知子服";
                    sender.sendMessage(ChatColor.YELLOW + "状态: " + ChatColor.RED + "离线");
                    sender.sendMessage(ChatColor.YELLOW + "最后离线子服: " + ChatColor.AQUA + serverDisplay);
                    if (lastTimeStr != null) {
                        try {
                            long lastTime = Long.parseLong(lastTimeStr);
                            long diff = Math.max(0, System.currentTimeMillis() - lastTime);
                            String timeAgo = formatDuration(diff);
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.SIMPLIFIED_CHINESE);
                            sender.sendMessage(ChatColor.YELLOW + "最后在线时间: " + ChatColor.WHITE + sdf.format(new Date(lastTime))
                                    + ChatColor.GRAY + " (" + timeAgo + "前)");
                        } catch (NumberFormatException ignored) {}
                    }
                }
                sender.sendMessage(ChatColor.GOLD + "========================================");
            });
        });
        return true;
    }

    private boolean handleIp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("freecoreessentials.ip")) {
            sender.sendMessage(ChatColor.RED + "你没有使用 /ip 的权限。");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "用法: /ip <玩家名称|IP地址>");
            return true;
        }

        String targetArg = args[0].trim();

        // Check if query is an IP address
        if (targetArg.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            String ip = targetArg;
            sender.sendMessage(ChatColor.GRAY + "正在查询 IP [" + ip + "] 关联的玩家与归属地...");
            Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
                String location = io.github.freecoreessentials.util.IpLocationResolver.resolveLocation(ip);
                List<String> players = redisSmembers(PREFIX_IP_TO_PLAYERS + ip);
                Bukkit.getScheduler().runTask(this.plugin, () -> {
                    sender.sendMessage(ChatColor.GOLD + "========== [ IP 关联玩家反查 ] ==========");
                    sender.sendMessage(ChatColor.YELLOW + "目标 IP: " + ChatColor.AQUA + ip);
                    sender.sendMessage(ChatColor.YELLOW + "归属地与运营商: " + ChatColor.GREEN + location);
                    if (players.isEmpty()) {
                        sender.sendMessage(ChatColor.GRAY + "未记录到任何使用该 IP 登录过的玩家。");
                    } else {
                        sender.sendMessage(ChatColor.YELLOW + "该 IP 下登录过的玩家 (" + players.size() + " 人):");
                        for (String p : players) {
                            sender.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + p);
                        }
                    }
                    sender.sendMessage(ChatColor.GOLD + "=========================================");
                });
            });
            return true;
        }

        String targetName = targetArg;
        sender.sendMessage(ChatColor.GRAY + "正在查询玩家 " + targetName + " 的 IP 记录与归属地...");

        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            Player local = Bukkit.getPlayerExact(targetName);
            String uuidStr = local != null ? local.getUniqueId().toString()
                    : redis("GET", "fce:online:name:" + targetName.toLowerCase(Locale.ROOT) + ":uuid");
            if (uuidStr == null) {
                uuidStr = redis("GET", PREFIX_NAME_TO_UUID + targetName.toLowerCase(Locale.ROOT));
            }

            if (uuidStr == null) {
                Bukkit.getScheduler().runTask(this.plugin, () ->
                        sender.sendMessage(ChatColor.RED + "未找到玩家 " + targetName + " 的记录。"));
                return;
            }

            String currentIp = redis("GET", PREFIX_IP_CURRENT + uuidStr);
            List<String> historyIps = redisSmembers(PREFIX_IP_HISTORY + uuidStr);
            String currentLocation = currentIp != null ? io.github.freecoreessentials.util.IpLocationResolver.resolveLocation(currentIp) : "未知";

            // Resolve location for all history IPs
            java.util.Map<String, String> historyLocations = new java.util.LinkedHashMap<>();
            for (String hip : historyIps) {
                historyLocations.put(hip, io.github.freecoreessentials.util.IpLocationResolver.resolveLocation(hip));
            }

            String finalUuid = uuidStr;
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                sender.sendMessage(ChatColor.GOLD + "========== [ 玩家 " + targetName + " IP 信息 ] ==========");
                sender.sendMessage(ChatColor.YELLOW + "UUID: " + ChatColor.WHITE + finalUuid);
                sender.sendMessage(ChatColor.YELLOW + "当前/最近登录 IP: " + ChatColor.AQUA + (currentIp != null ? currentIp : "未知")
                        + " " + ChatColor.GREEN + "[" + currentLocation + "]");
                if (historyIps.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "历史登录 IP 列表: " + ChatColor.GRAY + "暂无更多记录");
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "历史登录 IP 列表 (" + historyIps.size() + " 个):");
                    for (String ip : historyIps) {
                        String loc = historyLocations.getOrDefault(ip, "未知");
                        sender.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + ip
                                + " " + ChatColor.DARK_GRAY + "[" + ChatColor.YELLOW + loc + ChatColor.DARK_GRAY + "]"
                                + (ip.equals(currentIp) ? ChatColor.GREEN + " [最新]" : ""));
                    }
                }
                sender.sendMessage(ChatColor.GOLD + "=========================================");
            });
        });
        return true;
    }

    private static String formatDuration(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + " 秒";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " 分钟";
        long hours = minutes / 60;
        long remMinutes = minutes % 60;
        if (hours < 24) return hours + " 小时 " + remMinutes + " 分钟";
        long days = hours / 24;
        long remHours = hours % 24;
        return days + " 天 " + remHours + " 小时";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> list = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    list.add(p.getName());
                }
            }
            return list;
        }
        return Collections.emptyList();
    }

    // --- Redis Connection Utility ---
    private Config config() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(
                new File(this.plugin.getDataFolder().getParentFile(), "HuskSync/config.yml"));
        return new Config(c.getString("redis.credentials.host", "127.0.0.1"),
                c.getInt("redis.credentials.port", 6379),
                c.getString("redis.credentials.password", ""));
    }

    private String redis(String... cmd) {
        try {
            Config cfg = config();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(cfg.host, cfg.port), 2000);
                s.setSoTimeout(2000);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                if (!cfg.password.isEmpty() && send(out, in, "AUTH", cfg.password) == null) return null;
                return send(out, in, cmd);
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private List<String> redisSmembers(String key) {
        List<String> result = new ArrayList<>();
        try {
            Config cfg = config();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(cfg.host, cfg.port), 2000);
                s.setSoTimeout(2000);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                if (!cfg.password.isEmpty() && send(out, in, "AUTH", cfg.password) == null) return result;
                write(out, "SMEMBERS", key);
                int t = in.read();
                if (t != '*') {
                    if (t == '+' || t == '-' || t == ':' || t == '$') line(in);
                    return result;
                }
                int count = Integer.parseInt(line(in));
                for (int i = 0; i < count; i++) {
                    int type = in.read();
                    if (type == '$') {
                        int len = Integer.parseInt(line(in));
                        if (len >= 0) {
                            byte[] b = in.readNBytes(len);
                            in.read(); in.read(); // \r\n
                            result.add(new String(b, StandardCharsets.UTF_8));
                        }
                    } else if (type == ':') {
                        result.add(line(in));
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private String send(OutputStream out, InputStream in, String... values) throws IOException {
        write(out, values);
        int t = in.read();
        if (t < 0 || t == '-') { line(in); return null; }
        String l = line(in);
        if (t == '$') {
            int n = Integer.parseInt(l);
            if (n < 0) return null;
            byte[] b = in.readNBytes(n);
            in.read(); in.read();
            return new String(b, StandardCharsets.UTF_8);
        }
        return l;
    }

    private void write(OutputStream out, String... values) throws IOException {
        StringBuilder q = new StringBuilder("*").append(values.length).append("\r\n");
        for (String v : values) {
            q.append("$").append(v.getBytes(StandardCharsets.UTF_8).length).append("\r\n")
             .append(v).append("\r\n");
        }
        out.write(q.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int n;
        while ((n = in.read()) != -1 && n != '\r') b.write(n);
        if (n < 0 || in.read() != '\n') throw new IOException("redis");
        return b.toString(StandardCharsets.UTF_8);
    }

    private record Config(String host, int port, String password) {}
}
