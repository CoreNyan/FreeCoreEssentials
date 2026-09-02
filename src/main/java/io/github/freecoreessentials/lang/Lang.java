package io.github.freecoreessentials.lang;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Loads the bundled Chinese language file and formats numbered placeholders. */
public final class Lang {
   private final JavaPlugin plugin;
   private final File file;
   private FileConfiguration messages;

   public Lang(JavaPlugin plugin) {
      this.plugin = plugin;
      plugin.saveResource("lang/zh_CN.yml", false);
      this.file = new File(plugin.getDataFolder(), "lang/zh_CN.yml");
      this.reload();
   }

   public void reload() {
      this.messages = YamlConfiguration.loadConfiguration(this.file);
      this.addMissingDefaults();
   }

   private void addMissingDefaults() {
      try (InputStream resource = this.plugin.getResource("lang/zh_CN.yml")) {
         if (resource == null) {
            this.plugin.getLogger().warning("Bundled language file lang/zh_CN.yml is missing.");
            return;
         }

         FileConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(resource, StandardCharsets.UTF_8));
         boolean changed = false;
         for (String key : defaults.getKeys(true)) {
            if (!defaults.isConfigurationSection(key) && !this.messages.contains(key)) {
               this.messages.set(key, defaults.get(key));
               changed = true;
            }
         }

         if (changed) {
            this.messages.save(this.file);
         }
      } catch (IOException exception) {
         this.plugin.getLogger().warning("Could not update missing language keys: " + exception.getMessage());
      }
   }

   public String message(String key, Object... arguments) {
      String value = this.messages.getString(key);
      if (value == null) {
         this.plugin.getLogger().warning("Missing language key: " + key);
         return key;
      }

      for (int index = 0; index < arguments.length; index++) {
         value = value.replace("{" + index + "}", String.valueOf(arguments[index]));
      }

      return ChatColor.translateAlternateColorCodes('&', value);
   }
}
