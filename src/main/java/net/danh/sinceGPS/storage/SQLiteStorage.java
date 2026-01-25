package net.danh.sinceGPS.storage;

import net.danh.sinceGPS.SinceGPS;
import net.danh.sinceGPS.core.Node;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.io.File;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class SQLiteStorage {
    private final SinceGPS plugin;
    private final String dbName = "database.db";
    private Connection connection;

    public SQLiteStorage(SinceGPS plugin) {
        this.plugin = plugin;
        initialize();
    }

    private void initialize() {
        File dataFolder = new File(plugin.getDataFolder(), dbName);
        if (!dataFolder.exists()) {
            try {
                dataFolder.createNewFile();
            } catch (Exception e) {
                plugin.getLogger().severe("Không thể tạo file database!");
            }
        }

        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dataFolder);
            createTables();
        } catch (Exception e) {
            plugin.getLogger().severe("Lỗi kết nối SQLite: " + e.getMessage());
        }
    }

    private void createTables() {
        // Bảng lưu Node
        String nodeTable = "CREATE TABLE IF NOT EXISTS gps_nodes (" +
                "id INTEGER PRIMARY KEY," +
                "name TEXT," +
                "display_name TEXT," +
                "world TEXT," +
                "x DOUBLE," +
                "y DOUBLE," +
                "z DOUBLE," +
                "group_name TEXT" +
                ");";

        // Bảng lưu kết nối (Edge)
        String edgeTable = "CREATE TABLE IF NOT EXISTS gps_edges (" +
                "source_id INTEGER," +
                "target_id INTEGER," +
                "weight DOUBLE," +
                "PRIMARY KEY (source_id, target_id)" +
                ");";

        try (Statement s = connection.createStatement()) {
            s.execute(nodeTable);
            s.execute(edgeTable);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // --- LOAD DATA ---
    public Map<Integer, Node> loadNodes() {
        Map<Integer, Node> nodes = new HashMap<>();

        // 1. Load Nodes
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM gps_nodes")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                int id = rs.getInt("id");
                String worldName = rs.getString("world");
                if (Bukkit.getWorld(worldName) == null) continue; // Skip nếu world null

                Location loc = new Location(Bukkit.getWorld(worldName), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"));
                Node node = new Node(id, loc, rs.getString("group_name"));
                node.setName(rs.getString("name"));
                node.setDisplayName(rs.getString("display_name"));

                nodes.put(id, node);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Lỗi load nodes", e);
        }

        // 2. Load Edges
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM gps_edges")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                int sourceId = rs.getInt("source_id");
                int targetId = rs.getInt("target_id");
                double weight = rs.getDouble("weight");

                Node source = nodes.get(sourceId);
                if (source != null && nodes.containsKey(targetId)) {
                    source.connect(targetId, weight);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Lỗi load edges", e);
        }

        return nodes;
    }

    // --- SAVE DATA (Async Bulk Update) ---
    public void saveAll(Map<Integer, Node> nodes) {
        // Dùng Transaction để save cực nhanh (nguyên tắc ACID)
        String insertNode = "REPLACE INTO gps_nodes (id, name, display_name, world, x, y, z, group_name) VALUES(?,?,?,?,?,?,?,?)";
        String insertEdge = "REPLACE INTO gps_edges (source_id, target_id, weight) VALUES(?,?,?)";

        try {
            connection.setAutoCommit(false); // Bắt đầu transaction

            try (PreparedStatement psNode = connection.prepareStatement(insertNode);
                 PreparedStatement psEdge = connection.prepareStatement(insertEdge)) {

                // Xóa dữ liệu cũ (hoặc dùng REPLACE INTO như trên)
                // Ở đây mình dùng REPLACE INTO để update, nhưng để sạch sẽ ta nên truncate trước nếu muốn đồng bộ hoàn toàn
                // Tuy nhiên để an toàn, ta cứ update đè.

                for (Node n : nodes.values()) {
                    psNode.setInt(1, n.getId());
                    psNode.setString(2, n.getName());
                    psNode.setString(3, n.getDisplayName());
                    psNode.setString(4, n.getLocation().getWorld().getName());
                    psNode.setDouble(5, n.getLocation().getX());
                    psNode.setDouble(6, n.getLocation().getY());
                    psNode.setDouble(7, n.getLocation().getZ());
                    psNode.setString(8, n.getGroup());
                    psNode.addBatch();

                    for (Map.Entry<Integer, Double> edge : n.getEdges().entrySet()) {
                        psEdge.setInt(1, n.getId());
                        psEdge.setInt(2, edge.getKey());
                        psEdge.setDouble(3, edge.getValue());
                        psEdge.addBatch();
                    }
                }

                psNode.executeBatch();
                psEdge.executeBatch();
            }

            connection.commit(); // Chốt đơn
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            e.printStackTrace();
            try {
                connection.rollback();
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
        }
    }

    // Xóa node khỏi DB khi người chơi xóa ingame
    public void deleteNode(int id) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Statement s = connection.createStatement()) {
                s.execute("DELETE FROM gps_nodes WHERE id=" + id);
                s.execute("DELETE FROM gps_edges WHERE source_id=" + id + " OR target_id=" + id);
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}