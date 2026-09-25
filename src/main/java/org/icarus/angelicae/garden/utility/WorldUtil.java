package org.icarus.angelicae.garden.utility;

import org.icarus.angelicae.garden.configuration.GardenConfig;
import org.bukkit.Location;

public class WorldUtil {

    public static boolean isBlacklistedWorld(Location location) {
        return GardenConfig.instance().blacklistedWorlds().contains(location.getWorld().getName());
    }
}
