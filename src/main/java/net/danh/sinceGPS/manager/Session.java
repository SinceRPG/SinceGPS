package net.danh.sinceGPS.manager;

import net.danh.sinceGPS.SinceGPS;
import net.danh.sinceGPS.utils.ColorUtils;
import net.danh.sinceGPS.utils.FormulaUtils;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;

public class Session {
    private final SinceGPS plugin;
    private final Player p;
    private final TargetWrapper target;
    private final BossBar bossBar;
    private List<Location> path;
    private int index = 0;
    private int animTick = 0;
    private double arrivalDist;

    public Session(SinceGPS plugin, Player p, TargetWrapper target, List<Location> path, BossBar bossBar) {
        this.plugin = plugin;
        this.p = p;
        this.target = target;
        this.path = path;
        this.bossBar = bossBar;

        String formula = plugin.getSettingsConfig().getString("settings.arrival-formula", "2.0");
        try {
            this.arrivalDist = FormulaUtils.eval(formula);
        } catch (Exception e) {
            this.arrivalDist = 2.0;
        }
    }

    public boolean update() {
        if (!p.isOnline() || !target.isValid() || !p.getWorld().equals(target.getLocation().getWorld())) return true;

        Location pLoc = p.getLocation();
        Location tLoc = target.getLocation();
        double dist = pLoc.distance(tLoc);

        bossBar.name(ColorUtils.parse("<bold>Mục tiêu:</bold> <yellow>" + String.format("%.1f", dist) + "m"));
        bossBar.progress((float) Math.max(0.0, Math.min(1.0, 1.0 - (dist / 100.0))));

        if (dist < arrivalDist) {
            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesConfig().getString("arrived")));
            p.playSound(pLoc, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1, 1);
            cleanup();
            return true;
        }

        if (target.isDynamic()) {
            Location endOfPath = path.get(path.size() - 1);
            if (endOfPath.distance(tLoc) > plugin.getSettingsConfig().getDouble("settings.dynamic-update-threshold", 3.0)) {
                cleanup();
                plugin.getNav().startTracking(p, target.getEntity());
                return true;
            }
        }

        updateIndex(pLoc);

        Location currentTargetPoint = path.get(Math.min(index, path.size() - 1));
        if (path.isEmpty() || pLoc.distance(currentTargetPoint) > 15.0) {
            cleanup();
            if (target.isDynamic()) plugin.getNav().startTracking(p, target.getEntity());
            else plugin.getNav().startStatic(p, tLoc);
            return true;
        }

        drawParticles(pLoc);
        return false;
    }

    private void updateIndex(Location pLoc) {
        double closest = Double.MAX_VALUE;
        int maxSearch = Math.min(index + 20, path.size());
        for (int i = index; i < maxSearch; i++) {
            double d = pLoc.distance(path.get(i));
            if (d < closest) {
                closest = d;
                // Nếu < 4 block thì chuyển sang điểm tiếp theo
                if (d < 4.0 && i >= index) index = i;
            }
        }
    }

    private void drawParticles(Location pLoc) {
        animTick++;
        double view = plugin.getSettingsConfig().getDouble("visuals.view-distance", 25.0);

        String pName = plugin.getSettingsConfig().getString("visuals.particle", "HAPPY_VILLAGER");
        Particle particle;
        try {
            particle = Particle.valueOf(pName);
        } catch (Exception e) {
            particle = Particle.HAPPY_VILLAGER;
        }

        double gap = plugin.getSettingsConfig().getDouble("visuals.particle-gap", 0.25);

        // [CẢI TIẾN] Hiệu ứng dòng chảy: Offset chạy từ 0 -> 1 liên tục
        double offset = (animTick % 10) / 10.0;

        for (int i = index; i < path.size() - 1; i++) {
            Location p1 = path.get(i);
            if (p1.distance(pLoc) > view) break;

            Location p2 = path.get(i + 1);
            Vector dir = p2.toVector().subtract(p1.toVector());
            double len = dir.length();
            dir.normalize();

            // Vẽ hạt chạy dọc theo vector
            for (double d = offset * gap; d < len; d += gap) {
                p.spawnParticle(particle, p1.clone().add(dir.clone().multiply(d)), 1, 0, 0, 0, 0);
            }
        }
    }

    public void cleanup() {
        p.hideBossBar(bossBar);
    }
}