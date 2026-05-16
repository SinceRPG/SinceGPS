package net.danh.sinceGPS.storage;

import net.danh.sinceGPS.SinceGPS;
import net.danh.sinceGPS.core.Node;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class SQLiteStorage {
    private final SinceGPS plugin;
    private final Object lock = new Object();
    private Connection connection;
    private String tableNodes;
    private String tableEdges;

    public SQLiteStorage(SinceGPS plugin) {
        this.plugin = plugin;
        initialize();
    }

    private void initialize() {
        String fileName = plugin.getCfg().getString("database.file", "database.db");
        String prefix = plugin.getCfg().getString("database.table-prefix", "sincegps_");

        this.tableNodes = prefix + "nodes";
        this.tableEdges = prefix + "edges";

        File databaseFile = new File(plugin.getDataFolder(), fileName);
        if (!databaseFile.exists()) {
            try {
                File parent = databaseFile.getParentFile();
                if (parent != null) parent.mkdirs();
                databaseFile.createNewFile();
            } catch (IOException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not create SQLite database file.", exception);
            }
        }

        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
            createTables();
        } catch (ReflectiveOperationException | SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not connect to SQLite.", exception);
        }
    }

    private void createTables() {
        String nodeTable = "CREATE TABLE IF NOT EXISTS " + tableNodes + " ("
                + "id INTEGER PRIMARY KEY,"
                + "name TEXT NOT NULL,"
                + "display_name TEXT,"
                + "world TEXT NOT NULL,"
                + "x DOUBLE,"
                + "y DOUBLE,"
                + "z DOUBLE,"
                + "group_name TEXT"
                + ");";

        String edgeTable = "CREATE TABLE IF NOT EXISTS " + tableEdges + " ("
                + "source_id INTEGER,"
                + "target_id INTEGER,"
                + "weight DOUBLE,"
                + "PRIMARY KEY (source_id, target_id)"
                + ");";

        synchronized (lock) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(nodeTable);
                statement.execute(edgeTable);
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not create SQLite tables.", exception);
            }
        }
    }

    public Map<Integer, Node> loadNodes() {
        Map<Integer, Node> nodes = new HashMap<>();
        synchronized (lock) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + tableNodes);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    int id = result.getInt("id");
                    World world = Bukkit.getWorld(result.getString("world"));
                    if (world == null) continue;

                    Location location = new Location(world, result.getDouble("x"), result.getDouble("y"), result.getDouble("z"));
                    Node node = new Node(id, location, result.getString("group_name"));
                    node.setName(result.getString("name"));
                    node.setDisplayName(result.getString("display_name"));
                    nodes.put(id, node);
                }
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not load GPS nodes.", exception);
            }

            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + tableEdges);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    int sourceId = result.getInt("source_id");
                    int targetId = result.getInt("target_id");
                    Node source = nodes.get(sourceId);
                    if (source != null && nodes.containsKey(targetId)) {
                        source.connect(targetId, result.getDouble("weight"));
                    }
                }
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not load GPS edges.", exception);
            }
        }
        return nodes;
    }

    public void saveAll(Map<Integer, Node> nodes) {
        String insertNode = "INSERT OR REPLACE INTO " + tableNodes
                + " (id, name, display_name, world, x, y, z, group_name) VALUES(?,?,?,?,?,?,?,?)";
        String insertEdge = "INSERT OR REPLACE INTO " + tableEdges
                + " (source_id, target_id, weight) VALUES(?,?,?)";

        synchronized (lock) {
            try {
                connection.setAutoCommit(false);
                try (Statement clear = connection.createStatement();
                     PreparedStatement nodeStatement = connection.prepareStatement(insertNode);
                     PreparedStatement edgeStatement = connection.prepareStatement(insertEdge)) {
                    clear.executeUpdate("DELETE FROM " + tableEdges);
                    clear.executeUpdate("DELETE FROM " + tableNodes);

                    for (Node node : nodes.values()) {
                        Location location = node.getLocation();
                        if (location.getWorld() == null) continue;

                        nodeStatement.setInt(1, node.getId());
                        nodeStatement.setString(2, node.getName());
                        nodeStatement.setString(3, node.getDisplayName());
                        nodeStatement.setString(4, location.getWorld().getName());
                        nodeStatement.setDouble(5, location.getX());
                        nodeStatement.setDouble(6, location.getY());
                        nodeStatement.setDouble(7, location.getZ());
                        nodeStatement.setString(8, node.getGroup());
                        nodeStatement.addBatch();

                        for (Map.Entry<Integer, Double> edge : node.getEdges().entrySet()) {
                            edgeStatement.setInt(1, node.getId());
                            edgeStatement.setInt(2, edge.getKey());
                            edgeStatement.setDouble(3, edge.getValue());
                            edgeStatement.addBatch();
                        }
                    }

                    nodeStatement.executeBatch();
                    edgeStatement.executeBatch();
                }
                connection.commit();
            } catch (SQLException exception) {
                rollback();
                plugin.getLogger().log(Level.SEVERE, "Could not save GPS graph.", exception);
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException exception) {
                    plugin.getLogger().log(Level.WARNING, "Could not restore SQLite autocommit.", exception);
                }
            }
        }
    }

    public void deleteNode(int id) {
        String deleteNode = "DELETE FROM " + tableNodes + " WHERE id=?";
        String deleteEdges = "DELETE FROM " + tableEdges + " WHERE source_id=? OR target_id=?";
        plugin.getSchedulerAdapter().runAsync(() -> {
            synchronized (lock) {
                try (PreparedStatement nodeStatement = connection.prepareStatement(deleteNode);
                     PreparedStatement edgeStatement = connection.prepareStatement(deleteEdges)) {
                    nodeStatement.setInt(1, id);
                    nodeStatement.executeUpdate();
                    edgeStatement.setInt(1, id);
                    edgeStatement.setInt(2, id);
                    edgeStatement.executeUpdate();
                } catch (SQLException exception) {
                    plugin.getLogger().log(Level.SEVERE, "Could not delete GPS node " + id + ".", exception);
                }
            }
        });
    }

    private void rollback() {
        try {
            connection.rollback();
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not roll back SQLite transaction.", exception);
        }
    }

    public void close() {
        synchronized (lock) {
            try {
                if (connection != null && !connection.isClosed()) connection.close();
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not close SQLite connection.", exception);
            }
        }
    }
}
