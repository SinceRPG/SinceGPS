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

    // Dữ liệu bộ nhớ (Cache)
    private final Map<Integer, Node> nodes = new ConcurrentHashMap<>();
    private final Map<String, Integer> nameIndex = new HashMap<>();
    private final Map<String, NodeGroup> groups = new HashMap<>();

    // Map lưu session ghi hình: PlayerUUID -> List<NodeID>
    private final Map<UUID, List<Integer>> recorders = new ConcurrentHashMap<>();

    // Hệ thống lưu trữ SQLite
    private final SQLiteStorage database;

    private int nextId = 0;
    private BukkitTask visualizerTask;

    public GraphManager(SinceGPS plugin) {
        this.plugin = plugin;
        this.database = new SQLiteStorage(plugin); // Khởi tạo kết nối DB
        load(); // Tải dữ liệu
        startVisualizer(); // Bắt đầu task hiển thị hạt
    }

    // --- NODE LOGIC (Thao tác cơ bản) ---

    public Node createNode(Location loc, String group) {
        Node n = new Node(nextId++, loc, group);
        nodes.put(n.getId(), n);
        nameIndex.put(n.getName(), n.getId());
        return n;
        // Lưu ý: Chưa lưu vào DB ngay để tối ưu tốc độ khi đang record liên tục.
        // Dữ liệu sẽ được lưu khi tắt server hoặc tắt chế độ record.
    }

    public void removeNode(int id) {
        Node n = nodes.remove(id);
        if (n != null) nameIndex.remove(n.getName());

        // Xóa kết nối từ các node khác trỏ tới nó
        nodes.values().forEach(node -> node.disconnect(id));

        // Xóa trực tiếp khỏi Database ngay lập tức để đồng bộ
        database.deleteNode(id);
    }

    public void connect(int id1, int id2, boolean oneWay) {
        Node n1 = nodes.get(id1);
        Node n2 = nodes.get(id2);
        if (n1 == null || n2 == null) return;

        double dist = n1.getLocation().distance(n2.getLocation());
        n1.connect(id2, dist);
        if (!oneWay) {
            n2.connect(id1, dist);
        }
    }

    // --- GETTERS & FINDERS (Tìm kiếm) ---

    public Node getNode(int id) {
        return nodes.get(id);
    }

    public Node getNode(String name) {
        return nodes.get(nameIndex.getOrDefault(name, -1));
    }

    public Node getNodeByDisplay(String search) {
        for (Node n : nodes.values()) {
            // So sánh tên hiển thị (bỏ màu) hoặc tên kỹ thuật
            if (ColorUtils.stripColor(n.getDisplayName()).equalsIgnoreCase(search) || n.getName().equalsIgnoreCase(search)) {
                return n;
            }
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
            // Kiểm tra world và check null
            if (n.getLocation() == null || n.getLocation().getWorld() == null || loc.getWorld() == null) continue;
            if (!n.getLocation().getWorld().equals(loc.getWorld())) continue;

            double d = n.getLocation().distanceSquared(loc);
            if (d < min && d < rSq) {
                min = d;
                best = n;
            }
        }
        return best;
    }

    // Helper mặc định lấy bán kính từ config
    public Node getNearestNode(Location loc) {
        return getNearestNode(loc, plugin.getCfg().getDouble("settings.nearest-node-search-range", 100.0));
    }

    // --- GROUP & PERMISSIONS ---

    public boolean canAccess(Player p, Node n) {
        String groupName = (n.getGroup() == null || n.getGroup().isEmpty()) ? "default" : n.getGroup();
        NodeGroup g = groups.get(groupName);

        // Nếu group không tồn tại trong config -> Cho phép đi (Fallback)
        if (g == null) return true;

        return g.canAccess(p);
    }

    // --- SMART RECORDING SYSTEM (Hệ thống ghi hình thông minh) ---

    public void toggleRecord(Player p) {
        if (recorders.containsKey(p.getUniqueId())) {
            // === DỪNG RECORD ===
            List<Integer> session = recorders.remove(p.getUniqueId());

            // 1. Chạy thuật toán nén đường đi (Xóa node thẳng hàng)
            int removed = optimizePath(session);

            // 2. Lưu Async xuống Database
            saveAsync();

            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMsg().getString("record-stopped").replace("<count>", String.valueOf(removed))));
            plugin.getCfg().playSound(p, "sounds.stop");
        } else {
            // === BẮT ĐẦU RECORD ===
            List<Integer> session = new ArrayList<>();
            double snapDist = plugin.getCfg().getDouble("settings.recorder.snap-distance", 3.0);

            // Auto Snap: Tìm node cũ gần nhất để bắt đầu (Tạo ngã ba/ngã tư)
            Node startNode = getNearestNode(p.getLocation(), snapDist);

            if (startNode == null) {
                // Không có đường cũ -> Tạo điểm khởi đầu mới
                startNode = createNode(p.getLocation(), "default");
            } else {
                p.sendMessage(ColorUtils.parseWithPrefix("&eĐã kết nối với mạng lưới cũ (Node " + startNode.getId() + ")"));
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
        if (lastNode == null) return; // Node bị xóa giữa chừng

        Location pLoc = p.getLocation();
        double dist = pLoc.distance(lastNode.getLocation());

        // Lấy config độ nhạy
        double minDist = plugin.getCfg().getDouble("settings.recorder.min-distance", 8.0);
        double angleThreshold = plugin.getCfg().getDouble("settings.recorder.angle-threshold", 15.0);
        double snapDist = plugin.getCfg().getDouble("settings.recorder.snap-distance", 3.0);

        boolean shouldCreate = false;

        // 1. Logic thông minh: Chỉ tạo node nếu đi xa HOẶC đang cua gắt
        if (dist >= minDist) {
            shouldCreate = true;
        } else if (dist > 2.0) { // Check góc cua nếu đã đi được > 2m
            Vector currentDir = pLoc.getDirection();
            Vector pathDir = pLoc.toVector().subtract(lastNode.getLocation().toVector()).normalize();
            double angle = Math.toDegrees(currentDir.angle(pathDir));

            // Nếu góc lệch > ngưỡng -> Đang cua -> Cần node mới để đường mượt
            if (angle > angleThreshold) {
                shouldCreate = true;
            }
        }

        if (shouldCreate) {
            // 2. Logic Auto Snap (Tự động bắt dính ngã tư)
            Node snapNode = getNearestNode(pLoc, snapDist);

            // Nếu tìm thấy node cũ (không nằm trong session hiện tại) -> Snap vào nó
            if (snapNode != null && !session.contains(snapNode.getId())) {
                connect(lastId, snapNode.getId(), false);
                session.add(snapNode.getId());

                p.spawnParticle(Particle.HAPPY_VILLAGER, snapNode.getLocation().add(0, 1, 0), 10);
                p.sendMessage(ColorUtils.parseWithPrefix("&eĐã bắt dính ngã rẽ (Node " + snapNode.getId() + ")"));
                plugin.getCfg().playSound(p, "sounds.popup");
            } else {
                // Không có gì để snap -> Tạo node mới bình thường
                Node newNode = createNode(pLoc, "default");
                connect(lastId, newNode.getId(), false);
                session.add(newNode.getId());

                p.spawnParticle(Particle.FLAME, pLoc.add(0, 0.5, 0), 1);
            }
        }
    }

    /**
     * Thuật toán tối ưu hóa đường đi (Douglas-Peucker đơn giản)
     * Loại bỏ các node nằm trên một đường thẳng để giảm tải dữ liệu.
     */
    private int optimizePath(List<Integer> sessionIds) {
        if (sessionIds.size() < 3) return 0;
        int removed = 0;

        // Duyệt danh sách (giữ lại điểm đầu và cuối)
        for (int i = 1; i < sessionIds.size() - 1; i++) {
            Node prev = getNode(sessionIds.get(i - 1));
            Node curr = getNode(sessionIds.get(i));
            Node next = getNode(sessionIds.get(i + 1));

            if (prev == null || curr == null || next == null) continue;

            // Tính góc tạo bởi 3 điểm (Prev -> Curr -> Next)
            Vector v1 = curr.getLocation().toVector().subtract(prev.getLocation().toVector()).normalize();
            Vector v2 = next.getLocation().toVector().subtract(curr.getLocation().toVector()).normalize();
            double angle = Math.toDegrees(v1.angle(v2));

            // Nếu góc < 5 độ (Gần như thẳng hàng) -> Node ở giữa là thừa
            if (angle < 5.0) {
                // Nối tắt Prev -> Next
                connect(prev.getId(), next.getId(), false);

                // Xóa Node giữa khỏi bộ nhớ và DB
                removeNode(curr.getId());

                // Cập nhật danh sách session
                sessionIds.remove(i);
                i--; // Lùi index lại để kiểm tra tiếp
                removed++;
            }
        }
        return removed;
    }

    // --- VISUALIZER (Hiển thị cho người đang Record) ---
    private void startVisualizer() {
        visualizerTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (UUID uid : recorders.keySet()) {
                Player p = plugin.getServer().getPlayer(uid);
                if (p == null || !p.isOnline()) continue;

                // Vẽ các node xung quanh để người xây dựng thấy
                for (Node n : nodes.values()) {
                    if (n.getLocation() == null || n.getLocation().getWorld() == null || p.getWorld() == null) continue;
                    if (!n.getLocation().getWorld().equals(p.getWorld())) continue;
                    if (n.getLocation().distance(p.getLocation()) > 40) continue;

                    p.spawnParticle(Particle.FLAME, n.getLocation().clone().add(0, 0.5, 0), 1, 0, 0, 0, 0);

                    // Vẽ dây nối
                    for (int tId : n.getEdges().keySet()) {
                        Node t = nodes.get(tId);
                        if (t != null && t.getLocation() != null) drawLine(p, n.getLocation(), t.getLocation());
                    }
                }
            }
        }, 0L, 20L); // 1 giây/lần cho nhẹ
    }

    private void drawLine(Player p, Location l1, Location l2) {
        Vector dir = l2.clone().subtract(l1).toVector();
        double len = dir.length();
        dir.normalize().multiply(2.0); // Vẽ thưa (2 block/hạt)
        for (double d = 0; d < len; d += 2.0) {
            p.spawnParticle(Particle.CRIT, l1.clone().add(dir.clone().multiply(d)).add(0, 0.5, 0), 1, 0, 0, 0, 0);
        }
    }

    // --- IO (LƯU TRỮ VỚI SQLITE) ---

    public void saveAsync() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::save);
    }

    public void save() {
        // Gọi SQLiteStorage để lưu toàn bộ node (Bulk Insert/Update)
        database.saveAll(nodes);
    }

    public void load() {
        // 1. Load Groups từ Config YAML (Vì groups ít khi thay đổi và cần chỉnh tay dễ dàng)
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
        // Luôn đảm bảo có group default
        if (!groups.containsKey("default")) groups.put("default", new NodeGroup("default", "", false, true));

        // 2. Load Nodes từ SQLite Database
        nodes.clear();
        nameIndex.clear();

        Map<Integer, Node> loadedNodes = database.loadNodes();
        nodes.putAll(loadedNodes);

        // Tính toán nextId để tạo node mới không bị trùng
        nextId = nodes.keySet().stream().max(Integer::compare).orElse(0) + 1;

        // Xây dựng lại chỉ mục tên
        for (Node n : nodes.values()) {
            nameIndex.put(n.getName(), n.getId());
        }

        plugin.getLogger().info("Đã tải " + nodes.size() + " nodes từ Database.");
    }

    public void shutdown() {
        if (visualizerTask != null) visualizerTask.cancel();
        save(); // Lưu lần cuối trước khi tắt
        database.close(); // Đóng kết nối DB
    }
}