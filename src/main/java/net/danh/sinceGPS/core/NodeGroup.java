package net.danh.sinceGPS.core;

import org.bukkit.entity.Player;

public class NodeGroup {
    private final String name;
    private final String permission;
    private final boolean discoverable;
    private final boolean navigable;

    public NodeGroup(String name, String permission, boolean discoverable, boolean navigable) {
        this.name = name;
        this.permission = permission;
        this.discoverable = discoverable;
        this.navigable = navigable;
    }

    public boolean canAccess(Player p) {
        if (!navigable) return false;
        if (permission == null || permission.isEmpty()) return true;
        return p.hasPermission(permission);
    }

    public boolean hasNoPermission() {
        return navigable && (permission == null || permission.isEmpty());
    }
}
