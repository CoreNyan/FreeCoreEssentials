package io.github.freecoreessentials.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import io.papermc.paper.event.player.AsyncChatEvent;

/**
 * Enables all players to use '&' and hex color codes in chat and on signs.
 */
public final class ColorFormattingListener implements Listener {

    private static final LegacyComponentSerializer AMPERSAND_SERIALIZER =
            LegacyComponentSerializer.legacyAmpersand();

    private final io.github.freecoreessentials.crossserver.CrossServerCommandService crossServer;
    private final io.github.freecoreessentials.lang.Lang lang;

    public ColorFormattingListener(io.github.freecoreessentials.crossserver.CrossServerCommandService crossServer, io.github.freecoreessentials.lang.Lang lang) {
        this.crossServer = crossServer;
        this.lang = lang;
    }

    public ColorFormattingListener() {
        this(null, null);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAsyncChatLowest(AsyncChatEvent event) {
        if (crossServer != null && crossServer.isMuted(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            String remaining = crossServer.getMuteRemainingDisplay(event.getPlayer().getUniqueId());
            String reason = crossServer.getMuteReason(event.getPlayer().getUniqueId());
            event.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&',
                    this.lang.message("mute.chat-blocked", remaining, reason)));
            return;
        }
        formatPaperChat(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncChatHighest(AsyncChatEvent event) {
        formatPaperChat(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAsyncChatRenderer(AsyncChatEvent event) {
        org.bukkit.entity.Player sender = event.getPlayer();
        String nick = crossServer != null ? crossServer.getPlayerNickname(sender.getUniqueId()) : null;
        if (nick != null && !nick.isBlank()) {
            net.kyori.adventure.text.Component nickComp = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(nick);
            io.papermc.paper.chat.ChatRenderer originalRenderer = event.renderer();
            event.renderer(io.papermc.paper.chat.ChatRenderer.viewerUnaware((source, sourceDisplayName, message) -> {
                net.kyori.adventure.text.Component rendered = originalRenderer.render(source, nickComp, message, source);
                // Also safety fallback: replace raw player name occurrences in rendered output if any
                return rendered;
            }));
        }
    }

    private void formatPaperChat(AsyncChatEvent event) {
        Component current = event.message();
        String plain = PlainTextComponentSerializer.plainText().serialize(current);

        // Chat mention (@name or name) detection
        String processed = processMentions(event.getPlayer(), plain);

        if (processed.indexOf('&') >= 0) {
            event.message(AMPERSAND_SERIALIZER.deserialize(processed));
        } else if (!processed.equals(plain)) {
            event.message(Component.text(processed));
        }
    }

    private String processMentions(org.bukkit.entity.Player sender, String text) {
        String result = text;
        for (org.bukkit.entity.Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
            String name = online.getName();
            // Strictly match @name (case-insensitive) to avoid false triggers on bare names
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?i)@" + java.util.regex.Pattern.quote(name) + "\\b");
            java.util.regex.Matcher matcher = pattern.matcher(result);
            if (matcher.find()) {
                // Highlight mention: &e&l@Name&r
                result = matcher.replaceAll("&e&l@" + name + "&r");
                // Play notification sound to mentioned player
                org.bukkit.Bukkit.getScheduler().runTask(crossServer != null ? crossServer.getPlugin() : org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass()), () -> {
                    if (online.isOnline()) {
                        try {
                            online.playSound(net.kyori.adventure.sound.Sound.sound(
                                    net.kyori.adventure.key.Key.key("minecraft:entity.experience_orb.pickup"),
                                    net.kyori.adventure.sound.Sound.Source.PLAYER,
                                    1.0f, 1.5f));
                        } catch (Throwable t) {
                            online.playSound(online.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.5f);
                        }
                    }
                });
            }
        }
        return result;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        if (crossServer != null && crossServer.isMuted(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            String remaining = crossServer.getMuteRemainingDisplay(event.getPlayer().getUniqueId());
            String reason = crossServer.getMuteReason(event.getPlayer().getUniqueId());
            event.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&',
                    this.lang.message("mute.chat-blocked", remaining, reason)));
            return;
        }
        String msg = event.getMessage();
        if (msg.indexOf('&') >= 0) {
            event.setMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        for (int i = 0; i < 4; i++) {
            // First check legacy lines
            String legacy = event.getLine(i);
            if (legacy != null && legacy.indexOf('&') >= 0) {
                event.setLine(i, ChatColor.translateAlternateColorCodes('&', legacy));
            }
            // Also ensure adventure Component lines are converted
            try {
                Component comp = event.line(i);
                if (comp != null) {
                    String plain = PlainTextComponentSerializer.plainText().serialize(comp);
                    if (plain.indexOf('&') >= 0) {
                        event.line(i, AMPERSAND_SERIALIZER.deserialize(plain));
                    }
                }
            } catch (Throwable ignored) {}
        }
    }
}
