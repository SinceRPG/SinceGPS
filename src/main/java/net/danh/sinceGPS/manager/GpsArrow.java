package net.danh.sinceGPS.manager;

import net.danh.sinceGPS.SinceGPS;
import net.danh.sinceGPS.utils.ColorUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public class GpsArrow {
    private final SinceGPS plugin;
    private final Player owner;
    private final List<BlockDisplay> blocks = new ArrayList<>();
    private TextDisplay header;

    private final boolean enabled;
    private final boolean hideFromOthers;
    private final boolean headerEnabled;
    private final String headerFormat;
    private final BlockData blockData;
    private final double distanceFromPlayer;
    private final double verticalOffset;
    private final double blockScale;
    private final double spacing;
    private final int shaftLength;
    private final int shaftWidth;
    private final int headLength;
    private final int headWidth;
    private final int interpolationDuration;
    private final int interpolationDelay;
    private final int visibilityRefreshInterval;
    private final double headerUp;
    private final List<Vector> shape;
    private int visibilityRefreshTicks = 0;

    public GpsArrow(SinceGPS plugin, Player owner) {
        this.plugin = plugin;
        this.owner = owner;
        this.enabled = plugin.getCfg().getBoolean("visuals.arrow.enabled", true);
        this.hideFromOthers = plugin.getCfg().getBoolean("visuals.arrow.hide-from-others", true);
        this.headerEnabled = plugin.getCfg().getBoolean("visuals.arrow.header.enabled", true);
        this.headerFormat = plugin.getCfg().getString("visuals.arrow.header.format", "<green><dist> blocks to destination");
        this.blockData = parseBlockData(plugin.getCfg().getString("visuals.arrow.block", "QUARTZ_BLOCK"));
        this.distanceFromPlayer = plugin.getCfg().getDouble("visuals.arrow.position.forward", 4.0);
        this.verticalOffset = plugin.getCfg().getDouble("visuals.arrow.position.up", 2.6);
        this.blockScale = plugin.getCfg().getDouble("visuals.arrow.block-scale", 0.55);
        this.spacing = plugin.getCfg().getDouble("visuals.arrow.spacing", 0.42);
        this.shaftLength = plugin.getCfg().getInt("visuals.arrow.shaft.length", 5);
        this.shaftWidth = plugin.getCfg().getInt("visuals.arrow.shaft.width", 1);
        this.headLength = plugin.getCfg().getInt("visuals.arrow.head.length", 2);
        this.headWidth = plugin.getCfg().getInt("visuals.arrow.head.width", 5);
        this.interpolationDuration = plugin.getCfg().getInt("visuals.arrow.interpolation.duration", 1);
        this.interpolationDelay = plugin.getCfg().getInt("visuals.arrow.interpolation.delay", 0);
        this.visibilityRefreshInterval = Math.max(1, plugin.getCfg().getInt("visuals.arrow.visibility-refresh-interval", 20));
        this.headerUp = plugin.getCfg().getDouble("visuals.arrow.header.up", 0.9);
        this.shape = buildShape();
    }

    public void update(Location playerLocation, Location destination, double distanceToDestination) {
        if (!enabled || playerLocation.getWorld() == null || destination.getWorld() == null
                || !playerLocation.getWorld().equals(destination.getWorld())) {
            cleanup();
            return;
        }

        Vector lookDirection = playerLocation.getDirection();
        if (lookDirection.lengthSquared() <= 0.0001) return;
        lookDirection.normalize();
        NavigationManager.ArrowOffset offset = plugin.getNav().getArrowOffset(owner);

        Location anchor = playerLocation.clone()
                .add(lookDirection.clone().multiply(distanceFromPlayer + offset.forward()))
                .add(0, verticalOffset + offset.up(), 0);

        Vector routeDirection = destination.toVector().subtract(playerLocation.toVector()).setY(0);
        if (routeDirection.lengthSquared() <= 0.0001) routeDirection = lookDirection.clone().setY(0);
        if (routeDirection.lengthSquared() <= 0.0001) return;
        routeDirection.normalize();

        Vector sideDirection = new Vector(-routeDirection.getZ(), 0, routeDirection.getX()).normalize();
        ensureEntities(anchor, shape.size());

        for (int i = 0; i < shape.size(); i++) {
            Vector local = shape.get(i);
            Location blockLocation = anchor.clone()
                    .add(routeDirection.clone().multiply(local.getZ() * spacing))
                    .add(sideDirection.clone().multiply(local.getX() * spacing))
                    .add(0, local.getY() * spacing, 0);
            updateBlock(blocks.get(i), blockLocation);
        }

        updateHeader(anchor, distanceToDestination);
        refreshVisibility();
    }

    private List<Vector> buildShape() {
        List<Vector> shape = new ArrayList<>();
        int safeShaftLength = Math.max(1, shaftLength);
        int safeShaftWidth = Math.max(1, shaftWidth);
        int safeHeadLength = Math.max(1, headLength);
        int safeHeadWidth = Math.max(safeShaftWidth + 2, headWidth);

        for (int z = -safeShaftLength; z < 0; z++) {
            addWidth(shape, z, safeShaftWidth);
        }

        for (int z = 0; z < safeHeadLength; z++) {
            int width = Math.max(1, safeHeadWidth - z * 2);
            addWidth(shape, z, width);
        }

        return shape;
    }

    private void addWidth(List<Vector> shape, int z, int width) {
        int half = width / 2;
        for (int x = -half; x <= half; x++) {
            shape.add(new Vector(x, 0, z));
        }
    }

    private void ensureEntities(Location anchor, int required) {
        while (blocks.size() < required) {
            BlockDisplay display = anchor.getWorld().spawn(anchor, BlockDisplay.class, entity -> {
                entity.setBlock(blockData);
                entity.setPersistent(false);
                entity.setInterpolationDuration(interpolationDuration);
                entity.setInterpolationDelay(interpolationDelay);
                entity.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                        new Vector3f((float) blockScale, (float) blockScale, (float) blockScale), new AxisAngle4f()));
                entity.setBillboard(Display.Billboard.FIXED);
            });
            blocks.add(display);
            applyVisibility(display);
        }

        while (blocks.size() > required) {
            Entity extra = blocks.remove(blocks.size() - 1);
            extra.remove();
        }
    }

    private void updateBlock(BlockDisplay display, Location location) {
        display.teleport(location);
    }

    private void updateHeader(Location anchor, double distanceToDestination) {
        if (!headerEnabled) {
            if (header != null) {
                header.remove();
                header = null;
            }
            return;
        }

        Location headerLocation = anchor.clone().add(0, headerUp, 0);
        if (header == null || header.isDead()) {
            header = headerLocation.getWorld().spawn(headerLocation, TextDisplay.class, entity -> {
                entity.setPersistent(false);
                entity.setBillboard(Display.Billboard.CENTER);
                entity.setAlignment(TextDisplay.TextAlignment.CENTER);
                entity.setSeeThrough(plugin.getCfg().getBoolean("visuals.arrow.header.see-through", true));
                entity.setShadowed(plugin.getCfg().getBoolean("visuals.arrow.header.shadow", true));
                entity.setInterpolationDuration(interpolationDuration);
                entity.setInterpolationDelay(interpolationDelay);
            });
            applyVisibility(header);
        }

        header.teleport(headerLocation);
        header.text(ColorUtils.parse(headerFormat.replace("<dist>", String.format("%.1f", distanceToDestination))));
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
        for (BlockDisplay display : blocks) {
            applyVisibility(display);
        }
        if (header != null) applyVisibility(header);
    }

    private BlockData parseBlockData(String value) {
        try {
            return Bukkit.createBlockData(value);
        } catch (IllegalArgumentException ignored) {
            Material material = Material.matchMaterial(value);
            if (material != null && material.isBlock()) return material.createBlockData();
            return Material.QUARTZ_BLOCK.createBlockData();
        }
    }

    public void cleanup() {
        for (BlockDisplay display : blocks) {
            display.remove();
        }
        blocks.clear();
        if (header != null) {
            header.remove();
            header = null;
        }
    }
}
