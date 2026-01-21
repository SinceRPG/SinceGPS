package net.danh.sinceGPS.manager;

import net.danh.sinceGPS.SinceGPS;
import net.danh.sinceGPS.pathfinding.SmartPathfinder;
import net.danh.sinceGPS.utils.ColorUtils;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NavigationManager {
    private final SinceGPS plugin;
    private final SmartPathfinder pathfinder;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final BukkitTask task;

    public NavigationManager(SinceGPS plugin) {
        this.plugin = plugin;
        this.pathfinder = new SmartPathfinder(plugin);
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 2L);
    }

    public void startStatic(Player p, Location target) {
        start(p, new TargetWrapper(target));
    }

    public void startTracking(Player p, Entity target) {
        start(p, new TargetWrapper(target));
    }

    private void start(Player p, TargetWrapper target) {
        stop(p, false);
        p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesConfig().getString("calculating")));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Location> rawPath = pathfinder.findPath(p.getLocation(), target.getLocation());

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (rawPath == null || rawPath.isEmpty()) {
                    p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesConfig().getString("not-found")));
                    return;
                }

                // [CẢI TIẾN] Làm mịn đường đi
                List<Location> smoothPath = BezierUtil.smooth(rawPath);

                String colorName = plugin.getSettingsConfig().getString("visuals.bossbar-color", "BLUE");
                BossBar.Color color;
                try {
                    color = BossBar.Color.valueOf(colorName);
                } catch (Exception e) {
                    color = BossBar.Color.BLUE;
                }

                BossBar bar = BossBar.bossBar(Component.text("GPS Active"), 1.0f, color, BossBar.Overlay.PROGRESS);
                p.showBossBar(bar);

                sessions.put(p.getUniqueId(), new Session(plugin, p, target, smoothPath, bar));
                p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1, 2);

                double dist = p.getLocation().distance(target.getLocation());
                String msg = plugin.getMessagesConfig().getString("found").replace("<dist>", String.format("%.1f", dist));
                p.sendMessage(ColorUtils.parseWithPrefix(msg));
            });
        });
    }

    public void stop(Player p, boolean notify) {
        Session s = sessions.remove(p.getUniqueId());
        if (s != null) {
            s.cleanup();
            if (notify) p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesConfig().getString("stopped")));
        }
    }

    private void tick() {
        var iterator = sessions.values().iterator();
        while (iterator.hasNext()) {
            Session session = iterator.next();
            if (session.update()) {
                session.cleanup();
                iterator.remove();
            }
        }
    }

    public void shutdown() {
        task.cancel();
        sessions.values().forEach(Session::cleanup);
        sessions.clear();
    }
}