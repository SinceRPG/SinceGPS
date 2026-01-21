package net.danh.sinceGPS.manager;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

public class BezierUtil {
    // Làm mịn đường đi bằng thuật toán Quadratic Bezier (đơn giản hóa)
    public static List<Location> smooth(List<Location> input) {
        if (input.size() < 3) return input;
        List<Location> output = new ArrayList<>();
        output.add(input.get(0));

        for (int i = 0; i < input.size() - 2; i++) {
            Location p0 = input.get(i);
            Location p1 = input.get(i + 1); // Điểm góc (corner)
            Location p2 = input.get(i + 2);

            // Nội suy từ điểm giữa p0-p1 đến p1-p2
            // Tăng bước nhảy (step) để đường mịn hơn
            for (double t = 0.1; t <= 0.9; t += 0.1) {
                double x = Math.pow(1 - t, 2) * p0.getX() + 2 * (1 - t) * t * p1.getX() + t * t * p2.getX();
                double y = Math.pow(1 - t, 2) * p0.getY() + 2 * (1 - t) * t * p1.getY() + t * t * p2.getY();
                double z = Math.pow(1 - t, 2) * p0.getZ() + 2 * (1 - t) * t * p1.getZ() + t * t * p2.getZ();
                output.add(new Location(p0.getWorld(), x, y, z));
            }
        }
        output.add(input.get(input.size() - 1));
        return output;
    }
}