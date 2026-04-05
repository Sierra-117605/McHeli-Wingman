package com.mcheliwingman.config;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

public class WingmanConfig {

    public static int uavControllerRange = 99999;
    public static int uavMaxDistance = 99999;
    public static boolean forceChunkload = false;

    /** Lateral distance from leader centre-line (blocks). */
    public static double formationSideDist = 20.0;
    /** Altitude offset relative to leader Y (blocks). Positive = above leader. */
    public static double formationAltOffset = 0.0;
    /** Distance behind the leader (blocks). */
    public static double formationRearDist = 30.0;
    /** Maximum wingmen per aircraft. Runtime-writable by /wingman maxwings. */
    public static int maxWingmen = 10;

    public static void load(File configFile) {
        Configuration cfg = new Configuration(configFile);
        cfg.load();

        uavControllerRange = cfg.getInt(
            "controllerRange", "uav", 99999, -1, Integer.MAX_VALUE,
            "UAV controller effective range in blocks. -1 for unlimited."
        );
        uavMaxDistance = cfg.getInt(
            "maxDistance", "uav", 99999, -1, Integer.MAX_VALUE,
            "UAV maximum flight distance in blocks. -1 for unlimited."
        );
        forceChunkload = cfg.getBoolean(
            "forceChunkload", "uav", false,
            "Force chunk loading around UAVs. Warning: may increase server load."
        );
        formationSideDist = cfg.getFloat(
            "formationSideDist", "formation", 20.0f, 1.0f, 500.0f,
            "Lateral distance from the leader centre-line (blocks)."
        );
        formationAltOffset = cfg.getFloat(
            "formationAltOffset", "formation", 0.0f, -500.0f, 500.0f,
            "Altitude offset relative to leader Y (blocks). Positive = above, negative = below."
        );
        formationRearDist = cfg.getFloat(
            "formationRearDist", "formation", 30.0f, 1.0f, 500.0f,
            "Distance behind the leader (blocks)."
        );
        maxWingmen = cfg.getInt(
            "maxWingmen", "formation", 10, 1, 64,
            "Maximum wingmen per aircraft."
        );

        if (cfg.hasChanged()) {
            cfg.save();
        }
    }
}
