package net.danh.sinceGPS.manager;

import net.danh.sinceGPS.SinceGPS;
import net.danh.sinceGPS.core.Node;
import net.danh.sinceGPS.utils.ColorUtils;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

public class Session {
    private final SinceGPS plugin;
    private final Player player;
    private final Node targetNode;
    private final BossBar bossBar;

    // Configs
    private final double reachDist;
    private final double rerouteDist;
    private final int searchRange;
    private final int arrowLookahead;

    private final Particle.DustOptions pathColor;
    private final Particle.DustOptions pulseColor;
    private final Particle particleType;
    private final double pulseSpeed;
    private final boolean arrowEnabled;
    private final String arrowFwd, arrowLeft, arrowRight, arrowBack;

    private List<Location> path;
    private int pathIndex = 0;
    private double pulseOffset = 0;
    private long lastRerouteCheck = 0;

    public Session(SinceGPS plugin, Player player, List<Location> path, Node targetNode) {
        this.plugin = plugin;
        this.player = player;
        this.path = path;
        this.targetNode = targetNode;

        // Load Settings
        this.reachDist = plugin.getCfg().getDouble("settings.reach-distance", 3.0);
        this.rerouteDist = plugin.getCfg().getDouble("settings.navigation.reroute-distance", 12.0);
        this.searchRange = plugin.getCfg().getInt("settings.algorithm.smart-search-range", 100);
        this.arrowLookahead = plugin.getCfg().getInt("visuals.navigation.arrow-lookahead", 8);

        this.pulseSpeed = plugin.getCfg().getDouble("visuals.navigation.pulse-speed", 1.5);
        this.arrowEnabled = plugin.getCfg().getBoolean("visuals.navigation.arrow-enabled", true);

        String[] pc = plugin.getCfg().getString("visuals.navigation.path-color", "0,255,255").split(",");
        String[] uc = plugin.getCfg().getString("visuals.navigation.pulse-color", "255,165,0").split(",");
        this.pathColor = new Particle.DustOptions(Color.fromRGB(Integer.parseInt(pc[0].trim()), Integer.parseInt(pc[1].trim()), Integer.parseInt(pc[2].trim())), 1f);
        this.pulseColor = new Particle.DustOptions(Color.fromRGB(Integer.parseInt(uc[0].trim()), Integer.parseInt(uc[1].trim()), Integer.parseInt(uc[2].trim())), 1.5f);

        Particle type;
        try {
            type = Particle.valueOf(plugin.getCfg().getString("visuals.navigation.path-type", "DUST"));
        } catch (Exception e) {
            type = Particle.DUST;
        }
        this.particleType = type;

        this.arrowFwd = plugin.getCfg().getString("action-bar.arrows.forward", "⬆");
        this.arrowLeft = plugin.getCfg().getString("action-bar.arrows.left", "⬅");
        this.arrowRight = plugin.getCfg().getString("action-bar.arrows.right", "➡");
        this.arrowBack = plugin.getCfg().getString("action-bar.arrows.backward", "⬇");

        this.bossBar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        player.showBossBar(bossBar);
    }

    public boolean update() {
        if (!player.isOnline()) return true;
        Location pLoc = player.getLocation();

        // Smart Indexing: Tìm điểm gần nhất trên đường đi để cập nhật tiến độ
        int max = Math.min(pathIndex + searchRange, path.size());
        double closestDistSq = Double.MAX_VALUE;
        int newIndex = pathIndex;

        for (int i = pathIndex; i < max; i++) {
            double d = pLoc.distanceSquared(path.get(i));
            if (d < closestDistSq) {
                closestDistSq = d;
                newIndex = i;
            }
        }

        // Chỉ cập nhật index nếu tiến lên phía trước (tránh đi lùi làm nhảy index)
        if (newIndex > pathIndex) pathIndex = newIndex;

        // Auto Reroute Check
        if (System.currentTimeMillis() - lastRerouteCheck > (plugin.getCfg().getInt("settings.navigation.check-interval") * 50L)) {
            // Nếu khoảng cách từ người chơi đến điểm gần nhất > giới hạn cho phép -> LẠC ĐƯỜNG
            if (closestDistSq > (rerouteDist * rerouteDist)) {
                triggerReroute(pLoc);
            }
            lastRerouteCheck = System.currentTimeMillis();
        }

        // Check Arrive
        double distToEnd = pLoc.distance(path.get(path.size() - 1));
        if (distToEnd < reachDist) {
            player.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("arrived")));
            plugin.getCfg().playSound(player, "sounds.arrive");
            cleanup();
            return true;
        }

        // Visuals
        String title = plugin.getMsg().getString("bossbar-title").replace("<dist>", String.format("%.1f", distToEnd));
        bossBar.name(ColorUtils.parse(title));
        bossBar.progress((float) Math.max(0, Math.min(1, 1 - (distToEnd / (distToEnd + 50)))));

        renderParticles(pLoc);
        renderActionBar(pLoc);
        return false;
    }

    // --- LOGIC TÍNH LẠI ĐƯỜNG (CÓ LEAD-IN TỪ CHÂN) ---
    private void triggerReroute(Location pLoc) {
        Node startNode = plugin.getGraphManager().getNearestNode(pLoc);

        if (startNode != null) {
            player.sendActionBar(ColorUtils.parse(plugin.getMsg().getString("rerouting")));

            // Chạy async tìm đường
            plugin.getServer().getScheduler().runTaskAsynchronously(SinceGPS.inst(), () -> {
                List<Location> newRawPath = PathFinder.findPath(startNode, targetNode, plugin.getGraphManager());

                if (newRawPath != null && !newRawPath.isEmpty()) {
                    // Làm mượt đường đi
                    List<Location> smooth = PathFinder.smoothPath(newRawPath, plugin.getCfg().getInt("settings.algorithm.curve-resolution", 8));

                    // Quay lại Main Thread để cập nhật
                    plugin.getServer().getScheduler().runTask(SinceGPS.inst(), () -> {
                        if (!player.isOnline()) return;

                        // [NEW] Logic Nối dây từ chân người chơi vào đường mới
                        Location currentPos = player.getLocation();
                        Location pathStart = smooth.get(0);

                        // Nếu khoảng cách > 2 block, tạo các điểm trung gian
                        if (currentPos.distance(pathStart) > 2.0) {
                            List<Location> leadIn = new ArrayList<>();
                            Vector dir = pathStart.toVector().subtract(currentPos.toVector());
                            double dist = dir.length();
                            dir.normalize().multiply(0.5); // Mật độ 0.5 block/điểm

                            Location walker = currentPos.clone();
                            for (double d = 0; d < dist; d += 0.5) {
                                leadIn.add(walker.clone());
                                walker.add(dir);
                            }
                            // Thêm vào đầu danh sách
                            smooth.addAll(0, leadIn);
                        }

                        // Cập nhật đường đi mới cho session
                        this.path = smooth;
                        this.pathIndex = 0; // Reset index về 0 để bắt đầu từ chân người chơi
                    });
                }
            });
        }
    }

    private void renderParticles(Location pLoc) {
        int view = (int) plugin.getCfg().getDouble("visuals.view-distance", 60.0);
        int end = Math.min(pathIndex + view * 2, path.size());

        // Vẽ đường tĩnh
        for (int i = pathIndex; i < end; i += 2) spawnDust(path.get(i), pathColor);

        // Vẽ xung nhịp (Pulse)
        pulseOffset += pulseSpeed;
        if (pulseOffset > view * 2) pulseOffset = 0;

        int pIdx = pathIndex + (int) pulseOffset;
        if (pIdx < path.size()) {
            spawnDust(path.get(pIdx), pulseColor);
            if (pIdx > 0) spawnDust(path.get(pIdx - 1), pulseColor);
        }

        // Vẽ mũi tên 3D
        if (arrowEnabled) {
            int arrowIdx = Math.min(pathIndex + arrowLookahead, path.size() - 1);
            Location target = path.get(arrowIdx);
            Vector dir = target.toVector().subtract(pLoc.toVector()).normalize();
            Location center = pLoc.clone().add(dir.clone().multiply(2)).add(0, 0.5, 0);

            Vector cross = dir.getCrossProduct(new Vector(0, 1, 0)).normalize().multiply(0.5);
            Vector back = dir.clone().multiply(-0.5);

            spawnDust(center, pulseColor);
            spawnDust(center.clone().add(back).add(cross), pulseColor);
            spawnDust(center.clone().add(back).subtract(cross), pulseColor);
        }
    }

    private void renderActionBar(Location pLoc) {
        if (!plugin.getCfg().getBoolean("action-bar.enabled")) return;
        Location next = path.get(Math.min(pathIndex + arrowLookahead, path.size() - 1));
        Vector dir = next.toVector().subtract(pLoc.toVector()).normalize();
        Vector pDir = pLoc.getDirection().setY(0).normalize();
        double angle = Math.toDegrees(Math.atan2(dir.getZ(), dir.getX()) - Math.atan2(pDir.getZ(), pDir.getX()));
        while (angle <= -180) angle += 360;
        while (angle > 180) angle -= 360;

        String arrow;
        if (angle > -45 && angle <= 45) arrow = arrowFwd;
        else if (angle > 45 && angle <= 135) arrow = arrowRight;
        else if (angle > -135 && angle <= -45) arrow = arrowLeft;
        else arrow = arrowBack;

        double dist = pLoc.distance(path.get(path.size() - 1));
        String msg = plugin.getCfg().getString("action-bar.format").replace("<arrow>", arrow).replace("<dist>", String.format("%.1f", dist));
        player.sendActionBar(ColorUtils.parse(msg));
    }

    private void spawnDust(Location loc, Particle.DustOptions dust) {
        try {
            if (particleType == Particle.DUST) {
                player.spawnParticle(particleType, loc.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0, dust);
            } else {
                player.spawnParticle(particleType, loc.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0);
            }
        } catch (Exception ignored) {
        }
    }

    public void cleanup() {
        player.hideBossBar(bossBar);
    }
}