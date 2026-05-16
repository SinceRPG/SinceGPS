package net.danh.sinceGPS;

import net.danh.sinceGPS.command.GPSCommand;
import net.danh.sinceGPS.listeners.RecordListener;
import net.danh.sinceGPS.manager.GraphManager;
import net.danh.sinceGPS.manager.NavigationManager;
import net.danh.sinceGPS.utils.ConfigUtils;
import net.danh.sinceGPS.utils.SchedulerAdapter;
import org.bukkit.plugin.java.JavaPlugin;

public final class SinceGPS extends JavaPlugin {
    private static SinceGPS instance;
    private ConfigUtils messagesConfig;
    private ConfigUtils settingsConfig;
    private SchedulerAdapter scheduler;
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
        this.scheduler = new SchedulerAdapter(this);

        this.graphManager = new GraphManager(this);
        this.navigationManager = new NavigationManager(this);

        new GPSCommand(this).register();
        getServer().getPluginManager().registerEvents(new RecordListener(this), this);
    }

    @Override
    public void onDisable() {
        if (navigationManager != null) navigationManager.shutdown();
        getServer().getAsyncScheduler().cancelTasks(this);
        getServer().getGlobalRegionScheduler().cancelTasks(this);
        if (graphManager != null) graphManager.shutdown();
        instance = null;
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

    public SchedulerAdapter getSchedulerAdapter() {
        return scheduler;
    }

    public ConfigUtils getCfg() {
        return settingsConfig;
    }

    public ConfigUtils getMsg() {
        return messagesConfig;
    }
}
