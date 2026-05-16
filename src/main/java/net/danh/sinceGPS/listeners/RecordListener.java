package net.danh.sinceGPS.listeners;

import net.danh.sinceGPS.SinceGPS;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

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

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (!plugin.getNav().isMoveMode(event.getPlayer())) return;
        int delta = event.getNewSlot() - event.getPreviousSlot();
        if (delta > 4) delta -= 9;
        if (delta < -4) delta += 9;
        plugin.getNav().adjustArrowOffset(event.getPlayer(), delta);
        event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getNav().stopNavigation(event.getPlayer(), false);
        plugin.getNav().clearPlayerState(event.getPlayer().getUniqueId());
        plugin.getGraphManager().stopRecording(event.getPlayer(), false);
    }
}
