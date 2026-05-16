package net.danh.sinceGPS.manager;

import net.danh.sinceGPS.SinceGPS;
import net.danh.sinceGPS.core.Node;
import net.danh.sinceGPS.utils.ColorUtils;
import net.danh.sinceGPS.utils.SchedulerAdapter;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class Session {
    private final SinceGPS plugin;
    private final Player player;
    private final Node targetNode;
    private final BossBar bossBar;
    private final Runnable finishCallback;
    private final AtomicBoolean rerouting = new AtomicBoolean(false);
    private final AtomicBoolean cleaned = new AtomicBoolean(false);

    private final double reachDist;
    private final double rerouteDist;
    private final int searchRange;
    private final int arrowLookahead;
    private final double particleYOffset;
    private final double bossBarScaleDistance;

    private final Particle.DustOptions pathColor;
    private final Particle.DustOptions pulseColor;
    private final Particle particleType;
    private final Particle pulseParticleType;
    private final double pulseSpeed;
    private final boolean arrowEnabled;
    private final String arrowFwd;
    private final String arrowLeft;
    private final String arrowRight;
    private final String arrowBack;

    private List<Location> path;
    private SchedulerAdapter.TaskHandle taskHandle = SchedulerAdapter.TaskHandle.NOOP;
    private int pathIndex = 0;
    private double pulseOffset = 0;
    private long lastRerouteCheck = 0;

    public Session(SinceGPS plugin, Player player, List<Location> path, Node targetNode, Runnable finishCallback) {
        this.plugin = plugin;
        this.player = player;
        this.path = List.copyOf(path);
        this.targetNode = targetNode;
        this.finishCallback = finishCallback;

        this.reachDist = plugin.getCfg().getDouble("settings.reach-distance", 3.0);
        this.rerouteDist = plugin.getCfg().getDouble("settings.navigation.reroute-distance", 12.0);
        this.searchRange = plugin.getCfg().getInt("settings.algorithm.smart-search-range", 100);
        this.arrowLookahead = plugin.getCfg().getInt("visuals.navigation.arrow-lookahead", 8);
        this.particleYOffset = plugin.getCfg().getDouble("visuals.navigation.particle-y-offset", 0.5);
        this.bossBarScaleDistance = plugin.getCfg().getDouble("visuals.navigation.bossbar-scale-distance", 50.0);

        this.pulseSpeed = plugin.getCfg().getDouble("visuals.navigation.pulse-speed", 1.5);
        this.arrowEnabled = plugin.getCfg().getBoolean("visuals.navigation.arrow-enabled", true);
        this.pathColor = parseDust("visuals.navigation.path-color", "0,255,255", 1.0f);
        this.pulseColor = parseDust("visuals.navigation.pulse-color", "255,165,0", 1.5f);
        this.particleType = parseParticle(plugin.getCfg().getString("visuals.navigation.path-type", "DUST"), Particle.DUST);
        this.pulseParticleType = parseParticle(plugin.getCfg().getString("visuals.navigation.pulse-type", "DUST"), Particle.DUST);

        this.arrowFwd = plugin.getCfg().getString("action-bar.arrows.forward", "UP");
        this.arrowLeft = plugin.getCfg().getString("action-bar.arrows.left", "LEFT");
        this.arrowRight = plugin.getCfg().getString("action-bar.arrows.right", "RIGHT");
        this.arrowBack = plugin.getCfg().getString("action-bar.arrows.backward", "BACK");

        this.bossBar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        player.showBossBar(bossBar);
    }

    public void start(long periodTicks) {
        taskHandle = plugin.getSchedulerAdapter().runEntityTimer(player, () -> {
            if (update()) finishCallback.run();
        }, 1L, periodTicks);
    }

    public boolean update() {
        if (cleaned.get() || !player.isOnline() || path.isEmpty()) return true;

        Location playerLoc = player.getLocation();
        int max = Math.min(pathIndex + searchRange, path.size());
        double closestDistSq = Double.MAX_VALUE;
        int newIndex = pathIndex;

        for (int i = pathIndex; i < max; i++) {
            Location point = path.get(i);
            if (point.getWorld() == null || playerLoc.getWorld() == null || !point.getWorld().equals(playerLoc.getWorld())) {
                continue;
            }
            double distance = playerLoc.distanceSquared(point);
            if (distance < closestDistSq) {
                closestDistSq = distance;
                newIndex = i;
            }
        }

        if (newIndex > pathIndex) pathIndex = newIndex;

        long rerouteIntervalMs = plugin.getCfg().getInt("settings.navigation.check-interval", 20) * 50L;
        if (System.currentTimeMillis() - lastRerouteCheck > rerouteIntervalMs) {
            if (closestDistSq > rerouteDist * rerouteDist) triggerReroute(playerLoc);
            lastRerouteCheck = System.currentTimeMillis();
        }

        double distToEnd = playerLoc.distance(path.get(path.size() - 1));
        if (distToEnd < reachDist) {
            player.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("arrived")));
            plugin.getCfg().playSound(player, "sounds.arrive");
            return true;
        }

        String title = plugin.getMsg().getString("bossbar-title").replace("<dist>", String.format("%.1f", distToEnd));
        bossBar.name(ColorUtils.parse(title));
        bossBar.progress((float) Math.max(0, Math.min(1, 1 - (distToEnd / (distToEnd + bossBarScaleDistance)))));

        renderParticles(playerLoc);
        renderActionBar(playerLoc);
        return false;
    }

    private void triggerReroute(Location playerLoc) {
        if (!rerouting.compareAndSet(false, true)) return;

        Node startNode = plugin.getGraphManager().getNearestNode(playerLoc);
        if (startNode == null) {
            rerouting.set(false);
            return;
        }

        player.sendActionBar(ColorUtils.parse(plugin.getMsg().getString("rerouting")));
        plugin.getSchedulerAdapter().runAsync(() -> {
            List<Location> newRawPath = PathFinder.findPath(startNode, targetNode, plugin.getGraphManager());
            if (newRawPath == null || newRawPath.isEmpty()) {
                rerouting.set(false);
                return;
            }

            List<Location> smooth = PathFinder.smoothPath(newRawPath,
                    plugin.getCfg().getInt("settings.algorithm.curve-resolution", 8));
            plugin.getSchedulerAdapter().runEntity(player, () -> {
                try {
                    if (!player.isOnline() || cleaned.get()) return;
                    this.path = List.copyOf(plugin.getNav().addLeadIn(player.getLocation(), smooth));
                    this.pathIndex = 0;
                    this.pulseOffset = 0;
                } finally {
                    rerouting.set(false);
                }
            });
        });
    }

    private void renderParticles(Location playerLoc) {
        int view = (int) plugin.getCfg().getDouble("visuals.view-distance", 60.0);
        int end = Math.min(pathIndex + view * 2, path.size());

        for (int i = pathIndex; i < end; i += 2) spawnParticle(path.get(i), particleType, pathColor);

        pulseOffset += pulseSpeed;
        if (pulseOffset > view * 2) pulseOffset = 0;

        int pulseIndex = pathIndex + (int) pulseOffset;
        if (pulseIndex < path.size()) {
            spawnParticle(path.get(pulseIndex), pulseParticleType, pulseColor);
            if (pulseIndex > 0) spawnParticle(path.get(pulseIndex - 1), pulseParticleType, pulseColor);
        }

        if (!arrowEnabled || path.size() <= 1) return;

        int arrowIndex = Math.min(pathIndex + arrowLookahead, path.size() - 1);
        Vector direction = path.get(arrowIndex).toVector().subtract(playerLoc.toVector());
        if (direction.lengthSquared() <= 0.0001) return;

        direction.normalize();
        Location center = playerLoc.clone().add(direction.clone().multiply(2)).add(0, particleYOffset, 0);
        Vector cross = direction.getCrossProduct(new Vector(0, 1, 0));
        if (cross.lengthSquared() <= 0.0001) return;

        cross.normalize().multiply(0.5);
        Vector back = direction.clone().multiply(-0.5);
        spawnParticle(center, pulseParticleType, pulseColor);
        spawnParticle(center.clone().add(back).add(cross), pulseParticleType, pulseColor);
        spawnParticle(center.clone().add(back).subtract(cross), pulseParticleType, pulseColor);
    }

    private void renderActionBar(Location playerLoc) {
        if (!plugin.getCfg().getBoolean("action-bar.enabled")) return;

        Location next = path.get(Math.min(pathIndex + arrowLookahead, path.size() - 1));
        Vector routeDirection = next.toVector().subtract(playerLoc.toVector()).setY(0);
        Vector playerDirection = playerLoc.getDirection().setY(0);
        if (routeDirection.lengthSquared() <= 0.0001 || playerDirection.lengthSquared() <= 0.0001) return;

        routeDirection.normalize();
        playerDirection.normalize();
        double angle = Math.toDegrees(Math.atan2(routeDirection.getZ(), routeDirection.getX())
                - Math.atan2(playerDirection.getZ(), playerDirection.getX()));
        while (angle <= -180) angle += 360;
        while (angle > 180) angle -= 360;

        String arrow;
        if (angle > -45 && angle <= 45) arrow = arrowFwd;
        else if (angle > 45 && angle <= 135) arrow = arrowRight;
        else if (angle > -135 && angle <= -45) arrow = arrowLeft;
        else arrow = arrowBack;

        double distance = playerLoc.distance(path.get(path.size() - 1));
        String message = plugin.getCfg().getString("action-bar.format")
                .replace("<arrow>", arrow)
                .replace("<dist>", String.format("%.1f", distance));
        player.sendActionBar(ColorUtils.parse(message));
    }

    private void spawnParticle(Location location, Particle particle, Particle.DustOptions dust) {
        try {
            Location particleLocation = location.clone().add(0, particleYOffset, 0);
            if (particle == Particle.DUST) {
                player.spawnParticle(particle, particleLocation, 1, 0, 0, 0, 0, dust);
            } else {
                player.spawnParticle(particle, particleLocation, 1, 0, 0, 0, 0);
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid particles from older configs are ignored for this tick.
        }
    }

    private Particle.DustOptions parseDust(String path, String fallback, float size) {
        String[] color = plugin.getCfg().getString(path, fallback).split(",");
        int red = parseColorPart(color, 0);
        int green = parseColorPart(color, 1);
        int blue = parseColorPart(color, 2);
        return new Particle.DustOptions(Color.fromRGB(red, green, blue), size);
    }

    private int parseColorPart(String[] color, int index) {
        if (index >= color.length) return 255;
        try {
            return Math.max(0, Math.min(255, Integer.parseInt(color[index].trim())));
        } catch (NumberFormatException ignored) {
            return 255;
        }
    }

    private Particle parseParticle(String name, Particle fallback) {
        try {
            return Particle.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public void cleanup() {
        if (!cleaned.compareAndSet(false, true)) return;
        taskHandle.cancel();
        player.hideBossBar(bossBar);
    }
}
