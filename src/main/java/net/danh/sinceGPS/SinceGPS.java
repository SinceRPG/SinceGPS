package net.danh.sinceGPS;

import net.danh.sinceGPS.command.GPSCommand;
import net.danh.sinceGPS.listeners.EditorListener;
import net.danh.sinceGPS.manager.GraphManager;
import net.danh.sinceGPS.manager.NavigationManager;
import net.danh.sinceGPS.utils.ConfigUtils;
import org.bukkit.plugin.java.JavaPlugin;

public final class SinceGPS extends JavaPlugin {
    private static SinceGPS instance;
    private ConfigUtils messagesConfig;
    private ConfigUtils settingsConfig;
    private GraphManager graphManager;
    private NavigationManager navigationManager;

    public static SinceGPS inst() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        this.settingsConfig = new ConfigUtils(this, "config.yml");
        this.messagesConfig = new ConfigUtils(this, "messages.yml");

        // Khởi tạo theo thứ tự
        this.graphManager = new GraphManager(this);
        this.navigationManager = new NavigationManager(this);

        new GPSCommand(this).register();
        getServer().getPluginManager().registerEvents(new EditorListener(this), this);

        getLogger().info("SinceGPS v5.0 (Enterprise) Enabled!");
    }

    @Override
    public void onDisable() {
        if (navigationManager != null) navigationManager.shutdown();
        if (graphManager != null) graphManager.shutdown();
    }

    public void reloadPlugin() {
        settingsConfig.reload();
        messagesConfig.reload();
        graphManager.load();
    }

    public GraphManager getGraphManager() {
        return graphManager;
    }

    public NavigationManager getNav() {
        return navigationManager;
    }

    public ConfigUtils getCfg() {
        return settingsConfig;
    }

    public ConfigUtils getMsg() {
        return messagesConfig;
    }
}