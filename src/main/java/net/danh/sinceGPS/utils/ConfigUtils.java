package net.danh.sinceGPS.utils;

import net.danh.sinceGPS.SinceGPS;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;

public class ConfigUtils {
    private final SinceGPS plugin;
    private final String name;
    private File file;
    private FileConfiguration config;

    public ConfigUtils(SinceGPS plugin, String name) {
        this.plugin = plugin;
        this.name = name;
        this.load();
    }

    public void load() {
        file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            if (plugin.getResource(name) != null) {
                plugin.saveResource(name, false);
            } else {
                try {
                    file.createNewFile();
                } catch (IOException exception) {
                    plugin.getLogger().log(Level.SEVERE, "Could not create config file " + name + ".", exception);
                }
            }
        }
        config = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        try {
            config.save(file);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not save config file " + name + ".", exception);
        }
    }

    public void reload() {
        load();
    }

    public String getString(String path) {
        return config.getString(path, "");
    }

    public String getString(String path, String def) {
        return config.getString(path, def);
    }

    public int getInt(String path, int def) {
        return config.getInt(path, def);
    }

    public int getInt(String path) {
        return config.getInt(path);
    }

    public double getDouble(String path, double def) {
        return config.getDouble(path, def);
    }

    public boolean getBoolean(String path, boolean def) {
        return config.getBoolean(path, def);
    }

    public boolean getBoolean(String path) {
        return config.getBoolean(path);
    }

    public List<String> getStringList(String path) {
        return config.getStringList(path);
    }

    public void set(String path, Object value) {
        config.set(path, value);
    }

    public void setAndSave(String path, Object value) {
        config.set(path, value);
        save();
    }

    public ConfigurationSection getSection(String path) {
        return config.getConfigurationSection(path);
    }

    public Location getLocation(String path) {
        return config.getLocation(path);
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public void playSound(Player player, String path) {
        if (!config.getBoolean(path + ".enabled", true)) return;

        Sound sound = getSoundSafe(config.getString(path + ".type"));
        if (sound == null) return;

        try {
            float volume = (float) config.getDouble(path + ".volume", 1.0);
            float pitch = (float) config.getDouble(path + ".pitch", 1.0);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException ignored) {
            // Invalid sound settings should not break gameplay.
        }
    }

    private Sound getSoundSafe(String name) {
        if (name == null || name.isBlank()) return null;

        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase());
        if (key != null) {
            Sound sound = Registry.SOUND_EVENT.get(key);
            if (sound != null) return sound;
        }

        try {
            return (Sound) Sound.class.getField(name.toUpperCase()).get(null);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
