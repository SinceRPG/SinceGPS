package net.danh.sinceGPS;

import net.danh.sinceGPS.command.GPSCommand;
import net.danh.sinceGPS.listeners.GPSListener;
import net.danh.sinceGPS.manager.NavigationManager;
import net.danh.sinceGPS.manager.WaypointsManager;
import net.danh.sinceGPS.utils.ConfigUtils;
import org.bukkit.plugin.java.JavaPlugin;

public final class SinceGPS extends JavaPlugin {
    private static SinceGPS instance;
    private NavigationManager navigationManager;
    private WaypointsManager waypointsManager;
    private ConfigUtils settingsConfig;
    private ConfigUtils messagesConfig;

    public static SinceGPS inst() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        this.settingsConfig = new ConfigUtils(this, "config.yml");
        this.messagesConfig = new ConfigUtils(this, "messages.yml");
        this.waypointsManager = new WaypointsManager(this);
        this.navigationManager = new NavigationManager(this);
        new GPSCommand(this).register();
        getServer().getPluginManager().registerEvents(new GPSListener(this), this);
        getLogger().info("SinceGPS (Particle Mode) Loaded!");
    }

    @Override
    public void onDisable() {
        if (navigationManager != null) navigationManager.shutdown();
    }

    public void reloadPlugin() {
        settingsConfig.reload();
        messagesConfig.reload();
        waypointsManager.reload();
        getLogger().info("Configuration reloaded!");
    }

    public NavigationManager getNav() {
        return navigationManager;
    }

    public WaypointsManager getWaypoints() {
        return waypointsManager;
    }

    public ConfigUtils getSettingsConfig() {
        return settingsConfig;
    }

    public ConfigUtils getMessagesConfig() {
        return messagesConfig;
    }
}