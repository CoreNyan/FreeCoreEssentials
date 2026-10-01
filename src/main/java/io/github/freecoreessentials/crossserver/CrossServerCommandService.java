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
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
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
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
    private static final Set<String> PLAYER_COMMANDS = Set.of(
            "give", "tp", "teleport", "gamemode", "effect", "clear", "enchant", "experience", "xp",
            "attribute", "data", "item", "damage", "kill", "spectate", "ride", "title", "tell", "msg",
            "w", "whisper", "team", "playsound", "stopsound", "advancement", "recipe", "kick", "ban",
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
    private volatile boolean running;
    private boolean applyingRemoteAdministration;

    private record TpaRequest(String id, UUID requesterId, String requesterName, String requesterServer,
                              UUID targetId, String targetName, String targetServer) {}

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
    }

    public void close() {
        running = false;
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, "BungeeCord");
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        // A target may have been offline when an InvSee session was opened.
        // Re-read the shared HuskSync record after login has finished so the
        // live player inventory cannot remain at a stale pre-login snapshot.
        UUID uuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> refreshInventoryOnJoin(uuid), 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> refreshInventoryOnJoin(uuid), 60L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> refreshInventoryOnJoin(uuid), 100L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> refreshInventoryOnJoin(uuid), 160L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> refreshInventoryOnJoin(uuid), 220L);
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
        try { return GameMode.valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (applyingGameMode.remove(uuid)) return;
        GameMode mode = event.getNewGameMode();
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

    private void rememberRequestedMode(Player player, GameMode mode) {
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
            String key = TICKET_PREFIX + player.getUniqueId();
            String ticket = redis("GET", key);
            if (ticket == null) {
                if (attempt < 40) Bukkit.getScheduler().runTaskLater(plugin,
                        () -> pollQueuedCommand(player, attempt + 1), 5L);
                else plugin.getLogger().warning("No cross-server command ticket found after connect for " + player.getName());
                return;
            }
            redis("DEL", key);
            String commandLine = decode(ticket);
            plugin.getLogger().info("Found cross-server command ticket after connect for " + player.getName()
                    + ": /" + commandLine);
            Bukkit.getScheduler().runTaskLater(plugin, () -> runQueuedCommand(player, commandLine, 0), 10L);
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

    private boolean executeDirectTeleport(Player sender, String commandLine) {
        String[] parts = commandLine.trim().split("\\s+");
        if (parts.length < 2) return false;
        String root = parts[0].toLowerCase();
        if (!root.equals("tp") && !root.equals("teleport")) return false;
        Player target = Bukkit.getPlayerExact(parts[1]);
        if (target == null || !target.isOnline()) return false;
        // Teleport directly after the cross-server connect.  Running the
        // original command through Brigadier is racy during the backend join,
        // and a command such as /tp target x y z otherwise targets the remote
        // entity through a dispatcher that may not have finished indexing it.
        if (parts.length == 2) {
            boolean moved = sender.teleport(target.getLocation());
            plugin.getLogger().info("Cross-server direct teleport " + (moved ? "completed" : "failed")
                    + " for " + sender.getName() + " to " + target.getName());
            if (moved) sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                    + this.lang.message("vanilla-command.teleport-completed", target.getName()));
            return moved;
        }
        if (parts.length == 3) {
            Player moved = Bukkit.getPlayerExact(parts[1]);
            Player destination = Bukkit.getPlayerExact(parts[2]);
            if (destination == null && (parts[2].equalsIgnoreCase("@s") || parts[2].equalsIgnoreCase("@p"))) {
                destination = sender;
            }
            if (moved == null || destination == null || !moved.isOnline() || !destination.isOnline()) return false;
            boolean teleported = moved.teleport(destination.getLocation());
            plugin.getLogger().info("Cross-server direct target teleport " + (teleported ? "completed" : "failed")
                    + " for " + moved.getName() + " to " + destination.getName());
            if (teleported) sender.sendMessage(this.lang.message("prefix.freecoreessentials") + ChatColor.GREEN
                    + this.lang.message("vanilla-command.teleport-completed", moved.getName()));
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
        return target.startsWith("@") || Bukkit.getPlayerExact(target) != null;
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        pendingTpa.remove(event.getPlayer().getUniqueId());
        requestedGameModes.remove(event.getPlayer().getUniqueId());
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
        if (root.equals("tpa") || root.equals("tpaccept") || root.equals("tpdeny")) {
            event.setCancelled(true);
            handleTpaCommand(event.getPlayer(), root, parts);
            return;
        }
        if (root.equals("gamemode")) {
            GameMode requested = requestedMode(parts, event.getPlayer());
            if (requested != null) rememberRequestedMode(event.getPlayer(), requested);
        }
        if (isAdministrationCommand(root)) {
            if (event.getPlayer().isOp() || event.getPlayer().hasPermission("minecraft.command.op")) {
                event.setCancelled(true);
                executeCommandWithResult(event.getPlayer(), raw.substring(1), success -> {
                    if (success) {
                        publishPeerAdministration(raw.substring(1));
                        sendSuccessfulFeedback(event.getPlayer(), raw.substring(1));
                    }
                });
            }
            return;
        }
        if (!PLAYER_COMMANDS.contains(root)) return;
        String target = playerArgument(root, parts);
        String targetServer = target == null || target.startsWith("@")
                ? null : redis("GET", "fce:online:name:" + target.toLowerCase());
        if (targetServer == null || targetServer.equals(serverId())) {
            String commandLine = raw.substring(1);
            boolean gamemode = root.equals("gamemode");
            event.setCancelled(true);
            executeCommandWithResult(event.getPlayer(), commandLine, success -> {
                if (success && !gamemode) sendSuccessfulFeedback(event.getPlayer(), commandLine);
            });
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
            default -> { return; }
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
            // Custom feedback is emitted by this service. Suppress vanilla command
            // feedback while retaining the command source's permissions and identity.
            try {
                java.lang.reflect.Method silent = sourceStack.getClass().getMethod("withSuppressedOutput");
                sourceStack = silent.invoke(sourceStack);
            } catch (ReflectiveOperationException ignored) {
                // Older Paper mappings may not expose withSuppressedOutput; execution
                // still proceeds with the original source.
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

    private String displayTarget(Player player, String target) {
        return target.equalsIgnoreCase("@s") || target.equalsIgnoreCase("@p") ? player.getName() : target;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onServerCommand(ServerCommandEvent event) {
        if (applyingRemoteAdministration) return;
        String command = event.getCommand().trim();
        if (command.startsWith("/")) command = command.substring(1).trim();
        if (command.isEmpty()) return;
        String root = command.split("\\s+", 2)[0].toLowerCase();
        if (root.startsWith("minecraft:")) root = root.substring("minecraft:".length());
        if (isAdministrationCommand(root)) publishPeerAdministration(command);
    }

    private boolean isAdministrationCommand(String root) {
        return Set.of("op", "deop", "ban", "pardon", "ban-ip", "pardon-ip").contains(root);
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
            Bukkit.getScheduler().runTask(plugin, () -> publishLiveInventoryWhenCursorSettled(viewer));
            return;
        }
        // Bukkit applies the click after this event. Snapshot on the next tick so
        // normal pickup, placement, shift-click, hotbar and drop actions are saved.
        Bukkit.getScheduler().runTask(plugin,
                () -> persistInventoryWhenCursorSettled(viewer, session, event.getView().getTopInventory()));
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
            ItemStack remaining = moveIntoTarget(session.inventory, event.getCurrentItem());
            if (remaining.getAmount() != event.getCurrentItem().getAmount()) {
                event.setCancelled(true);
                event.setCurrentItem(remaining.getAmount() == 0 ? null : remaining);
                Bukkit.getScheduler().runTask(plugin, () -> persistInventory(session, session.inventory));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        Session session = sessionFor(viewer, event.getView().getTopInventory());
        if (session == null) {
            Bukkit.getScheduler().runTask(plugin, () -> publishLiveInventoryWhenCursorSettled(viewer));
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot >= 41 && slot < topSize)) {
            event.setCancelled(true);
            return;
        }
        if (event.getRawSlots().stream().anyMatch(slot -> slot < 41)) {
            Bukkit.getScheduler().runTask(plugin,
                    () -> persistInventoryWhenCursorSettled(viewer, session, event.getView().getTopInventory()));
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
        storeInvSeeState(session.target, snapshot(event.getView().getTopInventory()), 30);
    }

    private Session sessionFor(Player viewer, Inventory inventory) {
        Session session = sessions.get(viewer.getUniqueId());
        return session != null && session.inventory == inventory ? session : null;
    }

    private void persistInventory(Session session, Inventory inventory) {
        ItemStack[] items = snapshot(inventory);
        persistDatabase(session.target, items);
        storeInvSeeState(session.target, items, 600);
        Player local = Bukkit.getPlayer(session.target);
        if (local != null) {
            applyInventory(local, items);
        }
        publishInventory("INV", session.target, items);
    }

    private void persistInventoryWhenCursorSettled(Player viewer, Session session, Inventory inventory) {
        if (!viewer.isOnline()) return;
        if (hasCursorItem(viewer)) {
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> persistInventoryWhenCursorSettled(viewer, session, inventory), 1L);
            return;
        }
        persistInventory(session, inventory);
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
        ItemStack[] items = snapshot(player);
        updateViewerSessions(player.getUniqueId(), items);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean active = "1".equals(redis("EXISTS", INVSEE_STATE_PREFIX + player.getUniqueId()));
            if (active) {
                persistDatabase(player.getUniqueId(), items);
                storeInvSeeState(player.getUniqueId(), items, 600);
            }
            publishInventory("LIVE", player.getUniqueId(), items);
        });
    }

    private void publishLiveInventoryWhenCursorSettled(Player player) {
        if (!player.isOnline()) return;
        if (hasCursorItem(player)) {
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> publishLiveInventoryWhenCursorSettled(player), 1L);
            return;
        }
        publishLiveInventory(player);
    }

    private boolean hasCursorItem(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        return cursor != null && !cursor.getType().isAir() && cursor.getAmount() > 0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> publishLiveInventory(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> publishLiveInventory(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldItem(PlayerItemHeldEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> publishLiveInventory(event.getPlayer()));
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
        String key = TICKET_PREFIX + player.getUniqueId();
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
        if (root.equals("tpa")) {
            if (parts.length != 2 || parts[1].isBlank()) {
                player.sendMessage(ChatColor.YELLOW + "用法: /tpa <玩家>");
                return;
            }
            requestTeleport(player, parts[1]);
            return;
        }
        if (parts.length > 2) {
            player.sendMessage(ChatColor.YELLOW + "用法: /" + root + " [玩家]");
            return;
        }
        respondToTeleportRequest(player, root.equals("tpaccept"), parts.length == 2 ? parts[1] : null);
    }

    private void requestTeleport(Player requester, String targetName) {
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
                    targetName, targetServer);
            String targetKey = TPA_TARGET_PREFIX + targetId;
            if (redis("SET", targetKey, requestId, "NX", "EX", "60") == null
                    || redis("SET", TPA_REQUEST_PREFIX + requestId, serializeTpa(request), "EX", "60") == null) {
                redis("DEL", targetKey);
                Bukkit.getScheduler().runTask(plugin, () -> requester.sendMessage(ChatColor.YELLOW + "该玩家已有待处理的传送请求。"));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> requester.sendMessage(ChatColor.GREEN + "已向 " + targetName + " 发送传送请求，有效期 60 秒。"));
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
                        + "|" + b64(request.requesterName) + "|" + request.requesterServer + "|" + b64(targetName));
            }
        });
    }

    private void notifyTpaTarget(Player target, TpaRequest request) {
        target.sendMessage(ChatColor.YELLOW + "玩家 " + request.requesterName + " 请求传送到你这里。请输入 "
                + ChatColor.WHITE + "/tpaccept " + request.requesterName + ChatColor.YELLOW + " 同意，或 "
                + ChatColor.WHITE + "/tpdeny " + request.requesterName + ChatColor.YELLOW + " 拒绝（60秒内有效）。");
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
                    request.targetId, request.targetName, serverId());
            String action = accepted ? "TPA_ACCEPT" : "TPA_DENY";
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
                        + b64(current.requesterName) + "|" + current.targetId + "|" + b64(current.targetName) + "|" + current.targetServer);
                Bukkit.getScheduler().runTask(plugin, () -> target.sendMessage(accepted
                        ? ChatColor.GREEN + "已同意 " + current.requesterName + " 的传送请求。"
                        : ChatColor.GREEN + "已拒绝 " + current.requesterName + " 的传送请求。"));
            }
        });
    }

    private String serializeTpa(TpaRequest request) {
        return request.requesterId + "|" + b64(request.requesterName) + "|" + request.requesterServer + "|"
                + request.targetId + "|" + b64(request.targetName) + "|" + request.targetServer;
    }

    private void handleRemoteTpa(String[] payload) {
        if (payload[1].equals("TPA_REQUEST") && payload.length >= 8) {
            try {
                String requestId = payload[2];
                UUID targetId = UUID.fromString(payload[3]);
                UUID requesterId = UUID.fromString(payload[4]);
                Player target = Bukkit.getPlayer(targetId);
                if (target == null || !target.isOnline()) return;
                TpaRequest request = new TpaRequest(requestId, requesterId, decode(payload[5]), payload[6], targetId,
                        decode(payload[7]), serverId());
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
            TpaRequest request = new TpaRequest(requestId, requesterId, decode(payload[4]), serverId(), targetId,
                    decode(payload[6]), payload[7]);
            Player requester = Bukkit.getPlayer(requesterId);
            if (requester == null || !requester.isOnline()) return;
            if (payload[1].equals("TPA_DENY")) {
                requester.sendMessage(ChatColor.YELLOW + request.targetName + " 拒绝了你的传送请求。" );
                return;
            }
            completeTpa(requester, request);
        } catch (IllegalArgumentException ignored) { }
    }

    private void completeTpa(Player requester, TpaRequest request) {
        if (!request.targetServer.equals(serverId())) {
            String ticketKey = TPA_TICKET_PREFIX + requester.getUniqueId();
            String ticket = request.targetId + "|" + b64(request.targetName) + "|" + request.targetServer + "|" + request.id;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                if (redis("SET", ticketKey, ticket, "EX", "120") == null) {
                    Bukkit.getScheduler().runTask(plugin, () -> requester.sendMessage(ChatColor.RED + "跨服传送票据写入失败，请重试。"));
                    return;
                }
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
                if (attempt < 80) Bukkit.getScheduler().runTaskLater(plugin, () -> pollTpaTicket(player, attempt + 1), 5L);
                return;
            }
            redis("DEL", key);
            Bukkit.getScheduler().runTask(plugin, () -> completeTpaAfterJoin(player, ticket, 0));
        });
    }

    private void completeTpaAfterJoin(Player requester, String ticket, int attempt) {
        if (!requester.isOnline()) return;
        String[] fields = ticket.split("\\|", 4);
        if (fields.length < 3) return;
        try {
            UUID targetId = UUID.fromString(fields[0]);
            Player target = Bukkit.getPlayer(targetId);
            if (target == null || !target.isOnline()) {
                if (attempt < 120) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> completeTpaAfterJoin(requester, ticket, attempt + 1), 5L);
                } else {
                    plugin.getLogger().warning("Cross-server TPA target was not online after connect for "
                            + requester.getName() + ": " + fields[1]);
                    requester.sendMessage(ChatColor.RED + "目标玩家尚未进入当前子服，位置传送失败，请重试。" );
                }
                return;
            }
            boolean moved = requester.teleport(target.getLocation());
            plugin.getLogger().info("Cross-server TPA location teleport " + (moved ? "completed" : "failed")
                    + " for " + requester.getName() + " to " + target.getName());
            if (moved) requester.sendMessage(ChatColor.GREEN + "已传送到 " + target.getName() + " 身边。" );
            else requester.sendMessage(ChatColor.RED + "传送失败。" );
        } catch (IllegalArgumentException ignored) {
            requester.sendMessage(ChatColor.RED + "传送票据无效，传送失败。" );
        }
    }

    private void handle(String message) {
        String[] p = message.split("\\|", 12);
        if (p.length < 2 || !serverId().equals(p[0])) return;
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
        Player player = Bukkit.getPlayerExact(sender);
        if (player == null || !player.isOnline()) return;
        boolean executed = "1".equals(payload[3]);
        if (executed) {
            sendSuccessfulFeedback(player, command);
        }
    }

    private String peerServer() {
        return "survival".equals(serverId()) ? "technical" : "survival";
    }

    private void publishPresence() { Bukkit.getOnlinePlayers().forEach(this::publishPresence); }
    private void publishPresence(Player player) {
        String server = serverId();
        redis("SET", "fce:online:name:" + player.getName().toLowerCase(), server, "EX", "30");
        redis("SET", "fce:online:name:" + player.getName().toLowerCase() + ":uuid", player.getUniqueId().toString(), "EX", "30");
        redis("SET", "fce:online:uuid:" + player.getUniqueId(), server, "EX", "30");
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
    private Config config() { org.bukkit.configuration.file.YamlConfiguration c = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.File(plugin.getDataFolder().getParentFile(), "HuskSync/config.yml")); return new Config(c.getString("redis.credentials.host", "127.0.0.1"), c.getInt("redis.credentials.port", 6379), c.getString("redis.credentials.password", "")); }
    private String redis(String... cmd) { try { Config cfg=config(); try(Socket s=new Socket()){s.connect(new InetSocketAddress(cfg.host,cfg.port),2000); s.setSoTimeout(2000); InputStream in=s.getInputStream(); OutputStream out=s.getOutputStream(); if(!cfg.password.isEmpty()&&send(out,in,"AUTH",cfg.password)==null)return null; return send(out,in,cmd);}} catch(Exception ex){return null;} }
    private String send(OutputStream out, InputStream in, String... values) throws IOException { write(out, values); int t=in.read(); if(t<0||t=='-'){line(in);return null;} String l=line(in); if(t=='$'){int n=Integer.parseInt(l); if(n<0)return null; byte[] b=in.readNBytes(n);in.read();in.read();return new String(b,StandardCharsets.UTF_8);} return l; }
    private void write(OutputStream out, String... values) throws IOException { StringBuilder q=new StringBuilder("*").append(values.length).append("\r\n"); for(String v:values) q.append("$").append(v.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(v).append("\r\n"); out.write(q.toString().getBytes(StandardCharsets.UTF_8)); out.flush(); }
    private String line(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int n;while((n=in.read())!=-1&&n!='\r')b.write(n);if(n<0||in.read()!='\n')throw new IOException("redis");return b.toString(StandardCharsets.UTF_8);}
    private String[] array(InputStream in)throws IOException{if(in.read()!='*')throw new IOException("redis array");int n=Integer.parseInt(line(in));String[] a=new String[n];for(int i=0;i<n;i++){int type=in.read();if(type==':'){a[i]=line(in);continue;}if(type!='$')throw new IOException("redis bulk");int z=Integer.parseInt(line(in));byte[] b=in.readNBytes(z);in.read();in.read();a[i]=new String(b,StandardCharsets.UTF_8);}return a;}
    private record Config(String host,int port,String password) {}
    private record Session(UUID target, String name, Inventory inventory) {}
}
