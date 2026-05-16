package net.danh.sinceGPS.manager;

import net.danh.sinceGPS.SinceGPS;
import net.danh.sinceGPS.core.Node;
import net.danh.sinceGPS.utils.ColorUtils;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class NavigationManager {
    private final SinceGPS plugin;
    private final Map<UUID, Session> activeSessions = new ConcurrentHashMap<>();

    public NavigationManager(SinceGPS plugin) {
        this.plugin = plugin;
    }

    public void startNavigation(Player player, Node target) {
        stopNavigation(player, false);

        Location initialLoc = player.getLocation();
        Node start = plugin.getGraphManager().getNearestNode(initialLoc);
        if (start == null) {
            player.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("path-not-found")));
            return;
        }

        plugin.getSchedulerAdapter().runAsync(() -> {
            List<Location> path = PathFinder.findPath(start, target, plugin.getGraphManager());
            if (path == null || path.isEmpty()) {
                plugin.getSchedulerAdapter().runEntity(player,
                        () -> player.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("path-not-found"))));
                return;
            }

            int quality = plugin.getCfg().getInt("settings.algorithm.curve-resolution", 8);
            List<Location> smoothPath = PathFinder.smoothPath(path, quality);
            double totalDistance = PathFinder.estimateDistance(path);

            plugin.getSchedulerAdapter().runEntity(player, () -> {
                if (!player.isOnline()) return;

                List<Location> sessionPath = addLeadIn(player.getLocation(), smoothPath);
                UUID playerId = player.getUniqueId();
                AtomicReference<Session> sessionRef = new AtomicReference<>();
                Session session = new Session(plugin, player, sessionPath, target,
                        () -> finishNavigation(playerId, sessionRef.get()));
                sessionRef.set(session);

                activeSessions.put(playerId, session);
                session.start(plugin.getCfg().getInt("settings.update-rate", 1));

                player.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("path-found")
                        .replace("<target>", target.getDisplayName())
                        .replace("<distance>", String.format("%.1f", totalDistance))));
                plugin.getCfg().playSound(player, "sounds.start");
            });
        });
    }

    public void stopNavigation(Player player, boolean message) {
        Session session = activeSessions.remove(player.getUniqueId());
        if (session == null) return;

        session.cleanup();
        if (message) {
            player.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("stopped")));
            plugin.getCfg().playSound(player, "sounds.stop");
        }
    }

    public List<Location> addLeadIn(Location currentLoc, List<Location> route) {
        List<Location> result = new ArrayList<>(route);
        if (result.isEmpty()) return result;

        Location firstPathLoc = result.get(0);
        if (currentLoc.getWorld() == null || firstPathLoc.getWorld() == null
                || !currentLoc.getWorld().equals(firstPathLoc.getWorld())) {
            return result;
        }

        double minDistance = plugin.getCfg().getDouble("settings.navigation.lead-in-min-distance", 2.0);
        if (currentLoc.distance(firstPathLoc) <= minDistance) return result;

        double step = Math.max(0.1, plugin.getCfg().getDouble("settings.navigation.lead-in-step", 0.5));
        Vector direction = firstPathLoc.toVector().subtract(currentLoc.toVector());
        double distance = direction.length();
        if (distance <= 0.0) return result;

        direction.normalize().multiply(step);
        List<Location> leadInPath = new ArrayList<>();
        Location walker = currentLoc.clone();
        for (double d = 0; d < distance; d += step) {
            leadInPath.add(walker.clone());
            walker.add(direction);
        }

        result.addAll(0, leadInPath);
        return result;
    }

    private void finishNavigation(UUID playerId, Session session) {
        if (session != null && activeSessions.remove(playerId, session)) {
            session.cleanup();
        }
    }

    public void shutdown() {
        activeSessions.values().forEach(Session::cleanup);
        activeSessions.clear();
    }
}
