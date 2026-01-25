package net.danh.sinceGPS.listeners;

import net.danh.sinceGPS.SinceGPS;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public class RecordListener implements Listener {
    private final SinceGPS plugin;

    public RecordListener(SinceGPS plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getBlockX() == e.getTo().getBlockX() &&
                e.getFrom().getBlockY() == e.getTo().getBlockY() &&
                e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;

        if (plugin.getGraphManager().isRecording(e.getPlayer())) {
            plugin.getGraphManager().handleMoveRecord(e.getPlayer());
        }
    }
}