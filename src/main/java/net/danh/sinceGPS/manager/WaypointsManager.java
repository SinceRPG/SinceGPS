package net.danh.sinceGPS.manager;

import net.danh.sinceGPS.SinceGPS;
import net.danh.sinceGPS.utils.ConfigUtils;
import org.bukkit.Location;

import java.util.Collections;
import java.util.Set;

public class WaypointsManager {
    private final ConfigUtils dataFile;

    public WaypointsManager(SinceGPS plugin) {
        this.dataFile = new ConfigUtils(plugin, "waypoints.yml");
    }

    public void reload() {
        dataFile.reload();
    }

    public void setWaypoint(String name, Location loc) {
        dataFile.setAndSave(name, loc);
    }

    public Location getWaypoint(String name) {
        return dataFile.getLocation(name);
    }

    public Set<String> getWaypointNames() {
        if (dataFile.getConfig().getKeys(false) == null) return Collections.emptySet();
        return dataFile.getConfig().getKeys(false);
    }
}