package net.danh.sinceGPS.manager;

import net.danh.sinceGPS.SinceGPS;
import net.danh.sinceGPS.core.Node;
import net.danh.sinceGPS.core.NodeGroup;
import net.danh.sinceGPS.storage.SQLiteStorage;
import net.danh.sinceGPS.utils.ColorUtils;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GraphManager {
    private final SinceGPS plugin;
    private final Map<Integer, Node> nodes = new ConcurrentHashMap<>();
    private final Map<String, Integer> nameIndex = new HashMap<>();
    private final Map<String, NodeGroup> groups = new HashMap<>();
    private final Map<UUID, List<Integer>> recorders = new ConcurrentHashMap<>();
    private final SQLiteStorage database;
    private int nextId = 0;
    private BukkitTask visualizerTask;

    private Particle pNormal, pNew, pSnap, pEdge;
    private double recMinDist, recAngle, recSnap, optAngle, visRange, edgeDensity;
    private long visUpdate;

    public GraphManager(SinceGPS plugin) {
        this.plugin = plugin;
        this.database = new SQLiteStorage(plugin);
        load();
        startVisualizer();
    }

    public Node createNode(Location loc, String group) {
        Node n = new Node(nextId++, loc, group);
        nodes.put(n.getId(), n);
        nameIndex.put(n.getName(), n.getId());
        return n;
    }

    public void removeNode(int id) {
        Node n = nodes.remove(id);
        if (n != null) nameIndex.remove(n.getName());
        nodes.values().forEach(node -> node.disconnect(id));
        database.deleteNode(id);
    }

    public void connect(int id1, int id2, boolean oneWay) {
        Node n1 = nodes.get(id1);
        Node n2 = nodes.get(id2);
        if (n1 == null || n2 == null) return;
        double dist = n1.getLocation().distance(n2.getLocation());
        n1.connect(id2, dist);
        if (!oneWay) n2.connect(id1, dist);
    }

    public Node getNode(int id) {
        return nodes.get(id);
    }

    public Node getNode(String name) {
        return nodes.get(nameIndex.getOrDefault(name, -1));
    }

    public Node getNodeByDisplay(String s) {
        for (Node n : nodes.values()) {
            if (ColorUtils.stripColor(n.getDisplayName()).equalsIgnoreCase(s) || n.getName().equalsIgnoreCase(s))
                return n;
        }
        return null;
    }

    public Collection<Node> getNodes() {
        return nodes.values();
    }

    public Node getNearestNode(Location loc, double radius) {
        Node best = null;
        double min = Double.MAX_VALUE;
        double rSq = radius * radius;
        for (Node n : nodes.values()) {
            if (n.getLocation().getWorld() == null || loc.getWorld() == null) continue;
            if (!n.getLocation().getWorld().equals(loc.getWorld())) continue;
            double d = n.getLocation().distanceSquared(loc);
            if (d < min && d < rSq) {
                min = d;
                best = n;
            }
        }
        return best;
    }

    public Node getNearestNode(Location loc) {
        return getNearestNode(loc, plugin.getCfg().getDouble("settings.nearest-node-search-range", 100.0));
    }

    public boolean canAccess(Player p, Node n) {
        String gn = (n.getGroup() == null) ? "default" : n.getGroup();
        NodeGroup g = groups.get(gn);
        return g == null || g.canAccess(p);
    }

    public void toggleRecord(Player p) {
        if (recorders.containsKey(p.getUniqueId())) {
            List<Integer> session = recorders.remove(p.getUniqueId());
            if (session.size() < 2) {
                session.forEach(this::removeNode);
                p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("record-too-short")));
                return;
            }

            int removed = optimizePath(session);

            if (!session.isEmpty()) {
                int startId = session.get(0);
                Node startNode = getNode(startId);
                if (startNode != null && startNode.getName().equals("node_" + startId)) {
                    String newName = "start_" + startId;
                    nameIndex.remove(startNode.getName());
                    startNode.setName(newName);
                    nameIndex.put(newName, startId);
                }

                int endId = session.get(session.size() - 1);
                Node endNode = getNode(endId);
                if (endNode != null && endNode.getName().equals("node_" + endId)) {
                    String newName = "stop_" + endId;
                    nameIndex.remove(endNode.getName());
                    endNode.setName(newName);
                    nameIndex.put(newName, endId);
                }

                p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("record-stopped").replace("<count>", String.valueOf(removed))));
                p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("record-summary-start")
                        .replace("<id>", getNode(startId).getName()).replace("<raw_id>", String.valueOf(startId))));
                p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("record-summary-end")
                        .replace("<id>", getNode(endId).getName()).replace("<raw_id>", String.valueOf(endId))));
            }

            saveAsync();
            plugin.getCfg().playSound(p, "sounds.stop");
        } else {
            List<Integer> session = new ArrayList<>();
            Node startNode = getNearestNode(p.getLocation(), recSnap);
            if (startNode == null) startNode = createNode(p.getLocation(), "default");
            else
                p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("record-snap").replace("<id>", String.valueOf(startNode.getId()))));
            session.add(startNode.getId());
            recorders.put(p.getUniqueId(), session);
            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("record-started")));
            plugin.getCfg().playSound(p, "sounds.start");
        }
    }

    public boolean isRecording(Player p) {
        return recorders.containsKey(p.getUniqueId());
    }

    public void handleMoveRecord(Player p) {
        List<Integer> session = recorders.get(p.getUniqueId());
        if (session == null || session.isEmpty()) return;

        int lastId = session.get(session.size() - 1);
        Node lastNode = getNode(lastId);
        if (lastNode == null) return;

        Location pLoc = p.getLocation();
        double dist = pLoc.distance(lastNode.getLocation());
        boolean create = false;

        if (dist >= recMinDist) create = true;
        else if (dist > 2.0) {
            Vector dir = pLoc.getDirection();
            Vector path = pLoc.toVector().subtract(lastNode.getLocation().toVector()).normalize();
            if (Math.toDegrees(dir.angle(path)) > recAngle) create = true;
        }

        if (create) {
            Node snap = getNearestNode(pLoc, recSnap);
            if (snap != null && !session.contains(snap.getId())) {
                connect(lastId, snap.getId(), false);
                session.add(snap.getId());
                p.spawnParticle(pSnap, snap.getLocation().add(0, 1, 0), 10);
                p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("record-snap").replace("<id>", String.valueOf(snap.getId()))));
                plugin.getCfg().playSound(p, "sounds.snap");
            } else {
                Node n = createNode(pLoc, "default");
                connect(lastId, n.getId(), false);
                session.add(n.getId());
                p.spawnParticle(pNew, pLoc.add(0, 0.5, 0), 1);
            }
        }
    }

    private int optimizePath(List<Integer> ids) {
        if (ids.size() < 3) return 0;
        int removed = 0;
        for (int i = 1; i < ids.size() - 1; i++) {
            Node p = getNode(ids.get(i - 1));
            Node c = getNode(ids.get(i));
            Node n = getNode(ids.get(i + 1));
            if (p == null || c == null || n == null) continue;

            Vector v1 = c.getLocation().toVector().subtract(p.getLocation().toVector()).normalize();
            Vector v2 = n.getLocation().toVector().subtract(c.getLocation().toVector()).normalize();

            if (Math.toDegrees(v1.angle(v2)) < optAngle) {
                connect(p.getId(), n.getId(), false);
                removeNode(c.getId());
                ids.remove(i);
                i--;
                removed++;
            }
        }
        return removed;
    }

    private void startVisualizer() {
        if (visualizerTask != null) visualizerTask.cancel();
        visualizerTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (UUID uid : recorders.keySet()) {
                Player p = plugin.getServer().getPlayer(uid);
                if (p == null || !p.isOnline()) continue;
                try {
                    Location pLoc = p.getLocation(); // ASYNC READ (Safe on Paper, mostly)
                    for (Node n : nodes.values()) {
                        if (n.getLocation().getWorld() != p.getWorld() || n.getLocation().distance(pLoc) > visRange)
                            continue;
                        p.spawnParticle(pNormal, n.getLocation().clone().add(0, 0.5, 0), 1, 0, 0, 0, 0);
                        for (int tId : n.getEdges().keySet()) {
                            Node t = nodes.get(tId);
                            if (t != null) drawLine(p, n.getLocation(), t.getLocation());
                        }
                    }
                } catch (Exception ignored) {
                } // Catch async errors
            }
        }, 0L, visUpdate);
    }

    private void drawLine(Player p, Location l1, Location l2) {
        Vector dir = l2.clone().subtract(l1).toVector();
        double len = dir.length();
        dir.normalize().multiply(edgeDensity);
        for (double d = 0; d < len; d += edgeDensity) {
            p.spawnParticle(pEdge, l1.clone().add(dir.clone().multiply(d)).add(0, 0.5, 0), 1, 0, 0, 0, 0);
        }
    }

    public void saveAsync() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::save);
    }

    public void save() {
        database.saveAll(nodes);
    }

    public void load() {
        recMinDist = plugin.getCfg().getDouble("settings.recorder.min-distance", 8.0);
        recAngle = plugin.getCfg().getDouble("settings.recorder.angle-threshold", 15.0);
        recSnap = plugin.getCfg().getDouble("settings.recorder.snap-distance", 3.0);
        optAngle = plugin.getCfg().getDouble("settings.algorithm.optimization-angle", 5.0);
        visRange = plugin.getCfg().getDouble("visuals.recorder.visualizer-range", 40.0);
        edgeDensity = plugin.getCfg().getDouble("visuals.recorder.edge-density", 2.0);
        visUpdate = plugin.getCfg().getInt("visuals.recorder.update-interval", 20);

        try {
            pNormal = Particle.valueOf(plugin.getCfg().getString("visuals.recorder.node-normal", "FLAME"));
        } catch (Exception e) {
            pNormal = Particle.FLAME;
        }
        try {
            pNew = Particle.valueOf(plugin.getCfg().getString("visuals.recorder.node-new", "HAPPY_VILLAGER"));
        } catch (Exception e) {
            pNew = Particle.HAPPY_VILLAGER;
        }
        try {
            pSnap = Particle.valueOf(plugin.getCfg().getString("visuals.recorder.node-snap", "SOUL_FIRE_FLAME"));
        } catch (Exception e) {
            pSnap = Particle.SOUL_FIRE_FLAME;
        }
        try {
            pEdge = Particle.valueOf(plugin.getCfg().getString("visuals.recorder.edge-line", "CRIT"));
        } catch (Exception e) {
            pEdge = Particle.CRIT;
        }

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
        if (!groups.containsKey("default")) groups.put("default", new NodeGroup("default", "", false, true));

        nodes.clear();
        nameIndex.clear();
        Map<Integer, Node> dbNodes = database.loadNodes();
        nodes.putAll(dbNodes);

        nextId = nodes.keySet().stream().max(Integer::compare).orElse(0) + 1;
        for (Node n : nodes.values()) nameIndex.put(n.getName(), n.getId());

        plugin.getLogger().info("Loaded " + nodes.size() + " nodes from Database.");
        startVisualizer();
    }

    public void shutdown() {
        if (visualizerTask != null) visualizerTask.cancel();
        save();
        database.close();
    }
}