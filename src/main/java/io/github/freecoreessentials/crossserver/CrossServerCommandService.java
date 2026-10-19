package io.github.freecoreessentials.crossserver;

import io.github.freecoreessentials.lang.Lang;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.william278.husksync.api.BukkitHuskSyncAPI;
import net.william278.husksync.user.User;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/** Cross-server commands and database-backed inventory viewing. */
public final class CrossServerCommandService implements Listener, org.bukkit.command.CommandExecutor {
    private static final String CHANNEL = "fce:crossserver";
    private static final String TICKET_PREFIX = "fce:command:";
    private static final String TPA_REQUEST_PREFIX = "fce:tpa:req:";
    private static final String TPA_TARGET_PREFIX = "fce:tpa:target:";
    private static final String TPA_TICKET_PREFIX = "fce:tpa:ticket:";
    private static final String INVSEE_STATE_PREFIX = "fce:invsee:state:";
    private static final String GAMEMODE_PREFIX = "fce:gamemode:";
    private static final String MUTE_PREFIX = "fce:mute:";
    private static final String NICK_PREFIX = "fce:nick:";
    private static final String MAINTENANCE_PREFIX = "fce:maintenance:";
    private static final Set<String> PLAYER_COMMANDS = Set.of(
            "give", "tp", "teleport", "gamemode", "effect", "clear", "enchant", "experience", "xp",
            "attribute", "data", "item", "damage", "kill", "spectate", "ride", "title",
            "team", "playsound", "stopsound", "advancement", "recipe", "kick", "ban",
            "pardon", "op", "deop", "whitelist", "tellraw", "spawnpoint", "tag", "scoreboard",
            "bossbar", "clone", "datapack", "debug", "defaultgamemode", "difficulty", "execute", "fill",
            "forceload", "function", "gamerule", "help", "jfr", "list", "locate", "loot", "me", "particle",
            "place", "publish", "random", "reload", "save-all", "save-off", "save-on", "say", "schedule",
            "seed", "setblock", "setidletimeout", "setworldspawn", "spreadplayers", "stop", "summon", "time",
            "trigger", "weather", "worldborder");
    private final JavaPlugin plugin;
    private final Lang lang;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, TpaRequest> pendingTpa = new ConcurrentHashMap<>();
    private final Map<UUID, GameMode> desiredGameModes = new ConcurrentHashMap<>();
    private final Map<UUID, GameMode> requestedGameModes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> worldChangeNanos = new ConcurrentHashMap<>();
    private final Set<UUID> applyingGameMode = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> inventoryVersions = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> pendingInventoryTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> pendingLiveInventoryTasks = new ConcurrentHashMap<>();
    private final Map<UUID, CachedNickname> nicknameCache = new ConcurrentHashMap<>();
    private volatile Config cachedConfig;
    private final ExecutorService inventoryWrites = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "FreeCoreEssentials-inventory-writer");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean running;
    private boolean applyingRemoteAdministration;

    private record CachedNickname(String nick, long expireAtMillis) {}
    private record TpaRequest(String id, UUID requesterId, String requesterName, String requesterServer,
                              UUID targetId, String targetName, String targetServer, boolean isHere) {}

    public CrossServerCommandService(JavaPlugin plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    public void start() {
        running = true;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, (Runnable) this::publishPresence, 20L, 100L);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::listen);
        Bukkit.getOnlinePlayers().forEach(this::publishPresence);

        // Globally enforce gamerule sendCommandFeedback = false on all worlds to silence vanilla command broadcasts
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (org.bukkit.World w : Bukkit.getWorlds()) {
                try {
                    w.setGameRule(org.bukkit.GameRule.SEND_COMMAND_FEEDBACK, false);
                } catch (Throwable ignored) {}
            }
        }, 1L, 100L);
    }

    public void close() {
        running = false;
        inventoryWrites.shutdownNow();
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, "BungeeCord");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        if (isMaintenanceActive(serverId()) || isMaintenanceActive("all")) {
            // Check if player has bypass or op
            UUID uuid = event.getUniqueId();
            org.bukkit.OfflinePlayer opCheck = Bukkit.getOfflinePlayer(uuid);
            if (opCheck != null && opCheck.isOp()) {
                return; // OP allowed
            }
            // Check LuckPerms permission if available
            try {
                net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
                if (lp != null) {
                    net.luckperms.api.model.user.User u = lp.getUserManager().loadUser(uuid).join();
                    if (u != null && u.getCachedData().getPermissionData().checkPermission("freecoreessentials.maintenance.bypass").asBoolean()) {
                        return; // Bypass allowed
                    }
                }
            } catch (Throwable ignored) {}

            String kickMsg = ChatColor.translateAlternateColorCodes('&',
                    this.lang.message("maintenance.kick-message"));
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMsg);
        }
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        // Restore nickname if present
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String nick = getPlayerNickname(event.getPlayer().getUniqueId());
            if (nick != null && !nick.isBlank()) {
                String formatted = ChatColor.translateAlternateColorCodes('&', nick);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (event.getPlayer().isOnline()) {
                        event.getPlayer().setDisplayName(formatted);
                        event.getPlayer().setPlayerListName(formatted);
                    }
                });
            }
        });

        // HuskSync owns the login inventory restore. Never apply a Redis
        // InvSee snapshot here: it may be older than the live inventory and
        // overwrite items during login, crafting, or cursor actions.
        Bukkit.getScheduler().runTaskLater(plugin, () -> restoreGameMode(event.getPlayer()), 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> restoreGameMode(event.getPlayer()), 60L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> restoreGameMode(event.getPlayer()), 120L);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            publishPresence(event.getPlayer());
            Bukkit.getScheduler().runTask(plugin, () -> pollQueuedCommand(event.getPlayer(), 0));
            Bukkit.getScheduler().runTask(plugin, () -> pollTpaTicket(event.getPlayer(), 0));
        });
    }

    /** Re-applies the player's last selected mode after HuskSync/world plugins finish joining. */
    private void restoreGameMode(Player player) {
        if (!player.isOnline() || player.getGameMode() == GameMode.SPECTATOR) return;
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Prefer the in-memory mode for same-backend world changes so a
            // just-issued /gamemode command cannot be overwritten by a stale
            // Redis read that is still waiting for its asynchronous SET.
            GameMode mode = desiredGameModes.get(uuid);
            if (mode == null) mode = parseGameMode(redis("GET", GAMEMODE_PREFIX + uuid));
            if (mode == null) return;
            GameMode selected = mode;
            desiredGameModes.put(uuid, selected);
            Bukkit.getScheduler().runTask(plugin, () -> applyGameMode(player, selected));
        });
    }

    private void applyGameMode(Player player, GameMode mode) {
        // Preserve an active spectator session only for delayed restore; explicit remote
        // gamemode requests must still be able to leave spectator mode.
        applyGameMode(player, mode, true);
    }

    private void applyGameMode(Player player, GameMode mode, boolean preserveSpectator) {
        if (!player.isOnline() || (preserveSpectator && player.getGameMode() == GameMode.SPECTATOR)
                || player.getGameMode() == mode) return;
        UUID uuid = player.getUniqueId();
        applyingGameMode.add(uuid);
        try {
            player.setGameMode(mode);
        } finally {
            applyingGameMode.remove(uuid);
        }
    }

    private static GameMode parseGameMode(String value) {
        if (value == null || value.isBlank()) return null;
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "0", "s", "survival" -> GameMode.SURVIVAL;
            case "1", "c", "creative" -> GameMode.CREATIVE;
            case "2", "a", "adventure" -> GameMode.ADVENTURE;
            case "3", "sp", "spectator" -> GameMode.SPECTATOR;
            default -> {
                try {
                    yield GameMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    yield null;
                }
            }
        };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        GameMode mode = event.getNewGameMode();

        // Enforce sendCommandFeedback = false on all worlds to suppress vanilla broadcast messages
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            try {
                w.setGameRule(org.bukkit.GameRule.SEND_COMMAND_FEEDBACK, false);
            } catch (Throwable ignored) {}
        }

        if (applyingGameMode.remove(uuid)) return;
        GameMode requested = requestedGameModes.remove(uuid);
        GameMode desired = desiredGameModes.get(uuid);
        if (requested == mode || desired == null) {
            rememberGameMode(uuid, mode);
            return;
        }
        if (recentlyChangedWorld(uuid)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player current = Bukkit.getPlayer(uuid);
                if (current != null && current.isOnline()) applyGameMode(current, desired);
            }, 1L);
        } else if (desired != mode) {
            // Changes outside a world-transition window are treated as an
            // intentional console/admin change (for example /gamemode <mode>
            // <player>) and become the new persistent mode.
            rememberGameMode(uuid, mode);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrossWorldTeleport(PlayerTeleportEvent event) {
        if (event.getFrom().getWorld() != null && event.getTo().getWorld() != null
                && event.getFrom().getWorld() != event.getTo().getWorld()) {
            worldChangeNanos.put(event.getPlayer().getUniqueId(), System.nanoTime());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        worldChangeNanos.put(player.getUniqueId(), System.nanoTime());
        // Some world/Multiverse handlers force SURVIVAL after this event. Run
        // twice so the selected mode wins after both the world and sync hooks.
        Bukkit.getScheduler().runTaskLater(plugin, () -> restoreGameMode(player), 2L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> restoreGameMode(player), 20L);
    }

    private boolean recentlyChangedWorld(UUID uuid) {
        Long changed = worldChangeNanos.get(uuid);
        return changed != null && System.nanoTime() - changed < 5_000_000_000L;
    }

    private void rememberGameMode(UUID uuid, GameMode mode) {
        desiredGameModes.put(uuid, mode);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            redis("SET", GAMEMODE_PREFIX + uuid, mode.name(), "EX", "604800");
            publish(peerServer(), "MODE|" + uuid + "|" + mode.name());
        });
    }

    public void rememberRequestedMode(Player player, GameMode mode) {
        requestedGameModes.put(player.getUniqueId(), mode);
        rememberGameMode(player.getUniqueId(), mode);
    }

    private static GameMode requestedMode(String[] parts, Player sender) {
        if (parts.length < 2) return null;
        String target = parts.length >= 3 ? parts[2] : sender.getName();
        if (!target.equalsIgnoreCase(sender.getName()) && !target.equalsIgnoreCase("@s")) return null;
        return switch (parts[1].toLowerCase(Locale.ROOT)) {
            case "0", "survival", "s" -> GameMode.SURVIVAL;
            case "1", "creative", "c" -> GameMode.CREATIVE;
            case "2", "adventure", "a" -> GameMode.ADVENTURE;
            case "3", "spectator", "sp" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    private static GameMode requestedModeForTarget(String[] parts, Player target) {
        if (parts.length < 2) return null;
        String value = parts[1].toLowerCase(Locale.ROOT);
        if (parts.length < 3) return target.getGameMode();
        String name = parts[2];
        if (!name.equalsIgnoreCase(target.getName()) && !name.equalsIgnoreCase("@s")) return null;
        return switch (value) {
            case "0", "survival", "s" -> GameMode.SURVIVAL;
            case "1", "creative", "c" -> GameMode.CREATIVE;
            case "2", "adventure", "a" -> GameMode.ADVENTURE;
            case "3", "spectator", "sp" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    private void pollQueuedCommand(Player player, int attempt) {
        if (!player.isOnline()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String scopedKey = TICKET_PREFIX + serverId().toLowerCase(Locale.ROOT) + ":" + player.getUniqueId();
            String legacyKey = TICKET_PREFIX + player.getUniqueId();
            String ticket = redis("GET", scopedKey);
            String matchedKey = scopedKey;
            if (ticket == null) {
                ticket = redis("GET", legacyKey);
                matchedKey = legacyKey;
            }
            if (ticket == null) {
                if (attempt < 120) Bukkit.getScheduler().runTaskLater(plugin,
                        () -> pollQueuedCommand(player, attempt + 1), 5L);
                else plugin.getLogger().warning("No cross-server command ticket found after connect for " + player.getName());
                return;
            }
            redis("DEL", matchedKey);
            String commandLine = decode(ticket);
            plugin.getLogger().info("Found cross-server command ticket after connect for " + player.getName()
                    + ": /" + commandLine);
            Bukkit.getScheduler().runTaskLater(plugin, () -> runQueuedCommand(player, commandLine, 0), 15L);
        });
    }

    private void runQueuedCommand(Player player, String commandLine, int attempt) {
        if (!player.isOnline()) return;
        boolean teleport = isTeleportCommand(commandLine);
        boolean targetReady = player.getWorld() != null && player.isValid() && commandTargetReady(commandLine);
        if (teleport && !targetReady) {
            if (attempt < 100) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> runQueuedCommand(player, commandLine, attempt + 1), 2L);
            } else {
                plugin.getLogger().warning("Cross-server teleport target was not online after connect for "
                        + player.getName() + ": /" + commandLine);
                player.sendMessage(ChatColor.RED + "目标玩家尚未进入当前子服，位置传送失败，请重试。" );
            }
            return;
        }
        if (executeDirectTeleport(player, commandLine)) return;
        if (!executeCommandWithResult(player, commandLine, success -> {
            if (success) sendSuccessfulFeedback(player, commandLine);
        }) && attempt < (teleport ? 100 : 20)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> runQueuedCommand(player, commandLine, attempt + 1), 2L);
        }
    }

    private static boolean isTeleportCommand(String commandLine) {
        String[] parts = commandLine.trim().split("\\s+");
        if (parts.length == 0) return false;
        String root = parts[0].toLowerCase(Locale.ROOT);
        return root.equals("tp") || root.equals("teleport") || root.equals("minecraft:tp")
                || root.equals("minecraft:teleport");
    }

    private Player findOnlinePlayer(String name) {
        if (name == null || name.isBlank()) return null;
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null && exact.isOnline()) return exact;
        Player match = Bukkit.getPlayer(name);
        return match != null && match.isOnline() ? match : null;
    }

    private void scheduleDirectTeleportRetry(Player sender, Player target, int attempt) {
        if (!sender.isOnline() || !target.isOnline()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!sender.isOnline() || !target.isOnline()) return;
            Location current = target.getLocation().clone();
            boolean moved = sender.teleport(current);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                boolean verified = sender.isOnline() && sender.getWorld().equals(current.getWorld())
                        && sender.getLocation().distanceSquared(current) <= 9.0D;
                if (verified) {
                    plugin.getLogger().info("Cross-server direct teleport verified for "
                            + sender.getName() + " to " + target.getName());
                } else if (attempt < 8) {
                    scheduleDirectTeleportRetry(sender, target, attempt + 1);
                } else {
                    plugin.getLogger().warning("Cross-server direct teleport unverified after retries for "
                            + sender.getName() + " to " + target.getName());
                }
            }, 3L);
        }, attempt == 0 ? 5L : 6L);
    }

    private boolean executeDirectTeleport(Player sender, String commandLine) {
        String[] parts = commandLine.trim().split("\\s+");
        if (parts.length < 2) return false;
        String root = parts[0].toLowerCase();
        if (!root.equals("tp") && !root.equals("teleport")) return false;
        Player target = findOnlinePlayer(parts[1]);
        if (target == null || !target.isOnline()) return false;
        // Teleport directly after the cross-server connect.  Running the
        // original command through Brigadier is racy during the backend join,
        // and a command such as /tp target x y z otherwise targets the remote
        // entity through a dispatcher that may not have finished indexing it.
        if (parts.length == 2) {
            boolean moved = sender.teleport(target.getLocation());
            plugin.getLogger().info("Cross-server direct teleport " + (moved ? "completed" : "failed")
                    + " for " + sender.getName() + " to " + target.getName());
            if (moved) {
                scheduleDirectTeleportRetry(sender, target, 0);
                sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                        + this.lang.message("vanilla-command.teleport-completed", target.getName()));
            }
            return moved;
        }
        if (parts.length == 3) {
            Player moved = findOnlinePlayer(parts[1]);
            Player destination = findOnlinePlayer(parts[2]);
            if (destination == null && (parts[2].equalsIgnoreCase("@s") || parts[2].equalsIgnoreCase("@p"))) {
                destination = sender;
            }
            if (moved == null || destination == null || !moved.isOnline() || !destination.isOnline()) return false;
            boolean teleported = moved.teleport(destination.getLocation());
            plugin.getLogger().info("Cross-server direct target teleport " + (teleported ? "completed" : "failed")
                    + " for " + moved.getName() + " to " + destination.getName());
            if (teleported) {
                scheduleDirectTeleportRetry(moved, destination, 0);
                sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                        + this.lang.message("vanilla-command.teleport-completed", moved.getName()));
            }
            return teleported;
        }
        if (parts.length >= 5 && parts.length <= 7) {
            Location base = sender.getLocation();
            Double x = coordinate(parts[2], base.getX());
            Double y = coordinate(parts[3], base.getY());
            Double z = coordinate(parts[4], base.getZ());
            if (x == null || y == null || z == null) return false;
            float yaw = base.getYaw();
            float pitch = base.getPitch();
            try {
                if (parts.length >= 6) yaw = Float.parseFloat(parts[5].startsWith("~")
                        ? Float.toString((float) (yaw + relative(parts[5]))) : parts[5]);
                if (parts.length == 7) pitch = Float.parseFloat(parts[6].startsWith("~")
                        ? Float.toString((float) (pitch + relative(parts[6]))) : parts[6]);
            } catch (NumberFormatException ignored) {
                return false;
            }
            boolean moved = sender.teleport(new Location(base.getWorld(), x, y, z, yaw, pitch));
            plugin.getLogger().info("Cross-server coordinate teleport " + (moved ? "completed" : "failed")
                    + " for " + sender.getName() + " to " + x + " " + y + " " + z);
            if (moved) sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                    + this.lang.message("vanilla-command.teleport-completed", sender.getName()));
            return moved;
        }
        return false;
    }

    private static Double coordinate(String token, double base) {
        try { return token.startsWith("~") ? base + relative(token) : Double.parseDouble(token); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static double relative(String token) {
        String suffix = token.substring(1);
        return suffix.isEmpty() ? 0.0D : Double.parseDouble(suffix);
    }

    private boolean commandTargetReady(String commandLine) {
        String[] parts = commandLine.trim().split("\\s+");
        if (parts.length < 2) return true;
        String root = parts[0].toLowerCase();
        if (!root.equals("tp") && !root.equals("teleport")) return true;
        String target = parts[1];
        return target.startsWith("@") || findOnlinePlayer(target) != null;
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pendingTpa.remove(uuid);
        inventoryVersions.remove(uuid);
        BukkitTask pending = pendingInventoryTasks.remove(uuid);
        if (pending != null) pending.cancel();
        BukkitTask livePending = pendingLiveInventoryTasks.remove(uuid);
        if (livePending != null) livePending.cancel();
        requestedGameModes.remove(uuid);
        worldChangeNanos.remove(event.getPlayer().getUniqueId());
        redis("DEL", "fce:online:name:" + event.getPlayer().getName().toLowerCase());
        redis("DEL", "fce:online:name:" + event.getPlayer().getName().toLowerCase() + ":uuid");
        redis("DEL", "fce:online:uuid:" + event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim();
        if (!raw.startsWith("/") || raw.length() < 2) return;
        String[] parts = raw.substring(1).split("\\s+");
        String root = parts[0].toLowerCase();
        if (root.startsWith("minecraft:")) root = root.substring("minecraft:".length());
        if (root.equals("msg") || root.equals("tell") || root.equals("w") || root.equals("whisper")) {
            return;
        }
        if (root.equals("tpa") || root.equals("tphere") || root.equals("tpaccept") || root.equals("tpdeny")) {
            event.setCancelled(true);
            handleTpaCommand(event.getPlayer(), root, parts);
            return;
        }
        if (root.equals("gamemode") || root.equals("gm") || root.equals("gamemode2")) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            if (!player.hasPermission("freecoreessentials.gamemode") && !player.hasPermission("minecraft.command.gamemode")) {
                player.sendMessage(ChatColor.RED + "你没有修改游戏模式的权限。");
                return;
            }
            if (parts.length < 2) {
                player.sendMessage(ChatColor.YELLOW + "用法: /" + root + " <0|1|2|3|s|c|a|sp> [玩家]");
                return;
            }
            GameMode mode = parseGameMode(parts[1]);
            if (mode == null) {
                player.sendMessage(ChatColor.RED + "未知游戏模式: " + parts[1] + " (支持 0, 1, 2, 3, s, c, a, sp)");
                return;
            }
            String modeName = switch (mode) {
                case SURVIVAL -> lang.message("game-mode.survival");
                case CREATIVE -> lang.message("game-mode.creative");
                case ADVENTURE -> lang.message("game-mode.adventure");
                case SPECTATOR -> lang.message("game-mode.spectator");
            };
            if (parts.length >= 3) {
                String targetName = parts[2];
                Player target = Bukkit.getPlayerExact(targetName);
                if (target == null) target = Bukkit.getPlayer(targetName);
                if (target != null && target.isOnline()) {
                    io.github.freecoreessentials.listener.GameModeFeedbackListener.suppressNextFeedback(target.getUniqueId());
                    target.setGameMode(mode);
                    target.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                            + this.lang.message("game-mode.changed", modeName));
                    if (!target.equals(player)) {
                        player.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                                + "已将 " + target.getName() + " 的游戏模式切换为 " + modeName + "。");
                    }
                    rememberRequestedMode(target, mode);
                    return;
                }
                if (isPlayerOnlineNetwork(targetName)) {
                    dispatchRemoteGameMode(player, targetName, mode);
                    player.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                            + "已向目标子服发送切换指令：将 " + targetName + " 设置为 " + modeName + "。");
                    return;
                }
                player.sendMessage(ChatColor.RED + "目标玩家 " + targetName + " 不在线。");
                return;
            }
            io.github.freecoreessentials.listener.GameModeFeedbackListener.suppressNextFeedback(player.getUniqueId());
            player.setGameMode(mode);
            player.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                    + this.lang.message("game-mode.changed", modeName));
            rememberRequestedMode(player, mode);
            return;
        }
        if (root.equals("gamerule")) {
            if (parts.length == 2) {
                // Querying a gamerule: /gamerule <rule>
                event.setCancelled(true);
                String ruleName = parts[1];
                org.bukkit.GameRule<?> rule = org.bukkit.GameRule.getByName(ruleName);
                if (rule != null) {
                    Object val = event.getPlayer().getWorld().getGameRuleValue(rule);
                    event.getPlayer().sendMessage(this.lang.message("prefix.freecoreessentials")
                            + ChatColor.GOLD + "游戏规则 " + ChatColor.YELLOW + rule.getName() + ChatColor.GOLD + " 当前设置为: " + ChatColor.GREEN + val);
                } else {
                    event.getPlayer().sendMessage(this.lang.message("prefix.freecoreessentials")
                            + ChatColor.RED + "未知的游戏规则: " + ruleName);
                }
                return;
            } else if (parts.length >= 3) {
                // Setting a gamerule: /gamerule <rule> <value>
                event.setCancelled(true);
                String ruleName = parts[1];
                String ruleValue = parts[2];
                executeCommandWithResult(event.getPlayer(), raw.substring(1), success -> {
                    if (success) {
                        publishPeerAdministration(raw.substring(1));
                        event.getPlayer().sendMessage(this.lang.message("prefix.freecoreessentials")
                                + ChatColor.GREEN + "游戏规则 " + ChatColor.YELLOW + ruleName + ChatColor.GREEN + " 已更新为: " + ChatColor.AQUA + ruleValue);
                    } else {
                        sendFailureFeedback(event.getPlayer(), raw.substring(1));
                    }
                });
                return;
            }
        }
        if (isAdministrationCommand(root)) {
            if (event.getPlayer().isOp() || event.getPlayer().hasPermission("minecraft.command.op")) {
                event.setCancelled(true);
                executeCommandWithResult(event.getPlayer(), raw.substring(1), success -> {
                    if (success) {
                        publishPeerAdministration(raw.substring(1));
                        sendSuccessfulFeedback(event.getPlayer(), raw.substring(1));
                    } else {
                        sendFailureFeedback(event.getPlayer(), raw.substring(1));
                    }
                });
            }
            return;
        }
        if (!PLAYER_COMMANDS.contains(root)) return;
        String target = playerArgument(root, parts);
        String targetServer = null;
        if (target != null && !target.startsWith("@")) {
            targetServer = redis("GET", "fce:online:name:" + target.toLowerCase());
            // Support teleporting by real name if target entered with different case or from nick
            if (targetServer == null) {
                String uuidStr = redis("GET", "fce:nick:reverse:" + target.toLowerCase());
                if (uuidStr != null) {
                    targetServer = redis("GET", "fce:online:uuid:" + uuidStr);
                }
            }
        }

        // Intercept query commands with custom logic if requested
        if (targetServer == null || targetServer.equals(serverId())) {
            if (root.equals("seed")) {
                event.setCancelled(true);
                long seed = event.getPlayer().getWorld().getSeed();
                net.kyori.adventure.text.Component comp = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                        .deserialize(this.lang.message("prefix.freecoreessentials")
                                + this.lang.message("vanilla-command.seed", String.valueOf(seed)))
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.copyToClipboard(String.valueOf(seed)))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(net.kyori.adventure.text.Component.text("点击复制种子")));
                event.getPlayer().sendMessage(comp);
                return;
            }
            if (root.equals("worldborder") && parts.length > 1 && parts[1].equalsIgnoreCase("get")) {
                event.setCancelled(true);
                double size = event.getPlayer().getWorld().getWorldBorder().getSize();
                event.getPlayer().sendMessage(this.lang.message("prefix.freecoreessentials")
                        + ChatColor.translateAlternateColorCodes('&', this.lang.message("vanilla-command.worldborder-get", String.format(Locale.ROOT, "%.0f", size))));
                return;
            }
            // If target is completely unknown and this is a player target teleport, provide clear failure rather than bare "No entity was found"
            if ((root.equals("tp") || root.equals("teleport")) && parts.length == 2 && !parts[1].startsWith("@")) {
                Player localTarget = Bukkit.getPlayerExact(parts[1]);
                if (localTarget == null) localTarget = Bukkit.getPlayer(parts[1]);
                if (localTarget == null && targetServer == null) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage(ChatColor.RED + "未找到玩家 " + parts[1] + " (该玩家不在线)。");
                    return;
                }
            }
            // For other local vanilla commands: let Minecraft/Paper execute naturally without duplicate messages
            return;
        }

        if (!event.getPlayer().hasPermission("freecoreessentials.crossserver.command")) return;

        // give/effect/etc. can execute safely as console on the backend where the target is online.
        // A teleport must run in the target backend while the sender is also there, so queue it
        // for the sender's post-connect join and let Velocity move the sender first.
        if (root.equals("tp") || root.equals("teleport")) {
            event.setCancelled(true);
            queueAndConnect(event.getPlayer(), targetServer, raw.substring(1));
            return;
        }
        event.setCancelled(true);
        publish(targetServer, "CMD|" + b64(raw.substring(1)) + "|" + event.getPlayer().getName());
        event.getPlayer().sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                + this.lang.message("vanilla-command.sent", raw.substring(1), targetServer));
    }

    private void sendFailureFeedback(Player player, String commandLine) {
        player.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.RED
                + this.lang.message("vanilla-command.execution-failed", commandLine.trim()));
    }

    private void sendSuccessfulFeedback(Player player, String commandLine) {
        String[] parts = commandLine.trim().split("\\s+");
        if (parts.length == 0) return;
        String root = parts[0].toLowerCase();
        if (root.startsWith("minecraft:")) root = root.substring("minecraft:".length());
        String key;
        Object[] arguments;
        switch (root) {
            case "give" -> {
                if (parts.length < 3) return;
                String target = displayTarget(player, parts[1]);
                String item = parts[2];
                String amount = parts.length > 3 ? parts[3] : "1";
                key = "vanilla-command.give";
                arguments = new Object[]{target, amount, item};
            }
            case "tp", "teleport" -> {
                if (parts.length < 2) return;
                key = "vanilla-command.teleport";
                arguments = new Object[]{teleportDestination(player, parts)};
            }
            case "ban" -> {
                if (parts.length < 2) return;
                key = "vanilla-command.ban";
                arguments = new Object[]{displayTarget(player, parts[1])};
            }
            case "pardon" -> {
                if (parts.length < 2) return;
                key = "vanilla-command.pardon";
                arguments = new Object[]{displayTarget(player, parts[1])};
            }
            case "kick" -> {
                if (parts.length < 2) return;
                key = "vanilla-command.kick";
                arguments = new Object[]{displayTarget(player, parts[1])};
            }
            case "kill" -> {
                key = "vanilla-command.kill";
                arguments = new Object[]{parts.length > 1 ? displayTarget(player, parts[1]) : player.getName()};
            }
            case "clear" -> {
                key = "vanilla-command.clear";
                arguments = new Object[]{parts.length > 1 ? displayTarget(player, parts[1]) : player.getName()};
            }
            case "effect" -> {
                if (parts.length < 3) return;
                key = "vanilla-command.effect";
                arguments = new Object[]{displayTarget(player, parts[2])};
            }
            case "enchant" -> {
                key = "vanilla-command.enchant";
                arguments = new Object[]{parts.length > 1 ? displayTarget(player, parts[1]) : player.getName()};
            }
            case "experience", "xp" -> {
                key = "vanilla-command.experience";
                arguments = new Object[]{playerArgument(root, parts) != null ? displayTarget(player, playerArgument(root, parts)) : player.getName()};
            }
            case "op" -> {
                key = "vanilla-command.op";
                String opTarget = parts.length > 1 ? displayTarget(player, parts[1]) : "";
                arguments = new Object[]{opTarget};
                if (!opTarget.isEmpty()) {
                    Player tp = Bukkit.getPlayerExact(opTarget);
                    if (tp != null && tp.isOnline()) {
                        tp.sendMessage(ChatColor.YELLOW + "你已获得服务器管理员 (OP) 权限。");
                    }
                }
            }
            case "deop" -> {
                key = "vanilla-command.deop";
                String deopTarget = parts.length > 1 ? displayTarget(player, parts[1]) : "";
                arguments = new Object[]{deopTarget};
                if (!deopTarget.isEmpty()) {
                    Player tp = Bukkit.getPlayerExact(deopTarget);
                    if (tp != null && tp.isOnline()) {
                        tp.sendMessage(ChatColor.YELLOW + "你的服务器管理员 (OP) 权限已被收回。");
                    }
                }
            }
            case "whitelist" -> {
                key = "vanilla-command.whitelist";
                arguments = new Object[0];
            }
            case "time" -> {
                key = "vanilla-command.time";
                arguments = new Object[0];
            }
            case "weather" -> {
                key = "vanilla-command.weather";
                arguments = new Object[0];
            }
            case "difficulty" -> {
                key = "vanilla-command.difficulty";
                arguments = new Object[0];
            }
            case "defaultgamemode" -> {
                key = "vanilla-command.defaultgamemode";
                arguments = new Object[0];
            }
            case "gamerule" -> {
                key = "vanilla-command.gamerule";
                arguments = new Object[0];
            }
            case "seed" -> {
                key = "vanilla-command.seed";
                arguments = new Object[0];
            }
            case "stop" -> {
                key = "vanilla-command.stop";
                arguments = new Object[0];
            }
            case "save-all" -> {
                key = "vanilla-command.save-all";
                arguments = new Object[0];
            }
            case "save-off" -> {
                key = "vanilla-command.save-off";
                arguments = new Object[0];
            }
            case "save-on" -> {
                key = "vanilla-command.save-on";
                arguments = new Object[0];
            }
            case "say" -> {
                key = "vanilla-command.say";
                arguments = new Object[0];
            }
            case "me" -> {
                key = "vanilla-command.me";
                arguments = new Object[0];
            }
            case "title" -> {
                key = "vanilla-command.title";
                arguments = new Object[0];
            }
            case "tellraw" -> {
                key = "vanilla-command.tellraw";
                arguments = new Object[0];
            }
            case "summon" -> {
                key = "vanilla-command.summon";
                arguments = new Object[0];
            }
            case "setblock" -> {
                key = "vanilla-command.setblock";
                arguments = new Object[0];
            }
            case "fill" -> {
                key = "vanilla-command.fill";
                arguments = new Object[0];
            }
            case "clone" -> {
                key = "vanilla-command.clone";
                arguments = new Object[0];
            }
            case "particle" -> {
                key = "vanilla-command.particle";
                arguments = new Object[0];
            }
            case "playsound" -> {
                key = "vanilla-command.playsound";
                arguments = new Object[0];
            }
            case "stopsound" -> {
                key = "vanilla-command.stopsound";
                arguments = new Object[0];
            }
            case "worldborder" -> {
                key = "vanilla-command.worldborder";
                arguments = new Object[0];
            }
            case "spawnpoint" -> {
                key = "vanilla-command.spawnpoint";
                arguments = new Object[]{parts.length > 1 ? displayTarget(player, parts[1]) : player.getName()};
            }
            case "setworldspawn" -> {
                key = "vanilla-command.setworldspawn";
                arguments = new Object[0];
            }
            case "attribute" -> {
                key = "vanilla-command.attribute";
                arguments = new Object[0];
            }
            case "damage" -> {
                key = "vanilla-command.damage";
                arguments = new Object[]{parts.length > 1 ? displayTarget(player, parts[1]) : player.getName()};
            }
            case "data" -> {
                key = "vanilla-command.data";
                arguments = new Object[0];
            }
            case "item" -> {
                key = "vanilla-command.item";
                arguments = new Object[0];
            }
            case "tag" -> {
                key = "vanilla-command.tag";
                arguments = new Object[0];
            }
            case "team" -> {
                key = "vanilla-command.team";
                arguments = new Object[0];
            }
            case "bossbar" -> {
                key = "vanilla-command.bossbar";
                arguments = new Object[0];
            }
            case "scoreboard" -> {
                key = "vanilla-command.scoreboard";
                arguments = new Object[0];
            }
            case "schedule" -> {
                key = "vanilla-command.schedule";
                arguments = new Object[0];
            }
            case "spectate" -> {
                key = "vanilla-command.spectate";
                arguments = new Object[0];
            }
            case "ride" -> {
                key = "vanilla-command.ride";
                arguments = new Object[0];
            }
            case "advancement" -> {
                key = "vanilla-command.advancement";
                arguments = new Object[0];
            }
            case "recipe" -> {
                key = "vanilla-command.recipe";
                arguments = new Object[0];
            }
            case "reload" -> {
                key = "vanilla-command.reload";
                arguments = new Object[0];
            }
            default -> {
                key = "vanilla-command.execution-succeeded";
                arguments = new Object[]{commandLine.trim()};
            }
        }
        player.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                + this.lang.message(key, arguments));
    }

    private String teleportDestination(Player player, String[] parts) {
        // /tp <player> reports the named destination, while coordinate forms
        // report the resolved post-teleport location rather than a raw '~'.
        if (parts.length == 2) return displayTarget(player, parts[1]);
        if (parts.length == 3) {
            Player destination = Bukkit.getPlayerExact(parts[2]);
            return destination == null ? displayTarget(player, parts[2]) : destination.getName();
        }

        Player moved = Bukkit.getPlayerExact(parts[1]);
        if (moved == null && (parts[1].equalsIgnoreCase("@s") || parts[1].equalsIgnoreCase("@p"))) {
            moved = player;
        }
        if (moved == null) moved = player;
        org.bukkit.Location location = moved.getLocation();
        return String.format(Locale.ROOT, "%.2f %.2f %.2f", location.getX(), location.getY(), location.getZ());
    }

    /**
     * Executes a vanilla command through Minecraft's command dispatcher and
     * observes its real Brigadier result. Bukkit's dispatchCommand return
     * value only means that a command handler was found, not that it succeeded.
     */
    private boolean executeCommandWithResult(CommandSender sender, String commandLine, Consumer<Boolean> resultConsumer) {
        try {
            Object craftServer = Bukkit.getServer();
            Object minecraftServer = craftServer.getClass().getMethod("getServer").invoke(craftServer);
            Object sourceStack;
            if (sender instanceof Player player) {
                Object handle = player.getClass().getMethod("getHandle").invoke(player);
                sourceStack = handle.getClass().getMethod("createCommandSourceStack").invoke(handle);
            } else {
                sourceStack = minecraftServer.getClass().getMethod("createCommandSourceStack").invoke(minecraftServer);
            }
            // For administration commands (op, deop, ban, etc.), we emit clean custom feedback
            // and sync across servers. Suppress vanilla output for those only.
            String root = commandLine.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (root.startsWith("minecraft:")) root = root.substring("minecraft:".length());
            if (isAdministrationCommand(root)) {
                sourceStack = suppressVanillaOutput(sourceStack);
            }
            Class<?> callbackType = Class.forName("net.minecraft.commands.CommandResultCallback");
            Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
                    (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "onResult" -> {
                                if (args != null && args.length >= 1) resultConsumer.accept(Boolean.TRUE.equals(args[0]));
                                yield null;
                            }
                            case "toString" -> "FreeCoreEssentialsCommandResultCallback";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                            default -> null;
                        };
                    });
            Object callbackStack = sourceStack.getClass().getMethod("withCallback", callbackType)
                    .invoke(sourceStack, callback);
            Object commands = minecraftServer.getClass().getMethod("getCommands").invoke(minecraftServer);
            commands.getClass().getMethod("performPrefixedCommand", callbackStack.getClass(), String.class)
                    .invoke(commands, callbackStack, commandLine.startsWith("/") ? commandLine.substring(1) : commandLine);
            return true;
        } catch (ReflectiveOperationException | SecurityException | IllegalArgumentException exception) {
            plugin.getLogger().warning("Could not observe vanilla command result for /" + commandLine
                    + ": " + exception.getClass().getSimpleName() + " - " + exception.getMessage());
            return false;
        }
    }

    private Object suppressVanillaOutput(Object sourceStack) throws ReflectiveOperationException {
        Class<?> type = sourceStack.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Method silent = type.getDeclaredMethod("withSuppressedOutput");
                silent.setAccessible(true);
                Object suppressed = silent.invoke(sourceStack);
                return suppressed == null ? sourceStack : suppressed;
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        // Some mappings expose the method only through an interface or public
        // inherited method, so keep a final public lookup as a compatibility path.
        try {
            java.lang.reflect.Method silent = sourceStack.getClass().getMethod("withSuppressedOutput");
            Object suppressed = silent.invoke(sourceStack);
            return suppressed == null ? sourceStack : suppressed;
        } catch (NoSuchMethodException ignored) {
            throw new ReflectiveOperationException("Command source cannot suppress vanilla output");
        }
    }

    private String displayTarget(Player player, String target) {
        return target.equalsIgnoreCase("@s") || target.equalsIgnoreCase("@p") ? player.getName() : target;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onServerCommand(ServerCommandEvent event) {
        if (applyingRemoteAdministration) return;
        String raw = event.getCommand().trim();
        if (raw.startsWith("/")) raw = raw.substring(1).trim();
        if (raw.isEmpty()) return;
        String[] parts = raw.split("\\s+");
        String root = parts[0].toLowerCase(Locale.ROOT);
        if (root.startsWith("minecraft:")) root = root.substring("minecraft:".length());

        if (isAdministrationCommand(root)) {
            publishPeerAdministration(raw);
            return;
        }

        // Support console cross-server commands like "give <cross-server-player> diamond"
        if (PLAYER_COMMANDS.contains(root)) {
            String target = playerArgument(root, parts);
            if (target != null && !target.startsWith("@")) {
                Player localPlayer = Bukkit.getPlayerExact(target);
                if (localPlayer == null || !localPlayer.isOnline()) {
                    String targetServer = redis("GET", "fce:online:name:" + target.toLowerCase(Locale.ROOT));
                    if (targetServer != null && !targetServer.equals(serverId())) {
                        event.setCancelled(true);
                        publish(targetServer, "CMD|" + b64(raw) + "|CONSOLE");
                        Bukkit.getConsoleSender().sendMessage(ChatColor.GREEN + "[FreeCore] 已将控制台指令 [" + raw + "] 派发至目标子服 (" + targetServer + ") 针对玩家 " + target + " 执行。");
                    }
                }
            }
        }
    }

    private boolean isAdministrationCommand(String root) {
        return Set.of("op", "deop", "ban", "pardon", "ban-ip", "pardon-ip", "gamerule", "difficulty", "weather", "time", "defaultgamemode", "whitelist").contains(root);
    }

    private void publishPeerAdministration(String command) {
        String target = "survival".equals(serverId()) ? "technical" : "survival";
        publish(target, "ADMIN|" + b64(command));
    }

    private String playerArgument(String root, String[] parts) {
        int index = switch (root) {
            case "give", "tp", "teleport", "effect", "clear", "enchant", "attribute", "damage", "kill", "title", "tell", "msg", "w", "whisper", "spectate", "ride", "kick", "ban", "pardon", "op", "deop", "tellraw", "spawnpoint", "tag" -> 1;
            case "gamemode" -> 2;
            case "experience", "xp" -> parts.length > 1 && (parts[1].equalsIgnoreCase("add") || parts[1].equalsIgnoreCase("set")) ? 2 : 1;
            case "data" -> parts.length > 2 && parts[1].equalsIgnoreCase("entity") ? 2 : -1;
            case "item" -> parts.length > 3 && parts[1].equalsIgnoreCase("replace") && parts[2].equalsIgnoreCase("entity") ? 3 : -1;
            case "team" -> parts.length > 2 && parts[1].equalsIgnoreCase("leave") ? 2 : (parts.length > 3 && parts[1].equalsIgnoreCase("join") ? 3 : -1);
            case "whitelist" -> parts.length > 2 && (parts[1].equalsIgnoreCase("add") || parts[1].equalsIgnoreCase("remove")) ? 2 : -1;
            case "scoreboard" -> parts.length > 3 && parts[1].equalsIgnoreCase("players") ? 3 : -1;
            case "playsound" -> parts.length > 3 ? 3 : -1;
            case "stopsound" -> parts.length > 2 ? 2 : -1;
            case "advancement", "recipe" -> parts.length > 2 ? 2 : -1;
            default -> -1;
        };
        return index >= 0 && index < parts.length ? parts[index] : null;
    }

    @Override public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (!(sender instanceof Player viewer)) { sender.sendMessage("Only players can use invsee."); return true; }
        if (!viewer.hasPermission("freecoreessentials.invsee")) { viewer.sendMessage(ChatColor.RED + "你没有权限使用 invsee。"); return true; }
        if (args.length != 1) { viewer.sendMessage(ChatColor.YELLOW + "用法: /invsee <玩家>"); return true; }
        if (Bukkit.getPlayerExact(args[0]) == null) {
            loadInventoryByName(viewer, args[0]);
            return true;
        }
        UUID target = resolveUuid(args[0]);
        if (target == null) { viewer.sendMessage(ChatColor.RED + "无法定位该玩家。"); return true; }
        loadInventory(viewer, target, args[0]);
        return true;
    }

    private UUID resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        String value = redis("GET", "fce:online:name:" + name.toLowerCase() + ":uuid");
        try { return value == null ? null : UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; }
    }

    private void loadInventoryByName(Player viewer, String name) {
        BukkitHuskSyncAPI api = BukkitHuskSyncAPI.getInstance();
        if (api == null) {
            viewer.sendMessage(ChatColor.RED + "HuskSync unavailable.");
            return;
        }
        api.getUser(name).whenComplete((user, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || user == null || user.isEmpty()) {
                viewer.sendMessage(ChatColor.RED + "Player not found in HuskSync database.");
                return;
            }
            loadInventory(viewer, user.get().getUuid(), user.get().getUsername());
        }));
    }

    private void loadInventory(Player viewer, UUID target, String name) {
        BukkitHuskSyncAPI api = BukkitHuskSyncAPI.getInstance();
        if (api == null) { viewer.sendMessage(ChatColor.RED + "HuskSync 尚未就绪。"); return; }
        api.getUser(target).thenCompose(optional -> optional
                .<CompletableFuture<java.util.Optional<ItemStack[]>>>map(api::getCurrentInventoryContents)
                .orElseGet(() -> CompletableFuture.completedFuture(java.util.Optional.empty())))
                .whenComplete((contents, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null || contents == null || contents.isEmpty()) {
                        viewer.sendMessage(ChatColor.RED + "数据库中没有该玩家的背包数据。");
                        return;
                    }
                    Inventory inventory = Bukkit.createInventory(null, 54, ChatColor.DARK_GREEN + "InvSee: " + name);
                    ItemStack[] items = contents.get();
                    for (int i = 0; i < Math.min(items.length, inventory.getSize()); i++) inventory.setItem(i, items[i]);
                    sessions.put(viewer.getUniqueId(), new Session(target, name, inventory));
                    storeInvSeeState(target, snapshot(inventory), 600);
                    viewer.openInventory(inventory);
                }));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        Session session = sessionFor(viewer, event.getView().getTopInventory());
        if (session == null) {
            Bukkit.getScheduler().runTask(plugin, () -> publishLiveInventory(viewer));
            return;
        }
        // Bukkit applies the click after this event. Snapshot on the next tick so
        // normal pickup, placement, shift-click, hotbar and drop actions are saved.
        scheduleSessionInventoryPersistence(viewer, session, event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void protectInvSeeBounds(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        Session session = sessionFor(viewer, event.getView().getTopInventory());
        if (session == null) return;
        int slot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        if (slot >= 41 && slot < topSize) {
            event.setCancelled(true);
            return;
        }
        if (slot >= topSize && event.isShiftClick() && event.getCurrentItem() != null) {
            ItemStack source = event.getCurrentItem().clone();
            ItemStack remaining = moveIntoTarget(session.inventory, source);
            if (remaining.getAmount() != source.getAmount()) {
                event.setCancelled(true);
                event.setCurrentItem(remaining.getAmount() == 0 ? null : remaining);
                scheduleSessionInventoryPersistence(viewer, session, event.getView().getTopInventory());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        Session session = sessionFor(viewer, event.getView().getTopInventory());
        if (session == null) {
            Bukkit.getScheduler().runTask(plugin, () -> publishLiveInventory(viewer));
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot >= 41 && slot < topSize)) {
            event.setCancelled(true);
            return;
        }
        if (event.getRawSlots().stream().anyMatch(slot -> slot < 41)) {
            scheduleSessionInventoryPersistence(viewer, session, event.getView().getTopInventory());
        }
    }

    private ItemStack moveIntoTarget(Inventory inventory, ItemStack source) {
        ItemStack remaining = source.clone();
        for (int i = 0; i < 41 && remaining.getAmount() > 0; i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing == null || !existing.isSimilar(remaining)) continue;
            int moved = Math.min(existing.getMaxStackSize() - existing.getAmount(), remaining.getAmount());
            if (moved <= 0) continue;
            existing.setAmount(existing.getAmount() + moved);
            remaining.setAmount(remaining.getAmount() - moved);
        }
        for (int i = 0; i < 41 && remaining.getAmount() > 0; i++) {
            if (inventory.getItem(i) != null) continue;
            int moved = Math.min(remaining.getMaxStackSize(), remaining.getAmount());
            ItemStack placed = remaining.clone();
            placed.setAmount(moved);
            inventory.setItem(i, placed);
            remaining.setAmount(remaining.getAmount() - moved);
        }
        return remaining;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player viewer)) return;
        Session session = sessionFor(viewer, event.getView().getTopInventory());
        if (session == null) return;
        sessions.remove(viewer.getUniqueId(), session);
        persistInventory(session, event.getView().getTopInventory());
    }

    private Session sessionFor(Player viewer, Inventory inventory) {
        Session session = sessions.get(viewer.getUniqueId());
        return session != null && session.inventory == inventory ? session : null;
    }

    private void scheduleSessionInventoryPersistence(Player viewer, Session session, Inventory inventory) {
        BukkitTask previous = pendingInventoryTasks.put(viewer.getUniqueId(),
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    pendingInventoryTasks.remove(viewer.getUniqueId());
                    if (viewer.isOnline() && sessionFor(viewer, inventory) != null) persistInventory(session, inventory);
                }, 1L));
        if (previous != null) previous.cancel();
    }

    private void persistInventory(Session session, Inventory inventory) {
        ItemStack[] items = snapshot(inventory);
        Player local = Bukkit.getPlayer(session.target);
        if (local != null) applyInventory(local, items);
        queueInventoryWrite(session.target, items, true);
    }

    private void queueInventoryWrite(UUID target, ItemStack[] items, boolean authoritative) {
        long version = inventoryVersions.merge(target, 1L, Long::sum);
        ItemStack[] snapshot = cloneItems(items);
        inventoryWrites.execute(() -> {
            if (!isLatestInventoryVersion(target, version)) return;
            BukkitHuskSyncAPI api = BukkitHuskSyncAPI.getInstance();
            if (authoritative && api != null) {
                try {
                    api.getUser(target).join().ifPresent(user -> api.setCurrentInventoryContents(user, cloneItems(snapshot)));
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING, "Unable to persist inventory for " + target, exception);
                    return;
                }
            }
            if (!isLatestInventoryVersion(target, version)) return;
            String encoded = encodeInventory(snapshot);
            if (encoded == null) return;
            redis("SET", INVSEE_STATE_PREFIX + target, encoded, "EX", "600");
            publishInventoryNow(authoritative ? "INV" : "LIVE", target, encoded);
        });
    }

    private boolean isLatestInventoryVersion(UUID target, long version) {
        return version == inventoryVersions.getOrDefault(target, 0L);
    }

    private void publishInventoryNow(String action, UUID target, String encoded) {
        String payload = action + "|" + target + "|" + encoded;
        if (!"survival".equals(serverId())) publish("survival", payload);
        if (!"technical".equals(serverId())) publish("technical", payload);
    }

    private ItemStack[] snapshot(Inventory inventory) {
        ItemStack[] items = new ItemStack[41];
        ItemStack[] current = inventory.getContents();
        System.arraycopy(current, 0, items, 0, Math.min(items.length, current.length));
        return cloneItems(items);
    }

    private ItemStack[] snapshot(Player player) {
        ItemStack[] items = new ItemStack[41];
        System.arraycopy(player.getInventory().getStorageContents(), 0, items, 0, 36);
        System.arraycopy(player.getInventory().getArmorContents(), 0, items, 36, 4);
        items[40] = player.getInventory().getItemInOffHand();
        return cloneItems(items);
    }

    private void publishLiveInventory(Player player) {
        if (!player.isOnline()) return;
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir() && cursor.getAmount() > 0) {
            scheduleLiveInventoryPublish(player);
            return;
        }
        ItemStack[] items = snapshot(player);
        updateViewerSessions(player.getUniqueId(), items);
        queueInventoryWrite(player.getUniqueId(), items, false);
    }

    private void scheduleLiveInventoryPublish(Player player) {
        UUID uuid = player.getUniqueId();
        BukkitTask previous = pendingLiveInventoryTasks.put(uuid,
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    pendingLiveInventoryTasks.remove(uuid);
                    publishLiveInventory(player);
                }, 1L));
        if (previous != null) previous.cancel();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        scheduleLiveInventoryPublish(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        scheduleLiveInventoryPublish(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldItem(PlayerItemHeldEvent event) {
        scheduleLiveInventoryPublish(event.getPlayer());
    }

    private void updateViewerSessions(UUID target, ItemStack[] items) {
        sessions.forEach((viewerId, session) -> {
            if (!session.target.equals(target)) return;
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || viewer.getOpenInventory().getTopInventory() != session.inventory) return;
            for (int i = 0; i < 41; i++) session.inventory.setItem(i, items[i] == null ? null : items[i].clone());
            viewer.updateInventory();
        });
    }

    private void publishInventory(String action, UUID target, ItemStack[] items) {
        String encoded = encodeInventory(items);
        if (encoded == null) return;
        String payload = action + "|" + target + "|" + encoded;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Publish only after the authoritative snapshot is retained. This
            // ordering prevents a joining backend from reading an older state
            // between the Redis notification and the database write.
            if (action.equals("INV")) redis("SET", INVSEE_STATE_PREFIX + target, encoded, "EX", "600");
            if (!"survival".equals(serverId())) publish("survival", payload);
            if (!"technical".equals(serverId())) publish("technical", payload);
        });
    }

    private void storeInvSeeState(UUID target, ItemStack[] items, int seconds) {
        String encoded = encodeInventory(items);
        if (encoded != null) Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> redis("SET", INVSEE_STATE_PREFIX + target, encoded, "EX", String.valueOf(seconds)));
    }

    private void refreshInventoryOnJoin(UUID target) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String encoded = redis("GET", INVSEE_STATE_PREFIX + target);
            if (encoded == null) return;
            ItemStack[] items = decodeInventory(encoded);
            if (items == null) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(target);
                if (player != null && player.isOnline()) {
                    applyInventory(player, items);
                    player.updateInventory();
                    persistDatabase(target, items);
                }
            });
        });
    }

    private void persistDatabase(UUID target, ItemStack[] items) {
        BukkitHuskSyncAPI api = BukkitHuskSyncAPI.getInstance();
        if (api != null) api.getUser(target).thenAccept(optional ->
                optional.ifPresent(user -> api.setCurrentInventoryContents(user, cloneItems(items))));
    }

    private void refreshLiveInventory(UUID target) {
        Player local = Bukkit.getPlayer(target);
        BukkitHuskSyncAPI api = BukkitHuskSyncAPI.getInstance();
        if (local == null || api == null) return;
        api.getUser(target).thenCompose(optional -> optional
                .<CompletableFuture<java.util.Optional<ItemStack[]>>>map(api::getCurrentInventoryContents)
                .orElseGet(() -> CompletableFuture.completedFuture(java.util.Optional.empty())))
                .thenAccept(contents -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (local.isOnline() && contents.isPresent()) applyInventory(local, contents.get());
                }));
    }

    private void applyInventory(Player player, ItemStack[] items) {
        player.getInventory().setStorageContents(java.util.Arrays.copyOf(items, 36));
        player.getInventory().setArmorContents(java.util.Arrays.copyOfRange(items, 36, 40));
        player.getInventory().setItemInOffHand(items.length > 40 ? items[40] : null);
    }

    private String encodeInventory(ItemStack[] items) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
            output.writeInt(items.length);
            for (ItemStack item : items) output.writeObject(item);
            output.flush();
            return Base64.getUrlEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Unable to encode InvSee inventory", ex);
            return null;
        }
    }

    private ItemStack[] decodeInventory(String encoded) {
        try (BukkitObjectInputStream input = new BukkitObjectInputStream(
                new ByteArrayInputStream(Base64.getUrlDecoder().decode(encoded)))) {
            int length = input.readInt();
            if (length != 41) return null;
            ItemStack[] items = new ItemStack[length];
            for (int i = 0; i < length; i++) items[i] = (ItemStack) input.readObject();
            return items;
        } catch (IOException | ClassNotFoundException | IllegalArgumentException ex) {
            plugin.getLogger().log(Level.WARNING, "Unable to decode InvSee inventory", ex);
            return null;
        }
    }

    private ItemStack[] cloneItems(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) copy[i] = items[i] == null ? null : items[i].clone();
        return copy;
    }

    private void queueAndConnect(Player player, String targetServer, String command) {
        String key = TICKET_PREFIX + targetServer.toLowerCase(Locale.ROOT) + ":" + player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (redis("SET", key, b64(command), "EX", "120") == null) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(this.lang.message("prefix.freecoreessentials")
                        + ChatColor.RED + "跨服传送票据写入失败，请重试。"));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
                    out.writeUTF("Connect"); out.writeUTF(targetServer); player.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
                    player.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                            + this.lang.message("vanilla-command.teleport-switching", targetServer));
                } catch (IOException ex) {
                    player.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.RED
                            + this.lang.message("vanilla-command.teleport-switch-failed"));
                }
            });
        });
    }

    private void handleTpaCommand(Player player, String root, String[] parts) {
        if (!player.hasPermission("freecoreessentials.tpa")) {
            player.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.RED + "你没有使用 TPA 的权限。");
            return;
        }
        if (root.equals("tpa") || root.equals("tphere")) {
            if (parts.length != 2 || parts[1].isBlank()) {
                player.sendMessage(ChatColor.YELLOW + "用法: /" + root + " <玩家>");
                return;
            }
            requestTeleport(player, parts[1], root.equals("tphere"));
            return;
        }
        if (parts.length > 2) {
            player.sendMessage(ChatColor.YELLOW + "用法: /" + root + " [玩家]");
            return;
        }
        respondToTeleportRequest(player, root.equals("tpaccept"), parts.length == 2 ? parts[1] : null);
    }

    private void requestTeleport(Player requester, String targetName, boolean isHere) {
        Player localTarget = Bukkit.getPlayerExact(targetName);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String targetServer = localTarget == null ? redis("GET", "fce:online:name:" + targetName.toLowerCase(Locale.ROOT)) : serverId();
            String targetUuidValue = localTarget == null ? redis("GET", "fce:online:name:" + targetName.toLowerCase(Locale.ROOT) + ":uuid") : localTarget.getUniqueId().toString();
            UUID targetId;
            try { targetId = targetUuidValue == null ? null : UUID.fromString(targetUuidValue); }
            catch (IllegalArgumentException ignored) { targetId = null; }
            if (targetServer == null || targetId == null || !targetServer.equals("survival") && !targetServer.equals("technical")) {
                Bukkit.getScheduler().runTask(plugin, () -> requester.sendMessage(ChatColor.RED + "玩家不在线，传送请求失败。"));
                return;
            }
            if (targetId.equals(requester.getUniqueId())) {
                Bukkit.getScheduler().runTask(plugin, () -> requester.sendMessage(ChatColor.RED + "不能向自己发送传送请求。"));
                return;
            }
            String requestId = UUID.randomUUID().toString();
            TpaRequest request = new TpaRequest(requestId, requester.getUniqueId(), requester.getName(), serverId(), targetId,
                    targetName, targetServer, isHere);
            String targetKey = TPA_TARGET_PREFIX + targetId;
            if (redis("SET", targetKey, requestId, "NX", "EX", "60") == null
                    || redis("SET", TPA_REQUEST_PREFIX + requestId, serializeTpa(request), "EX", "60") == null) {
                redis("DEL", targetKey);
                Bukkit.getScheduler().runTask(plugin, () -> requester.sendMessage(ChatColor.YELLOW + "该玩家已有待处理的传送请求。"));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> requester.sendMessage(ChatColor.GREEN + "已向 " + targetName + " 发送"
                    + (isHere ? "召唤传送(tphere)" : "传送(tpa)") + "请求，有效期 60 秒。"));
            if (targetServer.equals(serverId())) {
                Player target = Bukkit.getPlayer(targetId);
                if (target == null || !target.isOnline()) {
                    redis("DEL", targetKey, TPA_REQUEST_PREFIX + requestId);
                    Bukkit.getScheduler().runTask(plugin, () -> requester.sendMessage(ChatColor.RED + "玩家不在线，传送请求失败。"));
                    return;
                }
                pendingTpa.put(targetId, request);
                notifyTpaTarget(target, request);
            } else {
                publish(targetServer, "TPA_REQUEST|" + requestId + "|" + targetId + "|" + request.requesterId
                        + "|" + b64(request.requesterName) + "|" + request.requesterServer + "|" + b64(targetName) + "|" + (isHere ? "1" : "0"));
            }
        });
    }

    private void notifyTpaTarget(Player target, TpaRequest request) {
        if (request.isHere) {
            target.sendMessage(ChatColor.YELLOW + "玩家 " + request.requesterName + " 请求你传送到他那里。请输入 "
                    + ChatColor.WHITE + "/tpaccept " + request.requesterName + ChatColor.YELLOW + " 同意，或 "
                    + ChatColor.WHITE + "/tpdeny " + request.requesterName + ChatColor.YELLOW + " 拒绝（60秒内有效）。");
        } else {
            target.sendMessage(ChatColor.YELLOW + "玩家 " + request.requesterName + " 请求传送到你这里。请输入 "
                    + ChatColor.WHITE + "/tpaccept " + request.requesterName + ChatColor.YELLOW + " 同意，或 "
                    + ChatColor.WHITE + "/tpdeny " + request.requesterName + ChatColor.YELLOW + " 拒绝（60秒内有效）。");
        }
    }

    private void respondToTeleportRequest(Player target, boolean accepted, String requesterName) {
        TpaRequest request = pendingTpa.get(target.getUniqueId());
        if (request == null || (requesterName != null && !request.requesterName.equalsIgnoreCase(requesterName))) {
            target.sendMessage(ChatColor.RED + "没有匹配的待处理传送请求。" );
            return;
        }
        pendingTpa.remove(target.getUniqueId(), request);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (redis("EXISTS", TPA_REQUEST_PREFIX + request.id) == null || "0".equals(redis("EXISTS", TPA_REQUEST_PREFIX + request.id))) {
                redis("DEL", TPA_TARGET_PREFIX + target.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> target.sendMessage(ChatColor.RED + "该传送请求已过期。"));
                return;
            }
            redis("DEL", TPA_TARGET_PREFIX + target.getUniqueId(), TPA_REQUEST_PREFIX + request.id);
            String requesterServer = redis("GET", "fce:online:uuid:" + request.requesterId);
            if (requesterServer == null) {
                Bukkit.getScheduler().runTask(plugin, () -> target.sendMessage(ChatColor.RED + "请求方已下线，传送失败。"));
                return;
            }
            TpaRequest current = new TpaRequest(request.id, request.requesterId, request.requesterName, requesterServer,
                    request.targetId, request.targetName, serverId(), request.isHere);
            String action = accepted ? "TPA_ACCEPT" : "TPA_DENY";

            // If it's a normal /tpa: requester moves to target!
            // If it's a /tphere: target moves to requester!
            if (!current.isHere) {
                // Normal TPA: requester teleports to target
                if (requesterServer.equals(serverId())) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player requester = Bukkit.getPlayer(current.requesterId);
                        if (requester == null || !requester.isOnline()) {
                            target.sendMessage(ChatColor.RED + "请求方已下线，传送失败。");
                            return;
                        }
                        if (!accepted) {
                            requester.sendMessage(ChatColor.YELLOW + target.getName() + " 拒绝了你的传送请求。" );
                            target.sendMessage(ChatColor.GREEN + "已拒绝 " + requester.getName() + " 的传送请求。" );
                            return;
                        }
                        target.sendMessage(ChatColor.GREEN + "已同意 " + requester.getName() + " 的传送请求。" );
                        completeTpa(requester, current);
                    });
                } else {
                    publish(requesterServer, action + "|" + current.id + "|" + current.requesterId + "|"
                            + b64(current.requesterName) + "|" + current.targetId + "|" + b64(current.targetName) + "|" + current.targetServer + "|0");
                    Bukkit.getScheduler().runTask(plugin, () -> target.sendMessage(accepted
                            ? ChatColor.GREEN + "已同意 " + current.requesterName + " 的传送请求。"
                            : ChatColor.GREEN + "已拒绝 " + current.requesterName + " 的传送请求。"));
                }
            } else {
                // TPHERE: target teleports to requester
                if (!accepted) {
                    if (requesterServer.equals(serverId())) {
                        Player requester = Bukkit.getPlayer(current.requesterId);
                        if (requester != null && requester.isOnline()) {
                            requester.sendMessage(ChatColor.YELLOW + target.getName() + " 拒绝了你的召唤传送请求。");
                        }
                    } else {
                        publish(requesterServer, "TPA_DENY|" + current.id + "|" + current.requesterId + "|"
                                + b64(current.requesterName) + "|" + current.targetId + "|" + b64(current.targetName) + "|" + current.targetServer + "|1");
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> target.sendMessage(ChatColor.GREEN + "已拒绝 " + current.requesterName + " 的召唤传送请求。"));
                    return;
                }

                // Accepted TPHERE: target moves to requester!
                Bukkit.getScheduler().runTask(plugin, () -> {
                    target.sendMessage(ChatColor.GREEN + "已同意传送至 " + current.requesterName + " 身边。");
                    if (requesterServer.equals(serverId())) {
                        Player requester = Bukkit.getPlayer(current.requesterId);
                        if (requester == null || !requester.isOnline()) {
                            target.sendMessage(ChatColor.RED + "请求方已下线，传送失败。");
                            return;
                        }
                        requester.sendMessage(ChatColor.GREEN + target.getName() + " 已同意你的召唤请求，正在传送到你身边。");
                        scheduleDirectTeleportRetry(target, requester, 0);
                    } else {
                        // Cross-server: target switches to requesterServer!
                        publish(requesterServer, "TPA_ACCEPT|" + current.id + "|" + current.requesterId + "|"
                                + b64(current.requesterName) + "|" + current.targetId + "|" + b64(current.targetName) + "|" + current.targetServer + "|1");
                        String ticketKey = TPA_TICKET_PREFIX + target.getUniqueId();
                        String ticket = current.requesterId + "|" + b64(current.requesterName) + "|" + requesterServer + "|" + current.id;
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                            if (redis("SET", ticketKey, ticket, "EX", "120") == null) {
                                Bukkit.getScheduler().runTask(plugin, () -> target.sendMessage(ChatColor.RED + "跨服传送票据写入失败，请重试。"));
                                return;
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> connectForTpa(target, requesterServer));
                        });
                    }
                });
            }
        });
    }

    private String serializeTpa(TpaRequest request) {
        return request.requesterId + "|" + b64(request.requesterName) + "|" + request.requesterServer + "|"
                + request.targetId + "|" + b64(request.targetName) + "|" + request.targetServer + "|" + (request.isHere ? "1" : "0");
    }

    private void handleRemoteTpa(String[] payload) {
        if (payload[1].equals("TPA_REQUEST") && payload.length >= 8) {
            try {
                String requestId = payload[2];
                UUID targetId = UUID.fromString(payload[3]);
                UUID requesterId = UUID.fromString(payload[4]);
                boolean isHere = payload.length >= 9 && "1".equals(payload[8]);
                Player target = Bukkit.getPlayer(targetId);
                if (target == null || !target.isOnline()) return;
                TpaRequest request = new TpaRequest(requestId, requesterId, decode(payload[5]), payload[6], targetId,
                        decode(payload[7]), serverId(), isHere);
                pendingTpa.put(targetId, request);
                notifyTpaTarget(target, request);
            } catch (IllegalArgumentException ignored) { }
            return;
        }
        if (payload.length < 8 || (!payload[1].equals("TPA_ACCEPT") && !payload[1].equals("TPA_DENY"))) return;
        try {
            String requestId = payload[2];
            UUID requesterId = UUID.fromString(payload[3]);
            UUID targetId = UUID.fromString(payload[5]);
            boolean isHere = payload.length >= 9 && "1".equals(payload[8]);
            TpaRequest request = new TpaRequest(requestId, requesterId, decode(payload[4]), serverId(), targetId,
                    decode(payload[6]), payload[7], isHere);
            Player requester = Bukkit.getPlayer(requesterId);
            if (requester == null || !requester.isOnline()) return;
            if (payload[1].equals("TPA_DENY")) {
                requester.sendMessage(ChatColor.YELLOW + request.targetName + " 拒绝了你的" + (isHere ? "召唤" : "") + "传送请求。" );
                return;
            }
            if (isHere) {
                requester.sendMessage(ChatColor.GREEN + request.targetName + " 已同意召唤传送，正在前往此服务器..." );
            } else {
                completeTpa(requester, request);
            }
        } catch (IllegalArgumentException ignored) { }
    }

    private void completeTpa(Player requester, TpaRequest request) {
        if (!request.targetServer.equals(serverId())) {
            String ticketKey = TPA_TICKET_PREFIX + requester.getUniqueId();
            String ticket = request.targetId + "|" + b64(request.targetName) + "|" + request.targetServer + "|" + request.id;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                if (redis("SET", ticketKey, ticket, "EX", "120") == null) {
                    plugin.getLogger().warning("Could not store cross-server TPA ticket for " + requester.getName()
                            + " (target server: " + request.targetServer + ")");
                    Bukkit.getScheduler().runTask(plugin, () -> requester.sendMessage(ChatColor.RED + "跨服传送票据写入失败，请重试。"));
                    return;
                }
                plugin.getLogger().info("Accepted cross-server TPA " + request.id + ": " + requester.getName()
                        + " -> " + request.targetName + ", connecting to " + request.targetServer);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    connectForTpa(requester, request.targetServer);
                    requester.sendMessage(ChatColor.GREEN + "对方已同意，正在前往 " + request.targetServer + "。" );
                });
            });
            return;
        }
        Player target = Bukkit.getPlayer(request.targetId);
        if (target == null || !target.isOnline()) {
            requester.sendMessage(ChatColor.RED + "目标玩家已下线，传送失败。" );
            return;
        }
        if (requester.teleport(target.getLocation())) {
            requester.sendMessage(ChatColor.GREEN + "已传送到 " + target.getName() + " 身边。" );
        } else {
            requester.sendMessage(ChatColor.RED + "传送失败。" );
        }
    }

    private void connectForTpa(Player player, String targetServer) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Connect"); out.writeUTF(targetServer); player.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
        } catch (IOException exception) {
            player.sendMessage(ChatColor.RED + "无法切换到目标子服，传送失败。" );
        }
    }

    private void pollTpaTicket(Player player, int attempt) {
        if (!player.isOnline()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String key = TPA_TICKET_PREFIX + player.getUniqueId();
            String ticket = redis("GET", key);
            if (ticket == null) {
                if (attempt < 120) Bukkit.getScheduler().runTaskLater(plugin, () -> pollTpaTicket(player, attempt + 1), 5L);
                else plugin.getLogger().warning("No cross-server TPA ticket found after join for " + player.getName());
                return;
            }
            plugin.getLogger().info("Found cross-server TPA ticket after connect for " + player.getName());
            Bukkit.getScheduler().runTaskLater(plugin, () -> completeTpaAfterJoin(player, key, ticket, 0), 20L);
        });
    }

    private void deleteTpaTicketAsync(String ticketKey) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> redis("DEL", ticketKey));
    }

    private void completeTpaAfterJoin(Player requester, String ticketKey, String ticket, int attempt) {
        if (!requester.isOnline()) return;
        String[] fields = ticket.split("\\|", 4);
        if (fields.length < 3) {
            deleteTpaTicketAsync(ticketKey);
            requester.sendMessage(ChatColor.RED + "传送票据无效，传送失败。" );
            return;
        }
        try {
            UUID targetId = UUID.fromString(fields[0]);
            Player target = Bukkit.getPlayer(targetId);
            if (target == null && fields.length >= 2) {
                target = findOnlinePlayer(decode(fields[1]));
            }
            if (target == null || !target.isOnline()) {
                if (attempt < 120) {
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> completeTpaAfterJoin(requester, ticketKey, ticket, attempt + 1), 5L);
                } else {
                    deleteTpaTicketAsync(ticketKey);
                    plugin.getLogger().warning("Cross-server TPA target was not online after connect for "
                            + requester.getName() + ": " + fields[1]);
                    requester.sendMessage(ChatColor.RED + "目标玩家尚未进入当前子服，位置传送失败，请重试。" );
                }
                return;
            }
            deleteTpaTicketAsync(ticketKey);
            scheduleDirectTeleportRetry(requester, target, 0);

        } catch (IllegalArgumentException ignored) {
            deleteTpaTicketAsync(ticketKey);
            requester.sendMessage(ChatColor.RED + "传送票据无效，传送失败。" );
        }
    }

    private void scheduleFinalTpaTeleport(Player requester, Player target, String ticketKey,
                                            Location destination, int attempt) {
        if (!requester.isOnline() || !target.isOnline()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!requester.isOnline() || !target.isOnline()) return;
            Location current = target.getLocation().clone();
            boolean moved = requester.teleport(current);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                boolean verified = requester.isOnline() && requester.getWorld().equals(current.getWorld())
                        && requester.getLocation().distanceSquared(current) <= 4.0D;
                plugin.getLogger().info("Cross-server TPA final teleport "
                        + (verified ? "verified" : (moved ? "overridden" : "failed"))
                        + " for " + requester.getName() + " to " + target.getName());
                if (verified) {
                    deleteTpaTicketAsync(ticketKey);
                    requester.sendMessage(ChatColor.GREEN + "已传送到 " + target.getName() + " 身边。" );
                } else if (attempt < 12) {
                    scheduleFinalTpaTeleport(requester, target, ticketKey, current, attempt + 1);
                } else {
                    deleteTpaTicketAsync(ticketKey);
                    requester.sendMessage(ChatColor.RED + "目标子服已进入，但最终位置传送失败，请重试。" );
                }
            }, 2L);
        }, attempt == 0 ? 10L : 5L);
    }

    private void handle(String message) {
        String[] p = message.split("\\|", 12);
        if (p.length < 2) return;
        if (p[0].equals("all") || serverId().equals(p[0])) {
            if (p[1].equals("BC") && p.length >= 3) {
                String bcMsg = decode(p[2]);
                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcastMessage(bcMsg));
                return;
            }
            if (p[1].equals("MAINT_KICK") && p.length >= 3) {
                String targetServer = p[2];
                kickNonOpsForMaintenance(targetServer);
                return;
            }
        }
        if (!serverId().equals(p[0])) return;
        if (p[1].equals("SUDO") && p.length >= 5) {
            String targetName = decode(p[2]);
            String action = decode(p[3]);
            Player target = Bukkit.getPlayerExact(targetName);
            if (target != null && target.isOnline()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    new io.github.freecoreessentials.command.SudoCommand(this, lang).executeLocalSudo(null, target, action);
                });
            }
            return;
        }
        if (p[1].equals("MSG") && p.length >= 5) {
            String targetName = decode(p[2]);
            String senderDisplayName = decode(p[3]);
            String messageContent = decode(p[4]);
            Player target = Bukkit.getPlayerExact(targetName);
            if (target != null && target.isOnline()) {
                String formatReceiver = ChatColor.translateAlternateColorCodes('&',
                        this.lang.message("private-message.format-receiver", senderDisplayName, messageContent));
                Bukkit.getScheduler().runTask(plugin, () -> target.sendMessage(formatReceiver));
            }
            return;
        }
        if (p[1].equals("NOTIFY_MUTE") && p.length >= 4) {
            String targetName = decode(p[2]);
            String blockedMsg = decode(p[3]);
            Player target = Bukkit.getPlayerExact(targetName);
            if (target != null && target.isOnline()) {
                Bukkit.getScheduler().runTask(plugin, () -> target.sendMessage(blockedMsg));
            }
            return;
        }
        if (p[1].equals("NOTIFY_UNMUTE") && p.length >= 3) {
            String targetName = decode(p[2]);
            Player target = Bukkit.getPlayerExact(targetName);
            if (target != null && target.isOnline()) {
                Bukkit.getScheduler().runTask(plugin, () -> target.sendMessage(ChatColor.GREEN + "你的禁言已被管理员解除，请文明发言。"));
            }
            return;
        }
        if (p[1].equals("REMOTE_GM") && p.length >= 5) {
            String targetName = decode(p[2]);
            String modeStr = p[3];
            Player target = Bukkit.getPlayerExact(targetName);
            if (target != null && target.isOnline()) {
                try {
                    GameMode mode = GameMode.valueOf(modeStr);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        target.setGameMode(mode);
                        rememberRequestedMode(target, mode);
                    });
                } catch (Exception ignored) {}
            }
            return;
        }
        if (p[1].startsWith("TPA_")) {
            Bukkit.getScheduler().runTask(plugin, () -> handleRemoteTpa(p));
            return;
        }
        if (p[1].equals("CMD") && p.length >= 3) {
            Bukkit.getScheduler().runTask(plugin, () -> handleRemoteCommand(p));
            return;
        }
        if (p[1].equals("RESULT") && p.length >= 5) {
            handleCommandResult(p);
            return;
        }
        if (p[1].equals("MODE") && p.length >= 4) {
            handleRemoteGameMode(p);
            return;
        }
        if (p[1].equals("ADMIN") && p.length >= 3) Bukkit.getScheduler().runTask(plugin, () -> {
            String command;
            try { command = decode(p[2]); } catch (IllegalArgumentException ignored) { return; }
            String root = command.trim().split("\\s+", 2)[0].toLowerCase();
            if (root.startsWith("minecraft:")) root = root.substring("minecraft:".length());
            if (!isAdministrationCommand(root)) return;
            applyingRemoteAdministration = true;
            try { executeCommandWithResult(Bukkit.getConsoleSender(), command, ignored -> { }); }
            finally { applyingRemoteAdministration = false; }
        });
        if ((p[1].equals("INV") || p[1].equals("LIVE")) && p.length >= 4) {
            try {
                UUID target = UUID.fromString(p[2]);
                ItemStack[] items = decodeInventory(p[3]);
                if (items == null) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    updateViewerSessions(target, items);
                    if (p[1].equals("INV")) {
                        Player player = Bukkit.getPlayer(target);
                        if (player != null && player.isOnline()) applyInventory(player, items);
                    }
                });
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private void handleRemoteCommand(String[] payload) {
        String command;
        try { command = decode(payload[2]); } catch (IllegalArgumentException ignored) { return; }
        if (payload.length < 4 || payload[3].isBlank()) return;
        String sender = payload[3];
        String[] parts = command.trim().split("\\s+");
        if (parts.length > 0 && parts[0].equalsIgnoreCase("gamemode")) {
            Player target = parts.length >= 3 ? Bukkit.getPlayerExact(parts[2]) : null;
            if (target != null) {
                GameMode requested = requestedModeForTarget(parts, target);
                if (requested != null) requestedGameModes.put(target.getUniqueId(), requested);
            }
        }
        Consumer<Boolean> result = executed -> publish(peerServer(), "RESULT|" + b64(sender) + "|"
                + (executed ? "1" : "0") + "|" + b64(command));
        if (executeDirectRemoteCommand(command, result)) return;
        if (!executeCommandWithResult(Bukkit.getConsoleSender(), command, result)) {
            publish(peerServer(), "RESULT|" + b64(sender) + "|0|" + b64(command));
        }
    }

    /** Directly handles /kill <exact player> on the backend that owns the entity. */
    private boolean executeDirectRemoteCommand(String command, Consumer<Boolean> result) {
        String[] parts = command.trim().split("\\s+");
        if (parts.length < 2) return false;
        String root = parts[0].toLowerCase(Locale.ROOT);
        if (root.startsWith("minecraft:")) root = root.substring("minecraft:".length());
        if (!root.equals("kill") || parts[1].startsWith("@")) return false;
        Player target = Bukkit.getPlayerExact(parts[1]);
        if (target == null || !target.isOnline()) return false;
        boolean killed = false;
        try {
            target.setHealth(0.0D);
            killed = target.isDead() || !target.isOnline() || target.getHealth() <= 0.0D;
        } catch (IllegalArgumentException | IllegalStateException ignored) { }
        result.accept(killed);
        return true;
    }

    private void handleRemoteGameMode(String[] payload) {
        UUID uuid;
        try { uuid = UUID.fromString(payload[2]); }
        catch (IllegalArgumentException ignored) { return; }
        GameMode mode = parseGameMode(payload[3]);
        if (mode == null) return;
        desiredGameModes.put(uuid, mode);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) applyGameMode(player, mode, false);
    }

    private void handleCommandResult(String[] payload) {
        final String sender;
        final String command;
        try {
            sender = decode(payload[2]);
            command = decode(payload[4]);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        boolean executed = "1".equals(payload[3]);

        if ("CONSOLE".equalsIgnoreCase(sender)) {
            if (executed) {
                Bukkit.getConsoleSender().sendMessage(ChatColor.GREEN + "[FreeCore] 跨服控制台指令 [" + command + "] 在远端子服执行成功！");
            } else {
                Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "[FreeCore] 跨服控制台指令 [" + command + "] 在远端子服执行失败！");
            }
            return;
        }

        Player player = Bukkit.getPlayerExact(sender);
        if (player == null || !player.isOnline()) return;
        if (executed) {
            sendSuccessfulFeedback(player, command);
        } else {
            sendFailureFeedback(player, command);
        }
    }

    public JavaPlugin getPlugin() { return this.plugin; }
    public String getCurrentServerId() { return serverId(); }

    public boolean isPlayerOnlineNetwork(String name) {
        if (name == null || name.isBlank()) return false;
        String s = redis("GET", "fce:online:name:" + name.toLowerCase(Locale.ROOT));
        return s != null && (s.equals("survival") || s.equals("technical"));
    }

    public UUID resolveNetworkUuid(String name) {
        if (name == null || name.isBlank()) return null;
        String val = redis("GET", "fce:online:name:" + name.toLowerCase(Locale.ROOT) + ":uuid");
        if (val == null) return null;
        try { return UUID.fromString(val); } catch (Exception e) { return null; }
    }

    public int getNetworkPlayerPing(String name) {
        String val = redis("GET", "fce:online:name:" + name.toLowerCase(Locale.ROOT) + ":ping");
        if (val == null) return 0;
        try { return Integer.parseInt(val); } catch (Exception e) { return 0; }
    }

    public String getNetworkPlayerIp(String name) {
        return redis("GET", "fce:online:name:" + name.toLowerCase(Locale.ROOT) + ":ip");
    }

    public int getBedrockPing(UUID uuid) {
        String val = redis("GET", "fce:bedrock:ping:" + uuid);
        if (val != null) {
            try { return Integer.parseInt(val); } catch (Exception ignored) {}
        }
        return 0;
    }

    public List<String> getAllNetworkOnlinePlayerNames() {
        List<String> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!list.contains(p.getName())) list.add(p.getName());
        }
        // Inquire Redis keys
        try (Socket s = new Socket()) {
            Config cfg = config();
            s.connect(new InetSocketAddress(cfg.host, cfg.port), 2000);
            s.setSoTimeout(2000);
            InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream();
            if (!cfg.password.isEmpty()) send(out, in, "AUTH", cfg.password);
            write(out, "KEYS", "fce:online:name:*");
            String[] arr = array(in);
            if (arr != null) {
                for (String k : arr) {
                    if (k.endsWith(":uuid") || k.endsWith(":ping") || k.endsWith(":ip") || k.endsWith(":realname")) continue;
                    String pNameLower = k.substring("fce:online:name:".length());
                    // Try to fetch real name with original case
                    String realName = redis("GET", "fce:online:name:" + pNameLower + ":realname");
                    String toAdd = (realName != null && !realName.isBlank()) ? realName : pNameLower;
                    boolean already = false;
                    for (String existing : list) {
                        if (existing.equalsIgnoreCase(toAdd)) { already = true; break; }
                    }
                    if (!already) list.add(toAdd);
                }
            }
        } catch (Throwable ignored) {}
        return list;
    }

    public String getPlayerDisplayName(Player player) {
        String nick = getPlayerNickname(player.getUniqueId());
        if (nick != null && !nick.isBlank()) {
            return ChatColor.translateAlternateColorCodes('&', nick);
        }
        return player.getName();
    }

    public void broadcastToAllPeers(String message) {
        publish(peerServer(), "BC|" + b64(message));
    }

    public void broadcastToServer(String targetServer, String message) {
        publish(targetServer, "BC|" + b64(message));
    }

    public void dispatchSudo(CommandSender sender, String targetName, String action) {
        String targetServer = redis("GET", "fce:online:name:" + targetName.toLowerCase(Locale.ROOT));
        if (targetServer != null) {
            publish(targetServer, "SUDO|" + b64(targetName) + "|" + b64(action) + "|" + b64(sender.getName()));
        }
    }

    public void sendPrivateMessage(CommandSender sender, String targetName, String senderDisplayName, String message) {
        String targetServer = redis("GET", "fce:online:name:" + targetName.toLowerCase(Locale.ROOT));
        if (targetServer != null) {
            String actualSenderName = (sender instanceof Player p) ? p.getName() : "控制台";
            String actualDisplayName = (sender instanceof Player p) ? getPlayerDisplayName(p) : "控制台";
            publish(targetServer, "MSG|" + b64(targetName) + "|" + b64(actualDisplayName) + "|" + b64(message));
            String formatSender = ChatColor.translateAlternateColorCodes('&',
                    this.lang.message("private-message.format-sender", targetName, message));
            sender.sendMessage(formatSender);
        }
    }

    public boolean isMuted(UUID uuid) {
        String val = redis("GET", MUTE_PREFIX + uuid);
        if (val == null) return false;
        String[] parts = val.split("\\|", 2);
        if (parts.length > 0) {
            try {
                long expireAt = Long.parseLong(parts[0]);
                if (expireAt > 0 && System.currentTimeMillis() > expireAt) {
                    redis("DEL", MUTE_PREFIX + uuid);
                    return false;
                }
            } catch (Exception ignored) {}
        }
        return true;
    }

    public String getMuteRemainingDisplay(UUID uuid) {
        String val = redis("GET", MUTE_PREFIX + uuid);
        if (val == null) return "0s";
        String[] parts = val.split("\\|", 2);
        try {
            long expireAt = Long.parseLong(parts[0]);
            if (expireAt <= 0) return lang.message("mute.forever");
            long diff = (expireAt - System.currentTimeMillis()) / 1000L;
            if (diff <= 0) return "0s";
            if (diff < 60) return diff + "s";
            if (diff < 3600) return (diff / 60) + "m";
            return (diff / 3600) + "h";
        } catch (Exception e) {
            return lang.message("mute.forever");
        }
    }

    public String getMuteReason(UUID uuid) {
        String val = redis("GET", MUTE_PREFIX + uuid);
        if (val == null) return "";
        String[] parts = val.split("\\|", 2);
        return parts.length > 1 ? parts[1] : "违反规范";
    }

    public void mutePlayer(UUID uuid, String name, long durationMillis, String reason) {
        long expireAt = durationMillis > 0 ? (System.currentTimeMillis() + durationMillis) : -1L;
        String val = expireAt + "|" + reason;
        if (durationMillis > 0) {
            long ttlSeconds = (durationMillis / 1000L) + 5;
            redis("SET", MUTE_PREFIX + uuid, val, "EX", String.valueOf(ttlSeconds));
        } else {
            redis("SET", MUTE_PREFIX + uuid, val);
        }
    }

    public void unmutePlayer(UUID uuid) {
        redis("DEL", MUTE_PREFIX + uuid);
    }

    public void notifyPlayerMuted(String targetName, String message) {
        String targetServer = redis("GET", "fce:online:name:" + targetName.toLowerCase(Locale.ROOT));
        if (targetServer != null) {
            publish(targetServer, "NOTIFY_MUTE|" + b64(targetName) + "|" + b64(message));
        }
    }

    public void notifyPlayerUnmuted(String targetName) {
        String targetServer = redis("GET", "fce:online:name:" + targetName.toLowerCase(Locale.ROOT));
        if (targetServer != null) {
            publish(targetServer, "NOTIFY_UNMUTE|" + b64(targetName));
        }
    }

    public void dispatchRemoteGameMode(CommandSender sender, String targetName, GameMode mode) {
        String targetServer = redis("GET", "fce:online:name:" + targetName.toLowerCase(Locale.ROOT));
        if (targetServer != null) {
            publish(targetServer, "REMOTE_GM|" + b64(targetName) + "|" + mode.name() + "|" + b64(sender.getName()));
        }
    }

    public String getPlayerNickname(UUID uuid) {
        long now = System.currentTimeMillis();
        CachedNickname cached = nicknameCache.get(uuid);
        if (cached != null && now < cached.expireAtMillis()) {
            return cached.nick();
        }
        String nick = redis("GET", NICK_PREFIX + uuid);
        nicknameCache.put(uuid, new CachedNickname(nick, now + 60_000L));
        return nick;
    }

    public void setPlayerNickname(UUID uuid, String nick) {
        Player p = Bukkit.getPlayer(uuid);
        String rawName = p != null ? p.getName() : "";
        if (nick == null || nick.isBlank()) {
            nicknameCache.put(uuid, new CachedNickname(null, System.currentTimeMillis() + 60_000L));
            redis("DEL", NICK_PREFIX + uuid);
            publishDirect("fctransfer:tab-name", uuid.toString() + "|" + rawName + "|" + b64(rawName));
        } else {
            nicknameCache.put(uuid, new CachedNickname(nick, System.currentTimeMillis() + 60_000L));
            redis("SET", NICK_PREFIX + uuid, nick, "EX", "2592000"); // 30 days
            String tabDisplayName = nick;
            if (p != null) {
                try {
                    String papiTab = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, "%freecore_tab_name%");
                    if (papiTab != null && !papiTab.isBlank() && !papiTab.equals("%freecore_tab_name%")) {
                        tabDisplayName = papiTab;
                    }
                } catch (Throwable ignored) {}
            }
            publishDirect("fctransfer:tab-name", uuid.toString() + "|" + rawName + "|" + b64(tabDisplayName));
        }
    }

    private void publishDirect(String channel, String message) {
        try {
            Config cfg = config();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(cfg.host, cfg.port), 2000);
                s.setSoTimeout(2000);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                if (!cfg.password.isEmpty()) send(out, in, "AUTH", cfg.password);
                send(out, in, "PUBLISH", channel, message);
            }
        } catch (Throwable ignored) {}
    }

    public boolean isMaintenanceActive(String server) {
        String val = redis("GET", MAINTENANCE_PREFIX + server.toLowerCase(Locale.ROOT));
        return "1".equals(val) || "true".equalsIgnoreCase(val);
    }

    public Map<String, Boolean> getMaintenanceStatusAll() {
        return Map.of(
                "all", isMaintenanceActive("all"),
                "survival", isMaintenanceActive("survival"),
                "technical", isMaintenanceActive("technical")
        );
    }

    public void setMaintenanceMode(String server, boolean enable) {
        String key = MAINTENANCE_PREFIX + server.toLowerCase(Locale.ROOT);
        if (enable) {
            redis("SET", key, "1");
            publish("all", "MAINT_KICK|" + server.toLowerCase(Locale.ROOT));
            publish(peerServer(), "MAINT_KICK|" + server.toLowerCase(Locale.ROOT));
            // Trigger local kick if applies
            kickNonOpsForMaintenance(server);
        } else {
            redis("DEL", key);
        }
    }

    public void kickNonOpsForMaintenance(String server) {
        if (!"all".equalsIgnoreCase(server) && !serverId().equalsIgnoreCase(server)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            String kickMsg = ChatColor.translateAlternateColorCodes('&',
                    this.lang.message("maintenance.kick-message"));
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.isOp() || player.hasPermission("freecoreessentials.maintenance.bypass")) continue;
                player.kickPlayer(kickMsg);
            }
        });
    }

    private String peerServer() {
        return "survival".equals(serverId()) ? "technical" : "survival";
    }

    private void publishPresence() { Bukkit.getOnlinePlayers().forEach(this::publishPresence); }
    private void publishPresence(Player player) {
        String server = serverId();
        int ping = player.getPing();
        int brPing = getBedrockPing(player.getUniqueId());
        if (brPing > 0) ping = brPing;
        String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                ? player.getAddress().getAddress().getHostAddress() : "未知";
        // Use 180s TTL so Redis heartbeat never expires even during severe TPS drops or GC pauses
        redis("SET", "fce:online:name:" + player.getName().toLowerCase(), server, "EX", "180");
        redis("SET", "fce:online:name:" + player.getName().toLowerCase() + ":realname", player.getName(), "EX", "180");
        redis("SET", "fce:online:name:" + player.getName().toLowerCase() + ":uuid", player.getUniqueId().toString(), "EX", "180");
        redis("SET", "fce:online:name:" + player.getName().toLowerCase() + ":ping", String.valueOf(ping), "EX", "180");
        redis("SET", "fce:online:name:" + player.getName().toLowerCase() + ":ip", ip, "EX", "180");
        redis("SET", "fce:online:uuid:" + player.getUniqueId(), server, "EX", "180");
    }
    private String serverId() { return Bukkit.getPort() == 25570 ? "technical" : "survival"; }
    private void publish(String destination, String payload) { redis("PUBLISH", CHANNEL, destination + "|" + payload); }
    private String b64(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private String decode(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }

    private void listen() {
        while (running) try (Socket socket = new Socket()) {
            Config cfg = config(); socket.connect(new InetSocketAddress(cfg.host, cfg.port), 2000);
            InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream();
            if (!cfg.password.isEmpty() && send(out, in, "AUTH", cfg.password) == null) continue;
            write(out, "SUBSCRIBE", CHANNEL);
            String[] subscribed = array(in);
            if (subscribed.length != 3 || !"subscribe".equals(subscribed[0])) throw new IOException("redis subscribe");
            plugin.getLogger().info("Cross-server Redis listener subscribed to " + CHANNEL + ".");
            while (running) { String[] a = array(in); if (a.length == 3 && "message".equals(a[0])) handle(a[2]); }
        } catch (Exception ex) {
            if (!running) return;
            plugin.getLogger().log(Level.WARNING, "Cross-server Redis listener retry", ex);
            try { Thread.sleep(1000L); } catch (InterruptedException ignored) { return; }
        }
    }
    private Config config() {
        Config cfg = this.cachedConfig;
        if (cfg != null) return cfg;
        org.bukkit.configuration.file.YamlConfiguration c = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.File(plugin.getDataFolder().getParentFile(), "HuskSync/config.yml"));
        cfg = new Config(c.getString("redis.credentials.host", "127.0.0.1"), c.getInt("redis.credentials.port", 6379), c.getString("redis.credentials.password", ""));
        this.cachedConfig = cfg;
        return cfg;
    }
    private String redis(String... cmd) { try { Config cfg=config(); try(Socket s=new Socket()){s.connect(new InetSocketAddress(cfg.host,cfg.port),2000); s.setSoTimeout(2000); InputStream in=s.getInputStream(); OutputStream out=s.getOutputStream(); if(!cfg.password.isEmpty()&&send(out,in,"AUTH",cfg.password)==null)return null; return send(out,in,cmd);}} catch(Exception ex){return null;} }
    private String send(OutputStream out, InputStream in, String... values) throws IOException { write(out, values); int t=in.read(); if(t<0||t=='-'){line(in);return null;} String l=line(in); if(t=='$'){int n=Integer.parseInt(l); if(n<0)return null; byte[] b=in.readNBytes(n);in.read();in.read();return new String(b,StandardCharsets.UTF_8);} return l; }
    private void write(OutputStream out, String... values) throws IOException { StringBuilder q=new StringBuilder("*").append(values.length).append("\r\n"); for(String v:values) q.append("$").append(v.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(v).append("\r\n"); out.write(q.toString().getBytes(StandardCharsets.UTF_8)); out.flush(); }
    private String line(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int n;while((n=in.read())!=-1&&n!='\r')b.write(n);if(n<0||in.read()!='\n')throw new IOException("redis");return b.toString(StandardCharsets.UTF_8);}
    private String[] array(InputStream in)throws IOException{if(in.read()!='*')throw new IOException("redis array");int n=Integer.parseInt(line(in));String[] a=new String[n];for(int i=0;i<n;i++){int type=in.read();if(type==':'){a[i]=line(in);continue;}if(type!='$')throw new IOException("redis bulk");int z=Integer.parseInt(line(in));byte[] b=in.readNBytes(z);in.read();in.read();a[i]=new String(b,StandardCharsets.UTF_8);}return a;}
    private record Config(String host,int port,String password) {}
    private record Session(UUID target, String name, Inventory inventory) {}
}
