package net.danh.sinceGPS.manager;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

public class TargetWrapper {
    private final Location staticLoc;
    private final Entity entity;

    public TargetWrapper(Location loc) {
        this.staticLoc = loc;
        this.entity = null;
    }

    public TargetWrapper(Entity entity) {
        this.entity = entity;
        this.staticLoc = null;
    }

    public Location getLocation() {
        return entity != null ? entity.getLocation() : staticLoc;
    }

    public boolean isDynamic() {
        return entity != null;
    }

    public Entity getEntity() {
        return entity;
    }

    public boolean isValid() {
        if (entity != null) return entity.isValid();
        return true;
    }
}