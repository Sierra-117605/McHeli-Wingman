package com.mcheliwingman.util;

import net.minecraft.entity.Entity;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static registry of entity IDs currently under active UAV camera control.
 *
 * Written by UavChunkStreamer on Phase.START (server thread) and read from
 * EntityTrackerEntry.func_180233_c (isVisibleTo), which is also called on the
 * server thread. ConcurrentHashMap is used defensively.
 *
 * The ASM patch in WingmanTransformer inserts a call to {@link #isUavControlled}
 * at the top of EntityTrackerEntry.func_180233_c so that isVisibleTo always
 * returns true for actively-controlled UAV aircraft, preventing
 * SPacketDestroyEntities from being sent regardless of distance.
 */
public class WingmanUavRegistry {

    private static final Set<Integer> uavEntityIds =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Mark an entity as UAV-controlled (or remove that mark). */
    public static void setUavControlled(int entityId, boolean controlled) {
        if (controlled) {
            uavEntityIds.add(entityId);
        } else {
            uavEntityIds.remove(entityId);
        }
    }

    /**
     * Called from ASM-injected code inside EntityTrackerEntry.func_180233_c.
     * Returns true → isVisibleTo returns true → player stays in trackingPlayers.
     */
    public static boolean isUavControlled(Entity entity) {
        return entity != null && uavEntityIds.contains(entity.getEntityId());
    }
}
