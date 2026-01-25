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

    // Cached Settings
    private Particle pNodeNormal, pNodeSelected, pNodeAuto, pEdge;
    private double recordMinDist, recordAngleThreshold, recordSnapDist;

    public GraphManager(SinceGPS plugin) {
        this.plugin = plugin;
        this.database = new SQLiteStorage(plugin);
        load();
        startVisualizer();
    }

    // --- NODE OPERATIONS ---

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

    public Set<String> getNodeNames() {
        return nameIndex.keySet();
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

    // --- SMART RECORDING ---

    public void toggleRecord(Player p) {
        if (recorders.containsKey(p.getUniqueId())) {
            List<Integer> session = recorders.remove(p.getUniqueId());
            int removed = optimizePath(session);
            saveAsync();

            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("record-stopped").replace("<count>", String.valueOf(removed))));

            if (!session.isEmpty()) {
                p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("record-summary-start").replace("<id>", String.valueOf(session.get(0)))));
                p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("record-summary-end").replace("<id>", String.valueOf(session.get(session.size() - 1)))));
            }

            plugin.getCfg().playSound(p, "sounds.stop");
        } else {
            List<Integer> session = new ArrayList<>();
            Node startNode = getNearestNode(p.getLocation(), recordSnapDist);

            if (startNode == null) {
                startNode = createNode(p.getLocation(), "default");
            } else {
                p.sendMessage(ColorUtils.parseWithPrefix("&eĐã kết nối với đường cũ (Node " + startNode.getId() + ")"));
            }

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

        boolean shouldCreate = false;
        if (dist >= recordMinDist) shouldCreate = true;
        else if (dist > 2.0) {
            Vector currentDir = pLoc.getDirection();
            Vector pathDir = pLoc.toVector().subtract(lastNode.getLocation().toVector()).normalize();
            if (Math.toDegrees(currentDir.angle(pathDir)) > recordAngleThreshold) shouldCreate = true;
        }

        if (shouldCreate) {
            Node snapNode = getNearestNode(pLoc, recordSnapDist);
            if (snapNode != null && !session.contains(snapNode.getId())) {
                connect(lastId, snapNode.getId(), false);
                session.add(snapNode.getId());
                p.spawnParticle(pNodeAuto, snapNode.getLocation().add(0, 1, 0), 10);
                p.sendMessage(ColorUtils.parseWithPrefix("&eĐã bắt dính ngã rẽ (Node " + snapNode.getId() + ")"));
                plugin.getCfg().playSound(p, "sounds.popup");
            } else {
                Node newNode = createNode(pLoc, "default");
                connect(lastId, newNode.getId(), false);
                session.add(newNode.getId());
                p.spawnParticle(pNodeAuto, pLoc.add(0, 0.5, 0), 1);
            }
        }
    }

    private int optimizePath(List<Integer> sessionIds) {
        if (sessionIds.size() < 3) return 0;
        int removed = 0;
        for (int i = 1; i < sessionIds.size() - 1; i++) {
            Node prev = getNode(sessionIds.get(i - 1));
            Node curr = getNode(sessionIds.get(i));
            Node next = getNode(sessionIds.get(i + 1));
            if (prev == null || curr == null || next == null) continue;

            Vector v1 = curr.getLocation().toVector().subtract(prev.getLocation().toVector()).normalize();
            Vector v2 = next.getLocation().toVector().subtract(curr.getLocation().toVector()).normalize();

            if (Math.toDegrees(v1.angle(v2)) < 5.0) {
                connect(prev.getId(), next.getId(), false);
                removeNode(curr.getId());
                sessionIds.remove(i);
                i--;
                removed++;
            }
        }
        return removed;
    }

    // --- VISUALIZER ---

    private void startVisualizer() {
        visualizerTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (UUID uid : recorders.keySet()) {
                Player p = plugin.getServer().getPlayer(uid);
                if (p == null || !p.isOnline()) continue;
                for (Node n : nodes.values()) {
                    if (n.getLocation().getWorld() == null || !n.getLocation().getWorld().equals(p.getWorld()))
                        continue;
                    if (n.getLocation().distance(p.getLocation()) > 40) continue;

                    p.spawnParticle(pNodeNormal, n.getLocation().clone().add(0, 0.5, 0), 1, 0, 0, 0, 0);
                    for (int tId : n.getEdges().keySet()) {
                        Node t = nodes.get(tId);
                        if (t != null) drawLine(p, n.getLocation(), t.getLocation());
                    }
                }
            }
        }, 0L, 20L);
    }

    private void drawLine(Player p, Location l1, Location l2) {
        Vector dir = l2.clone().subtract(l1).toVector();
        double len = dir.length();
        dir.normalize().multiply(2.0);
        for (double d = 0; d < len; d += 2.0) {
            p.spawnParticle(pEdge, l1.clone().add(dir.clone().multiply(d)).add(0, 0.5, 0), 1, 0, 0, 0, 0);
        }
    }

    // --- IO & LOAD ---

    public void saveAsync() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::save);
    }

    public void save() {
        database.saveAll(nodes);
    }

    public void load() {
        // Cache Settings
        recordMinDist = plugin.getCfg().getDouble("settings.recorder.min-distance", 8.0);
        recordAngleThreshold = plugin.getCfg().getDouble("settings.recorder.angle-threshold", 15.0);
        recordSnapDist = plugin.getCfg().getDouble("settings.recorder.snap-distance", 3.0);

        try {
            pNodeNormal = Particle.valueOf(plugin.getCfg().getString("visuals.editor.node-normal", "FLAME"));
        } catch (Exception e) {
            pNodeNormal = Particle.FLAME;
        }
        try {
            pNodeSelected = Particle.valueOf(plugin.getCfg().getString("visuals.editor.node-selected", "HAPPY_VILLAGER"));
        } catch (Exception e) {
            pNodeSelected = Particle.HAPPY_VILLAGER;
        }
        try {
            pNodeAuto = Particle.valueOf(plugin.getCfg().getString("visuals.editor.node-auto-create", "SOUL_FIRE_FLAME"));
        } catch (Exception e) {
            pNodeAuto = Particle.SOUL_FIRE_FLAME;
        }
        try {
            pEdge = Particle.valueOf(plugin.getCfg().getString("visuals.editor.edge-line", "CRIT"));
        } catch (Exception e) {
            pEdge = Particle.CRIT;
        }

        // [FIXED] Load Groups with 4 arguments
        groups.clear();
        ConfigurationSection groupSec = plugin.getCfg().getSection("groups");
        if (groupSec != null) {
            for (String key : groupSec.getKeys(false)) {
                groups.put(key, new NodeGroup(key,
                        groupSec.getString(key + ".permission", ""),
                        groupSec.getBoolean(key + ".discoverable", false), // Arg 3
                        groupSec.getBoolean(key + ".navigable", true)));   // Arg 4
            }
        }

        // [FIXED] Default group with 4 arguments
        if (!groups.containsKey("default")) {
            groups.put("default", new NodeGroup("default", "", false, true));
        }

        // Load Nodes
        nodes.clear();
        nameIndex.clear();
        Map<Integer, Node> loadedNodes = database.loadNodes();
        nodes.putAll(loadedNodes);

        nextId = nodes.keySet().stream().max(Integer::compare).orElse(0) + 1;
        for (Node n : nodes.values()) nameIndex.put(n.getName(), n.getId());

        plugin.getLogger().info("Đã tải " + nodes.size() + " nodes từ Database.");
    }

    public void shutdown() {
        if (visualizerTask != null) visualizerTask.cancel();
        save();
        database.close();
    }
}