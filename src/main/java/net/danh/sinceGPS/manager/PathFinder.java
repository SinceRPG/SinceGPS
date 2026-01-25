package net.danh.sinceGPS.manager;

import net.danh.sinceGPS.core.Node;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.*;

public class PathFinder {
    public static List<Location> findPath(Node start, Node end, GraphManager graph) {
        Map<Integer, Double> gScore = new HashMap<>();
        Map<Integer, Double> fScore = new HashMap<>();
        Map<Integer, Integer> cameFrom = new HashMap<>();
        PriorityQueue<Integer> open = new PriorityQueue<>(Comparator.comparingDouble(id -> fScore.getOrDefault(id, Double.MAX_VALUE)));

        gScore.put(start.getId(), 0.0);
        fScore.put(start.getId(), start.getLocation().distance(end.getLocation()));
        open.add(start.getId());

        while (!open.isEmpty()) {
            int currentId = open.poll();
            if (currentId == end.getId()) return reconstruct(cameFrom, end, graph);

            Node current = graph.getNode(currentId);
            if (current == null) continue;

            for (Map.Entry<Integer, Double> edge : current.getEdges().entrySet()) {
                int neighborId = edge.getKey();
                if (!graph.getNodes().contains(graph.getNode(neighborId))) continue;

                double tentativeG = gScore.get(currentId) + edge.getValue();
                if (tentativeG < gScore.getOrDefault(neighborId, Double.MAX_VALUE)) {
                    cameFrom.put(neighborId, currentId);
                    gScore.put(neighborId, tentativeG);
                    fScore.put(neighborId, tentativeG + graph.getNode(neighborId).getLocation().distance(end.getLocation()));
                    if (!open.contains(neighborId)) open.add(neighborId);
                }
            }
        }
        return null;
    }

    private static List<Location> reconstruct(Map<Integer, Integer> cameFrom, Node current, GraphManager graph) {
        List<Location> path = new LinkedList<>();
        path.add(current.getLocation());
        int currId = current.getId();
        while (cameFrom.containsKey(currId)) {
            currId = cameFrom.get(currId);
            path.add(0, graph.getNode(currId).getLocation()); // Safe for Java 8+
        }
        return path;
    }

    public static List<Location> smoothPath(List<Location> points, int quality) {
        if (points.size() < 2) return points;
        List<Location> smooth = new ArrayList<>();
        List<Vector> vectors = new ArrayList<>();

        vectors.add(points.get(0).toVector().subtract(points.get(1).toVector().subtract(points.get(0).toVector())));
        for (Location l : points) vectors.add(l.toVector());
        vectors.add(points.get(points.size() - 1).toVector().add(points.get(points.size() - 1).toVector().subtract(points.get(points.size() - 2).toVector())));

        for (int i = 0; i < vectors.size() - 3; i++) {
            for (int j = 0; j < quality; j++) {
                double t = (double) j / quality;
                Vector v = getCatmullRom(t, vectors.get(i), vectors.get(i + 1), vectors.get(i + 2), vectors.get(i + 3));
                smooth.add(new Location(points.get(0).getWorld(), v.getX(), v.getY(), v.getZ()));
            }
        }
        smooth.add(points.get(points.size() - 1));
        return smooth;
    }

    private static Vector getCatmullRom(double t, Vector p0, Vector p1, Vector p2, Vector p3) {
        double t2 = t * t;
        double t3 = t2 * t;
        return p0.clone().multiply(-0.5 * t3 + t2 - 0.5 * t)
                .add(p1.clone().multiply(1.5 * t3 - 2.5 * t2 + 1.0))
                .add(p2.clone().multiply(-1.5 * t3 + 2.0 * t2 + 0.5 * t))
                .add(p3.clone().multiply(0.5 * t3 - 0.5 * t2));
    }
}