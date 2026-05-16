package net.danh.sinceGPS.manager;

import net.danh.sinceGPS.core.Node;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public class PathFinder {
    private PathFinder() {
    }

    public static List<Location> findPath(Node start, Node end, GraphManager graph) {
        if (start == null || end == null) return null;
        if (start.getLocation().getWorld() == null || end.getLocation().getWorld() == null
                || !start.getLocation().getWorld().equals(end.getLocation().getWorld())) {
            return null;
        }

        Map<Integer, Double> gScore = new HashMap<>();
        Map<Integer, Double> fScore = new HashMap<>();
        Map<Integer, Integer> cameFrom = new HashMap<>();
        Set<Integer> closed = new HashSet<>();
        PriorityQueue<Integer> open = new PriorityQueue<>(
                Comparator.comparingDouble(id -> fScore.getOrDefault(id, Double.MAX_VALUE)));

        gScore.put(start.getId(), 0.0);
        fScore.put(start.getId(), start.getLocation().distance(end.getLocation()));
        open.add(start.getId());

        while (!open.isEmpty()) {
            int currentId = open.poll();
            if (!closed.add(currentId)) continue;
            if (currentId == end.getId()) return reconstruct(cameFrom, end, graph);

            Node current = graph.getNode(currentId);
            if (current == null) continue;

            for (Map.Entry<Integer, Double> edge : current.getEdges().entrySet()) {
                int neighborId = edge.getKey();
                Node neighbor = graph.getNode(neighborId);
                if (neighbor == null || closed.contains(neighborId)) continue;

                double tentativeG = gScore.get(currentId) + edge.getValue();
                if (tentativeG < gScore.getOrDefault(neighborId, Double.MAX_VALUE)) {
                    cameFrom.put(neighborId, currentId);
                    gScore.put(neighborId, tentativeG);
                    fScore.put(neighborId, tentativeG + neighbor.getLocation().distance(end.getLocation()));
                    open.add(neighborId);
                }
            }
        }
        return null;
    }

    private static List<Location> reconstruct(Map<Integer, Integer> cameFrom, Node current, GraphManager graph) {
        List<Location> path = new LinkedList<>();
        path.add(current.getLocation());
        int currentId = current.getId();
        while (cameFrom.containsKey(currentId)) {
            currentId = cameFrom.get(currentId);
            Node node = graph.getNode(currentId);
            if (node == null) break;
            path.add(0, node.getLocation());
        }
        return path;
    }

    public static List<Location> smoothPath(List<Location> points, int quality) {
        if (points.size() < 2) return List.copyOf(points);

        int safeQuality = Math.max(1, quality);
        List<Location> smooth = new ArrayList<>();
        List<Vector> vectors = new ArrayList<>();

        Vector first = points.get(0).toVector();
        Vector second = points.get(1).toVector();
        vectors.add(first.clone().subtract(second.clone().subtract(first)));
        for (Location location : points) vectors.add(location.toVector());

        Vector last = points.get(points.size() - 1).toVector();
        Vector beforeLast = points.get(points.size() - 2).toVector();
        vectors.add(last.clone().add(last.clone().subtract(beforeLast)));

        for (int i = 0; i < vectors.size() - 3; i++) {
            for (int j = 0; j < safeQuality; j++) {
                double t = (double) j / safeQuality;
                Vector vector = getCatmullRom(t, vectors.get(i), vectors.get(i + 1), vectors.get(i + 2), vectors.get(i + 3));
                smooth.add(new Location(points.get(0).getWorld(), vector.getX(), vector.getY(), vector.getZ()));
            }
        }
        smooth.add(points.get(points.size() - 1).clone());
        return smooth;
    }

    public static double estimateDistance(List<Location> path) {
        double distance = 0.0;
        for (int i = 1; i < path.size(); i++) {
            Location previous = path.get(i - 1);
            Location current = path.get(i);
            if (previous.getWorld() != null && previous.getWorld().equals(current.getWorld())) {
                distance += previous.distance(current);
            }
        }
        return distance;
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
