package net.danh.sinceGPS.core;

import org.bukkit.entity.Player;

public class NodeGroup {
    private final String name;
    private String permission;
    private boolean discoverable;
    private boolean navigable;

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

    public boolean isNavigable() {
        return navigable;
    }

    public void setNavigable(boolean navigable) {
        this.navigable = navigable;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
    }

    public String getName() {
        return name;
    }

    public boolean isDiscoverable() {
        return discoverable;
    }

    public void setDiscoverable(boolean discoverable) {
        this.discoverable = discoverable;
    }
}