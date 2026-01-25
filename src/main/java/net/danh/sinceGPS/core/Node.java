package net.danh.sinceGPS.core;

import org.bukkit.Location;

import java.util.HashMap;
import java.util.Map;

public class Node {
    private int id;
    private Map<Integer, Double> edges;
    private Location location;
    private String group;
    private String name;
    private String displayName;

    public Node(int id, Location location, String group) {
        this.id = id;
        this.location = location;
        this.group = group;
        this.name = "node_" + id;
        this.displayName = this.name;
        this.edges = new HashMap<>();
    }

    public void connect(int targetId, double weight) {
        edges.put(targetId, weight);
    }

    public void disconnect(int targetId) {
        edges.remove(targetId);
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName != null ? displayName : name;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Location getLocation() {
        return location;
    }

    public void setLocation(Location location) {
        this.location = location;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public Map<Integer, Double> getEdges() {
        return edges;
    }

    public void setEdges(Map<Integer, Double> edges) {
        this.edges = edges;
    }
}