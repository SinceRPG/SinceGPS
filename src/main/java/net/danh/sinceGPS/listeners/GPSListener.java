package net.danh.sinceGPS.listeners;

import net.danh.sinceGPS.SinceGPS;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class GPSListener implements Listener {
    private final SinceGPS plugin;

    public GPSListener(SinceGPS plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.getNav().stop(e.getPlayer(), false);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        plugin.getNav().stop(e.getPlayer(), false);
    }
}