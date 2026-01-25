package net.danh.sinceGPS.manager;

import net.danh.sinceGPS.SinceGPS;
import net.danh.sinceGPS.core.Node;
import net.danh.sinceGPS.utils.ColorUtils;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NavigationManager {
    private final SinceGPS plugin;
    private final Map<UUID, Session> activeSessions = new ConcurrentHashMap<>();
    private final BukkitTask task;

    public NavigationManager(SinceGPS plugin) {
        this.plugin = plugin;
        long rate = plugin.getCfg().getInt("settings.update-rate", 1);
        this.task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 0L, rate);
    }

    public void startNavigation(Player p, Node target) {
        stopNavigation(p, false);
        Node start = plugin.getGraphManager().getNearestNode(p.getLocation());
        if (start == null) {
            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("path-not-found")));
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Location> path = PathFinder.findPath(start, target, plugin.getGraphManager());
            if (path == null || path.isEmpty()) {
                p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("path-not-found")));
                return;
            }
            int quality = plugin.getCfg().getInt("settings.curve-resolution", 8);
            List<Location> smoothPath = PathFinder.smoothPath(path, quality);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                activeSessions.put(p.getUniqueId(), new Session(plugin, p, smoothPath, target));
                p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("path-found")
                        .replace("<target>", target.getDisplayName())
                        .replace("<distance>", String.format("%.1f", path.get(0).distance(target.getLocation())))));
                plugin.getCfg().playSound(p, "sounds.start");
            });
        });
    }

    public void stopNavigation(Player p, boolean msg) {
        if (activeSessions.remove(p.getUniqueId()) != null && msg) {
            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("stopped")));
            plugin.getCfg().playSound(p, "sounds.stop");
        }
    }

    private void tick() {
        activeSessions.values().removeIf(Session::update);
    }

    public void shutdown() {
        task.cancel();
        activeSessions.values().forEach(Session::cleanup);
        activeSessions.clear();
    }
}