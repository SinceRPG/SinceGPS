package net.danh.sinceGPS.pathfinding;

import net.danh.sinceGPS.SinceGPS;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.TrapDoor;

import java.util.*;

public class SmartPathfinder {
    // 4 Hướng: Bắc, Nam, Đông, Tây
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private final SinceGPS plugin;

    public SmartPathfinder(SinceGPS plugin) {
        this.plugin = plugin;
    }

    public List<Location> findPath(Location start, Location end) {
        if (!start.getWorld().equals(end.getWorld())) return null;
        World w = start.getWorld();

        Node startNode = new Node(start.getBlockX(), start.getBlockY(), start.getBlockZ());
        Node endNode = new Node(end.getBlockX(), end.getBlockY(), end.getBlockZ());

        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<String, Node> allNodes = new HashMap<>();

        startNode.g = 0;
        startNode.h = heuristic(startNode, endNode);
        open.add(startNode);
        allNodes.put(startNode.key(), startNode);

        int maxNodes = plugin.getSettingsConfig().getInt("settings.max-nodes", 12000);
        int iterations = 0;

        while (!open.isEmpty()) {
            if (iterations++ > maxNodes) return null;

            Node current = open.poll();

            // Kiểm tra đến đích (khoảng cách < 2 block)
            if (current.distSq(endNode) < 4) {
                return reconstructPath(current, end);
            }

            for (Node neighbor : getNeighbors(current, w)) {
                Node existing = allNodes.getOrDefault(neighbor.key(), neighbor);

                // G là chi phí đi từ điểm bắt đầu đến điểm này
                // Cost di chuyển: Đi thẳng = 1, Nhảy/Rơi = 1 (để khuyến khích tìm đường tự nhiên)
                double newG = current.g + 1.0;

                if (newG < existing.g) {
                    existing.g = newG;
                    existing.h = heuristic(existing, endNode);
                    existing.parent = current;

                    if (!open.contains(existing)) {
                        open.add(existing);
                        allNodes.put(existing.key(), existing);
                    }
                }
            }
        }
        return null;
    }

    private List<Node> getNeighbors(Node p, World w) {
        List<Node> list = new ArrayList<>();

        for (int[] d : DIRS) {
            int nx = p.x + d[0];
            int nz = p.z + d[1];

            // [QUAN TRỌNG] Kiểm tra Chunk đã load để tránh crash/lag server
            if (!w.isChunkLoaded(nx >> 4, nz >> 4)) continue;

            // --- LOGIC DI CHUYỂN GIỐNG NGƯỜI CHƠI ---

            // 1. ĐI BỘ (Walk): Y giữ nguyên
            // Yêu cầu:
            // - Block tại chân (nx, y, nz) phải đi qua được (Khí, Cỏ, Hoa...)
            // - Block tại đầu (nx, y+1, nz) phải thoáng
            // - Block dưới chân (nx, y-1, nz) phải đặc (Solid) để đứng lên
            if (isPassable(w, nx, p.y, nz) && isPassable(w, nx, p.y + 1, nz) && isSolid(w, nx, p.y - 1, nz)) {
                list.add(new Node(nx, p.y, nz));
            }

            // 2. NHẢY LÊN (Jump): Y + 1
            // Yêu cầu:
            // - Block trước mặt (nx, y, nz) là vật cản (Solid) nên phải nhảy lên
            // - Đích đến (nx, y+1, nz) phải thoáng (chỗ đứng mới)
            // - Đầu ở đích đến (nx, y+2, nz) phải thoáng
            // - [QUAN TRỌNG] Đầu ở vị trí hiện tại (p.x, p.y+2, p.z) phải thoáng để có đà nhảy
            else if (isSolid(w, nx, p.y, nz) && isPassable(w, nx, p.y + 1, nz) && isPassable(w, nx, p.y + 2, nz)) {
                // Kiểm tra xem vị trí hiện tại có bị cụng đầu không
                if (isPassable(w, p.x, p.y + 2, p.z)) {
                    list.add(new Node(nx, p.y + 1, nz));
                }
            }

            // 3. RƠI XUỐNG (Drop): Y - 1 đến Y - 3
            // Yêu cầu:
            // - Block trước mặt (nx, y, nz) phải thoáng (để bước ra)
            // - Block dưới chân trước mặt (nx, y-1, nz) là thoáng (thì mới rơi được)
            // - Tìm điểm chạm đất an toàn trong phạm vi 3 block
            else if (isPassable(w, nx, p.y, nz) && isPassable(w, nx, p.y + 1, nz) && !isSolid(w, nx, p.y - 1, nz)) {
                // Duyệt xuống dưới tối đa 3 block (Giới hạn an toàn không mất máu)
                for (int drop = 1; drop <= 3; drop++) {
                    int targetY = p.y - drop;

                    // Nếu block này là vật cản -> Không rơi được nữa -> Dừng
                    if (isSolid(w, nx, targetY, nz)) {
                        // Điểm đứng là block ngay trên block solid này (targetY + 1)
                        // Kiểm tra không gian đầu (targetY + 2)
                        if (isPassable(w, nx, targetY + 1, nz) && isPassable(w, nx, targetY + 2, nz)) {
                            // Đã tìm thấy điểm tiếp đất tại (targetY + 1)
                            // Vị trí node là nx, p.y - drop + 1, nz.
                            // Ví dụ: Solid ở y-1 -> Đứng ở y. Solid ở y-2 -> Đứng ở y-1
                            list.add(new Node(nx, targetY + 1, nz));
                        }
                        break; // Đã chạm đất, không check sâu hơn
                    }
                }
            }
        }
        return list;
    }

    // Kiểm tra block có thể đi xuyên qua (Khí, nước, cỏ, hoa, cửa mở...)
    private boolean isPassable(World w, int x, int y, int z) {
        Block b = w.getBlockAt(x, y, z);
        Material type = b.getType();

        // Tránh nguy hiểm
        if (type == Material.LAVA || type == Material.FIRE || type == Material.MAGMA_BLOCK || type == Material.SWEET_BERRY_BUSH || type == Material.CACTUS) {
            return false;
        }

        // Cửa/Cổng mở hoặc Trapdoor -> Coi như đi qua được
        if (isDoor(b)) return true;

        // Block không đặc (Solid) -> Đi qua được
        return !type.isSolid();
    }

    // Kiểm tra block có thể đứng lên được
    private boolean isSolid(World w, int x, int y, int z) {
        Block b = w.getBlockAt(x, y, z);
        Material type = b.getType();

        // Cửa đóng, Trapdoor đóng -> Có thể đứng lên (hoặc nhảy lên)
        if (isDoor(b)) return true;

        // Block đặc, nhưng loại trừ các block đặc nguy hiểm
        return type.isSolid() && type != Material.CACTUS && type != Material.MAGMA_BLOCK;
    }

    // Check Cửa/Trapdoor/Cổng rào
    private boolean isDoor(Block b) {
        return b.getBlockData() instanceof Openable || b.getBlockData() instanceof Gate || b.getBlockData() instanceof TrapDoor;
    }

    // Heuristic: Manhattan Distance (Tốt cho môi trường Blocky như Minecraft)
    // Tính cả trục Y để tìm đường lên/xuống hiệu quả
    private double heuristic(Node a, Node b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y) + Math.abs(a.z - b.z);
    }

    private List<Location> reconstructPath(Node n, Location end) {
        LinkedList<Location> path = new LinkedList<>();
        Node c = n;
        while (c != null) {
            // Thêm 0.5 để vào giữa block
            path.addFirst(new Location(end.getWorld(), c.x + 0.5, c.y, c.z + 0.5));
            c = c.parent;
        }
        // Thêm điểm cuối cùng chính xác
        path.addLast(end.clone());
        return path;
    }

    private static class Node implements Comparable<Node> {
        final int x, y, z;
        double g = Double.MAX_VALUE; // Chi phí từ start
        double h = 0; // Chi phí ước tính đến end
        Node parent;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        String key() {
            return x + "," + y + "," + z;
        }

        double f() {
            return g + h;
        }

        double distSq(Node o) {
            return Math.pow(x - o.x, 2) + Math.pow(y - o.y, 2) + Math.pow(z - o.z, 2);
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(this.f(), o.f());
        }
    }
}