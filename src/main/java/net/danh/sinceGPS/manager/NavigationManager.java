package net.danh.sinceGPS.manager;

import net.danh.sinceGPS.SinceGPS;
import net.danh.sinceGPS.core.Node;
import net.danh.sinceGPS.utils.ColorUtils;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
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
        // Stop session cũ (nếu có) trước khi tính toán mới
        stopNavigation(p, false);

        // Vị trí hiện tại dùng để tìm node gần nhất
        Location initialLoc = p.getLocation();
        Node start = plugin.getGraphManager().getNearestNode(initialLoc);

        if (start == null) {
            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("path-not-found")));
            return;
        }

        // Chạy tính toán đường đi dưới Async để không lag server
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Location> path = PathFinder.findPath(start, target, plugin.getGraphManager());

            if (path == null || path.isEmpty()) {
                p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("path-not-found")));
                return;
            }

            int quality = plugin.getCfg().getInt("settings.algorithm.curve-resolution", 8);
            List<Location> smoothPath = PathFinder.smoothPath(path, quality);

            // Quay về Main Thread để tạo Session và xử lý logic khoảng cách an toàn
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;

                // [TÍNH NĂNG MỚI] Tạo đường dẫn phụ từ chân người chơi đến Node đầu tiên
                Location currentLoc = p.getLocation();
                Location firstPathLoc = smoothPath.get(0);

                // Nếu người chơi đứng cách điểm bắt đầu > 2 block, ta vẽ thêm đường nối
                if (currentLoc.distance(firstPathLoc) > 2.0) {
                    List<Location> leadInPath = new ArrayList<>();

                    // Vector hướng từ người chơi -> điểm bắt đầu
                    Vector direction = firstPathLoc.toVector().subtract(currentLoc.toVector());
                    double distance = direction.length();
                    direction.normalize().multiply(0.5); // Mỗi điểm cách nhau 0.5 block để hạt dày và mượt

                    Location walker = currentLoc.clone();
                    for (double d = 0; d < distance; d += 0.5) {
                        leadInPath.add(walker.clone());
                        walker.add(direction);
                    }

                    // Chèn đường dẫn phụ vào ĐẦU danh sách đường đi chính
                    smoothPath.addAll(0, leadInPath);
                }

                // Khởi tạo Session với đường đi đã được nối dài
                activeSessions.put(p.getUniqueId(), new Session(plugin, p, smoothPath, target));

                p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("path-found")
                        .replace("<target>", target.getDisplayName())
                        .replace("<distance>", String.format("%.1f", path.get(0).distance(target.getLocation())))));
                plugin.getCfg().playSound(p, "sounds.start");
            });
        });
    }

    public void stopNavigation(Player p, boolean msg) {
        Session session = activeSessions.remove(p.getUniqueId());
        if (session != null) {
            session.cleanup();
            if (msg) {
                p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("stopped")));
                plugin.getCfg().playSound(p, "sounds.stop");
            }
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