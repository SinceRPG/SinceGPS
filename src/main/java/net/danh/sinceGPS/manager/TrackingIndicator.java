package net.danh.sinceGPS.manager;

import net.danh.sinceGPS.SinceGPS;
import net.danh.sinceGPS.utils.ColorUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Vector;

public class TrackingIndicator {
    private final SinceGPS plugin;
    private final Player owner;
    private final boolean enabled;
    private final boolean hideFromOthers;
    private final boolean showWhenOnscreen;
    private final String format;
    private final String arrowUp;
    private final String arrowDown;
    private final String arrowLeft;
    private final String arrowRight;
    private final String arrowCenter;
    private final double distanceFromCamera;
    private final double horizontalLimit;
    private final double verticalLimit;
    private final double onscreenHorizontalAngle;
    private final double onscreenVerticalAngle;
    private final int interpolationDuration;
    private final int interpolationDelay;
    private final int visibilityRefreshInterval;

    private TextDisplay display;
    private int visibilityRefreshTicks = 0;

    public TrackingIndicator(SinceGPS plugin, Player owner) {
        this.plugin = plugin;
        this.owner = owner;
        this.enabled = plugin.getCfg().getBoolean("visuals.tracking.enabled", true);
        this.hideFromOthers = plugin.getCfg().getBoolean("visuals.tracking.hide-from-others", true);
        this.showWhenOnscreen = plugin.getCfg().getBoolean("visuals.tracking.show-when-onscreen", false);
        this.format = plugin.getCfg().getString("visuals.tracking.format", "<gold><bold><arrow></bold> <white><dist>m");
        this.arrowUp = plugin.getCfg().getString("visuals.tracking.arrows.up", "^");
        this.arrowDown = plugin.getCfg().getString("visuals.tracking.arrows.down", "v");
        this.arrowLeft = plugin.getCfg().getString("visuals.tracking.arrows.left", "<");
        this.arrowRight = plugin.getCfg().getString("visuals.tracking.arrows.right", ">");
        this.arrowCenter = plugin.getCfg().getString("visuals.tracking.arrows.center", "*");
        this.distanceFromCamera = plugin.getCfg().getDouble("visuals.tracking.distance-from-camera", 2.2);
        this.horizontalLimit = plugin.getCfg().getDouble("visuals.tracking.horizontal-limit", 1.15);
        this.verticalLimit = plugin.getCfg().getDouble("visuals.tracking.vertical-limit", 0.62);
        this.onscreenHorizontalAngle = plugin.getCfg().getDouble("visuals.tracking.onscreen-horizontal-angle", 38.0);
        this.onscreenVerticalAngle = plugin.getCfg().getDouble("visuals.tracking.onscreen-vertical-angle", 24.0);
        this.interpolationDuration = plugin.getCfg().getInt("visuals.tracking.interpolation.duration", 1);
        this.interpolationDelay = plugin.getCfg().getInt("visuals.tracking.interpolation.delay", 0);
        this.visibilityRefreshInterval = Math.max(1, plugin.getCfg().getInt("visuals.tracking.visibility-refresh-interval", 20));
    }

    public void update(Location playerLocation, Location target, double distanceToTarget) {
        if (!enabled || !sameWorld(playerLocation, target)) {
            cleanup();
            return;
        }

        Location eye = owner.getEyeLocation();
        Vector forward = eye.getDirection();
        if (forward.lengthSquared() <= 0.0001) return;
        forward.normalize();

        Vector toTarget = target.toVector().subtract(eye.toVector());
        if (toTarget.lengthSquared() <= 0.0001) {
            cleanup();
            return;
        }
        toTarget.normalize();

        double yawAngle = signedHorizontalAngle(forward, toTarget);
        double pitchAngle = signedVerticalAngle(forward, toTarget);
        boolean onscreen = Math.abs(yawAngle) <= onscreenHorizontalAngle
                && Math.abs(pitchAngle) <= onscreenVerticalAngle
                && forward.dot(toTarget) > 0;

        if (onscreen && !showWhenOnscreen) {
            cleanup();
            return;
        }

        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        if (right.lengthSquared() <= 0.0001) right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = right.clone().crossProduct(forward).normalize();

        double x = clamp(yawAngle / onscreenHorizontalAngle, -1.0, 1.0) * horizontalLimit;
        double y = clamp(pitchAngle / onscreenVerticalAngle, -1.0, 1.0) * verticalLimit;

        if (!onscreen) {
            double edge = Math.max(Math.abs(x) / horizontalLimit, Math.abs(y) / verticalLimit);
            if (edge > 0.0001) {
                x /= edge;
                y /= edge;
            }
        }

        Location indicatorLocation = eye.clone()
                .add(forward.clone().multiply(distanceFromCamera))
                .add(right.multiply(x))
                .add(up.multiply(y));

        ensureDisplay(indicatorLocation);
        display.teleport(indicatorLocation);
        display.text(ColorUtils.parse(format
                .replace("<arrow>", chooseArrow(x, y, onscreen))
                .replace("<dist>", String.format("%.1f", distanceToTarget))));
        refreshVisibility();
    }

    private double signedHorizontalAngle(Vector forward, Vector target) {
        Vector flatForward = forward.clone().setY(0);
        Vector flatTarget = target.clone().setY(0);
        if (flatForward.lengthSquared() <= 0.0001 || flatTarget.lengthSquared() <= 0.0001) return 0.0;
        flatForward.normalize();
        flatTarget.normalize();
        double angle = Math.toDegrees(Math.atan2(flatTarget.getZ(), flatTarget.getX())
                - Math.atan2(flatForward.getZ(), flatForward.getX()));
        while (angle <= -180) angle += 360;
        while (angle > 180) angle -= 360;
        return angle;
    }

    private double signedVerticalAngle(Vector forward, Vector target) {
        double forwardPitch = Math.toDegrees(Math.asin(clamp(forward.getY(), -1.0, 1.0)));
        double targetPitch = Math.toDegrees(Math.asin(clamp(target.getY(), -1.0, 1.0)));
        return targetPitch - forwardPitch;
    }

    private String chooseArrow(double x, double y, boolean onscreen) {
        if (onscreen) return arrowCenter;
        if (Math.abs(x) > Math.abs(y)) return x < 0 ? arrowLeft : arrowRight;
        return y < 0 ? arrowDown : arrowUp;
    }

    private void ensureDisplay(Location location) {
        if (display != null && !display.isDead()) return;
        display = location.getWorld().spawn(location, TextDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setSeeThrough(plugin.getCfg().getBoolean("visuals.tracking.see-through", true));
            entity.setShadowed(plugin.getCfg().getBoolean("visuals.tracking.shadow", true));
            entity.setInterpolationDuration(interpolationDuration);
            entity.setInterpolationDelay(interpolationDelay);
        });
        applyVisibility(display);
    }

    private void applyVisibility(Entity entity) {
        if (!hideFromOthers) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getUniqueId().equals(owner.getUniqueId())) {
                player.hideEntity(plugin, entity);
            }
        }
    }

    private void refreshVisibility() {
        if (!hideFromOthers || ++visibilityRefreshTicks < visibilityRefreshInterval) return;
        visibilityRefreshTicks = 0;
        if (display != null) applyVisibility(display);
    }

    private boolean sameWorld(Location first, Location second) {
        return first.getWorld() != null && first.getWorld().equals(second.getWorld());
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public void cleanup() {
        if (display != null) {
            display.remove();
            display = null;
        }
    }
}
