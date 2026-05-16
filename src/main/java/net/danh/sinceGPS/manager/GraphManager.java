package net.danh.sinceGPS.manager;

import net.danh.sinceGPS.SinceGPS;
import net.danh.sinceGPS.core.Node;
import net.danh.sinceGPS.core.NodeGroup;
import net.danh.sinceGPS.storage.SQLiteStorage;
import net.danh.sinceGPS.utils.ColorUtils;
import net.danh.sinceGPS.utils.SchedulerAdapter;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GraphManager {
    private final SinceGPS plugin;
    private final Map<Integer, Node> nodes = new ConcurrentHashMap<>();
    private final Map<String, Integer> nameIndex = new ConcurrentHashMap<>();
    private final Map<String, NodeGroup> groups = new ConcurrentHashMap<>();
    private final Map<UUID, List<Integer>> recorders = new ConcurrentHashMap<>();
    private final Map<UUID, SchedulerAdapter.TaskHandle> visualizerTasks = new ConcurrentHashMap<>();
    private final SQLiteStorage database;
    private int nextId = 0;

    private Particle particleNormal;
    private Particle particleNew;
    private Particle particleSnap;
    private Particle particleEdge;
    private double recordMinDistance;
    private double recordAngle;
    private double recordSnapDistance;
    private double optimizeAngle;
    private double visualizerRange;
    private double edgeDensity;
    private double recorderParticleYOffset;
    private long visualizerUpdateInterval;

    public GraphManager(SinceGPS plugin) {
        this.plugin = plugin;
        this.database = new SQLiteStorage(plugin);
        load();
    }

    public Node createNode(Location location, String group) {
        Node node = new Node(nextId++, location, group);
        nodes.put(node.getId(), node);
        nameIndex.put(node.getName(), node.getId());
        return node;
    }

    public void removeNode(int id) {
        Node node = nodes.remove(id);
        if (node != null) nameIndex.remove(node.getName());
        nodes.values().forEach(current -> current.disconnect(id));
    }

    public void connect(int id1, int id2, boolean oneWay) {
        Node first = nodes.get(id1);
        Node second = nodes.get(id2);
        if (first == null || second == null) return;
        double distance = first.getLocation().distance(second.getLocation());
        first.connect(id2, distance);
        if (!oneWay) second.connect(id1, distance);
    }

    public Node getNode(int id) {
        return nodes.get(id);
    }

    public Node getNode(String name) {
        return nodes.get(nameIndex.getOrDefault(name, -1));
    }

    public Node getNodeByDisplay(String value) {
        for (Node node : nodes.values()) {
            if (ColorUtils.stripColor(node.getDisplayName()).equalsIgnoreCase(value)
                    || node.getName().equalsIgnoreCase(value)) {
                return node;
            }
        }
        return null;
    }

    public Collection<Node> getNodes() {
        return nodes.values();
    }

    public Node getNearestNode(Location location, double radius) {
        Node best = null;
        double min = Double.MAX_VALUE;
        double radiusSq = radius * radius;
        for (Node node : nodes.values()) {
            Location nodeLocation = node.getLocation();
            if (nodeLocation.getWorld() == null || location.getWorld() == null) continue;
            if (!nodeLocation.getWorld().equals(location.getWorld())) continue;
            double distance = nodeLocation.distanceSquared(location);
            if (distance < min && distance < radiusSq) {
                min = distance;
                best = node;
            }
        }
        return best;
    }

    public Node getNearestNode(Location location) {
        return getNearestNode(location, plugin.getCfg().getDouble("settings.nearest-node-search-range", 100.0));
    }

    public boolean canAccess(Player player, Node node) {
        String groupName = node.getGroup() == null ? "default" : node.getGroup();
        NodeGroup group = groups.get(groupName);
        if (player == null) return group == null || group.hasNoPermission();
        return group == null || group.canAccess(player);
    }

    public void toggleRecord(Player player) {
        UUID playerId = player.getUniqueId();
        if (recorders.containsKey(playerId)) {
            stopRecorder(player);
            return;
        }

        List<Integer> session = new ArrayList<>();
        Node startNode = getNearestNode(player.getLocation(), recordSnapDistance);
        if (startNode == null) {
            startNode = createNode(player.getLocation(), "default");
        } else {
            player.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("record-snap")
                    .replace("<id>", String.valueOf(startNode.getId()))));
        }

        session.add(startNode.getId());
        recorders.put(playerId, session);
        startVisualizer(player);
        player.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("record-started")));
        plugin.getCfg().playSound(player, "sounds.start");
    }

    private void stopRecorder(Player player) {
        UUID playerId = player.getUniqueId();
        cancelVisualizer(playerId);
        List<Integer> session = recorders.remove(playerId);
        if (session == null) return;

        if (session.size() < 2) {
            session.forEach(this::removeNode);
            player.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("record-too-short")));
            return;
        }

        int removed = optimizePath(session);
        if (!session.isEmpty()) {
            int startId = session.get(0);
            Node startNode = getNode(startId);
            if (startNode != null && startNode.getName().equals("node_" + startId)) {
                renameNode(startNode, "start_" + startId);
            }

            int endId = session.get(session.size() - 1);
            Node endNode = getNode(endId);
            if (endNode != null && endNode.getName().equals("node_" + endId)) {
                renameNode(endNode, "stop_" + endId);
            }

            player.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("record-stopped")
                    .replace("<count>", String.valueOf(removed))));
            player.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("record-summary-start")
                    .replace("<id>", getNode(startId).getName())
                    .replace("<raw_id>", String.valueOf(startId))));
            player.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("record-summary-end")
                    .replace("<id>", getNode(endId).getName())
                    .replace("<raw_id>", String.valueOf(endId))));
        }

        saveAsync();
        plugin.getCfg().playSound(player, "sounds.stop");
    }

    public void renameNode(Node node, String newName) {
        nameIndex.remove(node.getName());
        node.setName(newName);
        nameIndex.put(newName, node.getId());
    }

    public boolean isRecording(Player player) {
        return recorders.containsKey(player.getUniqueId());
    }

    public void handleMoveRecord(Player player) {
        List<Integer> session = recorders.get(player.getUniqueId());
        if (session == null || session.isEmpty()) return;

        int lastId = session.get(session.size() - 1);
        Node lastNode = getNode(lastId);
        if (lastNode == null) return;

        Location playerLocation = player.getLocation();
        Location lastLocation = lastNode.getLocation();
        double distance = playerLocation.distance(lastLocation);
        boolean shouldCreate = distance >= recordMinDistance;

        if (!shouldCreate && distance > 2.0) {
            Vector facing = playerLocation.getDirection();
            Vector pathDirection = playerLocation.toVector().subtract(lastLocation.toVector());
            if (pathDirection.lengthSquared() > 0.0001
                    && Math.toDegrees(facing.angle(pathDirection.normalize())) > recordAngle) {
                shouldCreate = true;
            }
        }

        if (!shouldCreate) return;

        Node snap = getNearestNode(playerLocation, recordSnapDistance);
        if (snap != null && !session.contains(snap.getId())) {
            connect(lastId, snap.getId(), false);
            session.add(snap.getId());
            player.spawnParticle(particleSnap, snap.getLocation().add(0, recorderParticleYOffset, 0), 10);
            player.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("record-snap")
                    .replace("<id>", String.valueOf(snap.getId()))));
            plugin.getCfg().playSound(player, "sounds.snap");
            return;
        }

        Node node = createNode(playerLocation, "default");
        connect(lastId, node.getId(), false);
        session.add(node.getId());
        player.spawnParticle(particleNew, playerLocation.clone().add(0, recorderParticleYOffset, 0), 1);
    }

    private int optimizePath(List<Integer> ids) {
        if (ids.size() < 3) return 0;
        int removed = 0;
        for (int i = 1; i < ids.size() - 1; i++) {
            Node previous = getNode(ids.get(i - 1));
            Node current = getNode(ids.get(i));
            Node next = getNode(ids.get(i + 1));
            if (previous == null || current == null || next == null) continue;

            Vector firstVector = current.getLocation().toVector().subtract(previous.getLocation().toVector());
            Vector secondVector = next.getLocation().toVector().subtract(current.getLocation().toVector());
            if (firstVector.lengthSquared() <= 0.0001 || secondVector.lengthSquared() <= 0.0001) continue;

            if (Math.toDegrees(firstVector.normalize().angle(secondVector.normalize())) < optimizeAngle) {
                connect(previous.getId(), next.getId(), false);
                removeNode(current.getId());
                ids.remove(i);
                i--;
                removed++;
            }
        }
        return removed;
    }

    private void startVisualizer(Player player) {
        cancelVisualizer(player.getUniqueId());
        SchedulerAdapter.TaskHandle handle = plugin.getSchedulerAdapter().runEntityTimer(player,
                () -> renderRecorderVisualizer(player), 1L, visualizerUpdateInterval);
        visualizerTasks.put(player.getUniqueId(), handle);
    }

    private void cancelVisualizer(UUID playerId) {
        SchedulerAdapter.TaskHandle handle = visualizerTasks.remove(playerId);
        if (handle != null) handle.cancel();
    }

    private void renderRecorderVisualizer(Player player) {
        if (!player.isOnline() || !isRecording(player)) {
            cancelVisualizer(player.getUniqueId());
            return;
        }

        Location playerLocation = player.getLocation();
        for (Node node : nodes.values()) {
            Location nodeLocation = node.getLocation();
            if (nodeLocation.getWorld() == null || !nodeLocation.getWorld().equals(player.getWorld())) continue;
            if (nodeLocation.distance(playerLocation) > visualizerRange) continue;

            player.spawnParticle(particleNormal, nodeLocation.clone().add(0, recorderParticleYOffset, 0),
                    1, 0, 0, 0, 0);
            for (int targetId : node.getEdges().keySet()) {
                Node target = nodes.get(targetId);
                if (target != null) drawLine(player, nodeLocation, target.getLocation());
            }
        }
    }

    private void drawLine(Player player, Location first, Location second) {
        Vector direction = second.clone().subtract(first).toVector();
        double length = direction.length();
        if (length <= 0.0001) return;

        double density = Math.max(0.1, edgeDensity);
        direction.normalize().multiply(density);
        for (double distance = 0; distance < length; distance += density) {
            player.spawnParticle(particleEdge,
                    first.clone().add(direction.clone().multiply(distance)).add(0, recorderParticleYOffset, 0),
                    1, 0, 0, 0, 0);
        }
    }

    public void saveAsync() {
        Map<Integer, Node> snapshot = snapshotNodes();
        plugin.getSchedulerAdapter().runAsync(() -> database.saveAll(snapshot));
    }

    public void save() {
        database.saveAll(snapshotNodes());
    }

    private Map<Integer, Node> snapshotNodes() {
        Map<Integer, Node> snapshot = new HashMap<>();
        for (Node node : nodes.values()) {
            Node copy = new Node(node.getId(), node.getLocation(), node.getGroup());
            copy.setName(node.getName());
            copy.setDisplayName(node.getDisplayName());
            copy.setEdges(new HashMap<>(node.getEdges()));
            snapshot.put(copy.getId(), copy);
        }
        return snapshot;
    }

    public void load() {
        cancelAllVisualizers();

        recordMinDistance = plugin.getCfg().getDouble("settings.recorder.min-distance", 8.0);
        recordAngle = plugin.getCfg().getDouble("settings.recorder.angle-threshold", 15.0);
        recordSnapDistance = plugin.getCfg().getDouble("settings.recorder.snap-distance", 3.0);
        optimizeAngle = plugin.getCfg().getDouble("settings.algorithm.optimization-angle", 5.0);
        visualizerRange = plugin.getCfg().getDouble("visuals.recorder.visualizer-range", 40.0);
        edgeDensity = plugin.getCfg().getDouble("visuals.recorder.edge-density", 2.0);
        recorderParticleYOffset = plugin.getCfg().getDouble("visuals.recorder.particle-y-offset", 0.5);
        visualizerUpdateInterval = plugin.getCfg().getInt("visuals.recorder.update-interval", 20);

        particleNormal = parseParticle("visuals.recorder.node-normal", Particle.FLAME);
        particleNew = parseParticle("visuals.recorder.node-new", Particle.HAPPY_VILLAGER);
        particleSnap = parseParticle("visuals.recorder.node-snap", Particle.SOUL_FIRE_FLAME);
        particleEdge = parseParticle("visuals.recorder.edge-line", Particle.CRIT);

        groups.clear();
        ConfigurationSection groupSec = plugin.getCfg().getSection("groups");
        if (groupSec != null) {
            for (String key : groupSec.getKeys(false)) {
                groups.put(key, new NodeGroup(key,
                        groupSec.getString(key + ".permission", ""),
                        groupSec.getBoolean(key + ".discoverable", false),
                        groupSec.getBoolean(key + ".navigable", true)));
            }
        }
        groups.putIfAbsent("default", new NodeGroup("default", "", false, true));

        nodes.clear();
        nameIndex.clear();
        nodes.putAll(database.loadNodes());
        nextId = nodes.keySet().stream().max(Integer::compare).orElse(-1) + 1;
        for (Node node : nodes.values()) nameIndex.put(node.getName(), node.getId());

        for (UUID playerId : recorders.keySet()) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) startVisualizer(player);
        }

        plugin.getLogger().info("Loaded " + nodes.size() + " GPS nodes from SQLite.");
    }

    private Particle parseParticle(String path, Particle fallback) {
        try {
            return Particle.valueOf(plugin.getCfg().getString(path, fallback.name()).toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private void cancelAllVisualizers() {
        visualizerTasks.values().forEach(SchedulerAdapter.TaskHandle::cancel);
        visualizerTasks.clear();
    }

    public void shutdown() {
        cancelAllVisualizers();
        recorders.clear();
        save();
        database.close();
    }
}
