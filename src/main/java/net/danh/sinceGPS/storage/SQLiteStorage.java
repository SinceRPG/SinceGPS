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
    private Connection connection;
    private String tableNodes;
    private String tableEdges;

    public SQLiteStorage(SinceGPS plugin) {
        this.plugin = plugin;
        initialize();
    }

    private void initialize() {
        // Load tên file và prefix từ config
        String fileName = plugin.getCfg().getString("database.file", "database.db");
        String prefix = plugin.getCfg().getString("database.table-prefix", "sincegps_");

        this.tableNodes = prefix + "nodes";
        this.tableEdges = prefix + "edges";

        File dataFolder = new File(plugin.getDataFolder(), fileName);
        if (!dataFolder.exists()) {
            try {
                dataFolder.getParentFile().mkdirs();
                dataFolder.createNewFile();
            } catch (Exception e) {
                plugin.getLogger().severe("Không thể tạo file database!");
            }
        }

        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dataFolder.getAbsolutePath());
            createTables();
        } catch (Exception e) {
            plugin.getLogger().severe("Lỗi kết nối SQLite: " + e.getMessage());
        }
    }

    private void createTables() {
        String nodeTable = "CREATE TABLE IF NOT EXISTS " + tableNodes + " (" +
                "id INTEGER PRIMARY KEY," +
                "name TEXT," +
                "display_name TEXT," +
                "world TEXT," +
                "x DOUBLE," +
                "y DOUBLE," +
                "z DOUBLE," +
                "group_name TEXT" +
                ");";

        String edgeTable = "CREATE TABLE IF NOT EXISTS " + tableEdges + " (" +
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

    public Map<Integer, Node> loadNodes() {
        Map<Integer, Node> nodes = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM " + tableNodes)) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                int id = rs.getInt("id");
                String worldName = rs.getString("world");
                if (Bukkit.getWorld(worldName) == null) continue;

                Location loc = new Location(Bukkit.getWorld(worldName), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"));
                Node node = new Node(id, loc, rs.getString("group_name"));
                node.setName(rs.getString("name"));
                node.setDisplayName(rs.getString("display_name"));
                nodes.put(id, node);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Lỗi load nodes", e);
        }

        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM " + tableEdges)) {
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

    public void saveAll(Map<Integer, Node> nodes) {
        String insertNode = "REPLACE INTO " + tableNodes + " (id, name, display_name, world, x, y, z, group_name) VALUES(?,?,?,?,?,?,?,?)";
        String insertEdge = "REPLACE INTO " + tableEdges + " (source_id, target_id, weight) VALUES(?,?,?)";

        try {
            connection.setAutoCommit(false);
            try (PreparedStatement psNode = connection.prepareStatement(insertNode);
                 PreparedStatement psEdge = connection.prepareStatement(insertEdge)) {

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
            connection.commit();
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

    public void deleteNode(int id) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Statement s = connection.createStatement()) {
                s.execute("DELETE FROM " + tableNodes + " WHERE id=" + id);
                s.execute("DELETE FROM " + tableEdges + " WHERE source_id=" + id + " OR target_id=" + id);
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