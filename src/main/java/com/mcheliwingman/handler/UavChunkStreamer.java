package com.mcheliwingman.handler;

import com.mcheliwingman.McHeliWingman;
import com.mcheliwingman.util.McheliReflect;
import com.mcheliwingman.util.WingmanUavRegistry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Two responsibilities:
 *
 * A) EntityTrackerEntry.func_180233_c (isVisibleTo) ASM patch
 *    WingmanTransformer inserts a call to WingmanUavRegistry.isUavControlled()
 *    at the top of func_180233_c. If the entity is in the UAV registry, the
 *    method immediately returns true, keeping the player in trackingPlayers
 *    regardless of distance, preventing SPacketDestroyEntities.
 *
 * B) PlayerChunkMap subscriptions
 *    Subscribes the controlling player to chunks around the UAV aircraft that
 *    lie OUTSIDE their normal render distance so the client receives SPacketChunkData
 *    for those chunks (preventing WorldClient from unloading entities there).
 *    Uses Phase.END so entity positions are final for that tick.
 *    Only manages chunks OUTSIDE the player's view radius to avoid sending
 *    spurious SPacketUnloadChunk for in-view chunks (which would cause warps).
 */
public class UavChunkStreamer {

    /** 5×5 area around the aircraft's current chunk. */
    private static final int CHUNK_RADIUS = 2;

    // -------------------------------------------------------------------------
    // Chunk subscription state
    // -------------------------------------------------------------------------

    /** Out-of-view chunk subscriptions we have added, keyed by aircraft UUID. */
    private final Map<UUID, Set<ChunkPos>> subscribed = new HashMap<>();

    /** The player subscribed on behalf of each aircraft UUID. */
    private final Map<UUID, EntityPlayerMP> subscribedPlayer = new HashMap<>();

    // -------------------------------------------------------------------------
    // Reflection caches — resolved once
    // -------------------------------------------------------------------------

    // PlayerChunkMap.playerViewRadius  (SRG: field_72698_e)
    private Field viewRadiusField = null;

    // PlayerChunkMap.getOrCreateEntry(int,int)  (SRG: func_187302_c)
    private Method getOrCreateEntryMethod = null;

    // EntityTracker.trackedEntityHashTable  (SRG: field_72794_c)
    private Field entityTrackerMapField = null;

    // IntHashMap.lookup(int)  (SRG: func_76041_a)
    private Method intHashMapLookupMethod = null;

    // EntityTrackerEntry.trackingRange  (SRG: field_73130_b) — final int
    private Field trackingRangeField = null;

    // EntityTrackerEntry.maxTrackingDistanceThreshold  (SRG: field_187262_f)
    private Field maxThresholdField = null;

    private static final int UNLIMITED_RANGE = Integer.MAX_VALUE >> 1;

    // =========================================================================
    // Tick handler
    // =========================================================================

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.world.isRemote) return;
        WorldServer ws = (WorldServer) event.world;

        if (event.phase == TickEvent.Phase.START) {
            // Must run BEFORE EntityTracker.updateTrackedEntities() (which runs
            // during WorldServer.tick(), i.e. between Phase.START and Phase.END).
            tickExpandTrackingRanges(ws);
        } else {
            // Phase.END: entity positions are final for this tick.
            tickChunkSubscriptions(ws);
        }
    }

    // =========================================================================
    // Phase.START — update WingmanUavRegistry (read by ASM-patched isVisibleTo)
    // =========================================================================

    /** Entity IDs we registered this tick (to detect removals). */
    private final Set<Integer> registeredIds = new HashSet<>();

    private void tickExpandTrackingRanges(WorldServer ws) {
        Set<Integer> activeIds = new HashSet<>();

        for (Entity entity : new ArrayList<>(ws.loadedEntityList)) {
            if (!McheliReflect.isAircraft(entity) || entity.isDead) continue;

            Object station = McheliReflect.getUavStation(entity);
            if (station == null) continue;

            int id = entity.getEntityId();
            activeIds.add(id);
            if (!registeredIds.contains(id)) {
                WingmanUavRegistry.setUavControlled(id, true);
                McHeliWingman.logger.info("[UavChunkStreamer] UAV registered: entityId={}", id);
            }

            // Expand tracking range fields every tick so isVisibleTo() always returns true.
            expandTrackingRange(ws, entity);
        }

        // Remove IDs that are no longer under UAV control.
        for (Integer id : registeredIds) {
            if (!activeIds.contains(id)) {
                WingmanUavRegistry.setUavControlled(id, false);
                McHeliWingman.logger.info("[UavChunkStreamer] UAV unregistered: entityId={}", id);
            }
        }
        registeredIds.clear();
        registeredIds.addAll(activeIds);
    }

    /**
     * Expand both EntityTrackerEntry range fields for the given aircraft so
     * EntityTracker.isVisibleTo() always returns true regardless of distance.
     *
     * field_73130_b  (MCP: trackingRange)               — final int, must strip modifier
     * field_187262_f (MCP: maxTrackingDistanceThreshold) — non-final int
     *
     * Math.min(field_73130_b, field_187262_f) is the effective limit; both must be large.
     */
    private void expandTrackingRange(WorldServer ws, Entity entity) {
        try {
            net.minecraft.entity.EntityTracker tracker = ws.getEntityTracker();

            // Resolve EntityTracker.field_72794_c (trackedEntityHashTable)
            if (entityTrackerMapField == null) {
                for (String name : new String[]{"trackedEntityHashTable", "field_72794_c"}) {
                    try {
                        Field f = net.minecraft.entity.EntityTracker.class.getDeclaredField(name);
                        f.setAccessible(true);
                        entityTrackerMapField = f;
                        McHeliWingman.logger.info("[UavChunkStreamer] Resolved tracker map field: {}", name);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
            }
            if (entityTrackerMapField == null) {
                McHeliWingman.logger.warn("[UavChunkStreamer] Could not resolve tracker map field");
                return;
            }

            Object hashMap = entityTrackerMapField.get(tracker);
            if (hashMap == null) return;

            // Resolve IntHashMap.func_76041_a(int) (lookup)
            if (intHashMapLookupMethod == null) {
                for (String name : new String[]{"lookup", "func_76041_a"}) {
                    try {
                        Method m = hashMap.getClass().getDeclaredMethod(name, int.class);
                        m.setAccessible(true);
                        intHashMapLookupMethod = m;
                        McHeliWingman.logger.info("[UavChunkStreamer] Resolved IntHashMap lookup: {}", name);
                        break;
                    } catch (NoSuchMethodException ignored) {}
                }
            }
            if (intHashMapLookupMethod == null) {
                McHeliWingman.logger.warn("[UavChunkStreamer] Could not resolve IntHashMap lookup");
                return;
            }

            Object entry = intHashMapLookupMethod.invoke(hashMap, entity.getEntityId());
            if (entry == null) return;

            // Resolve field_73130_b (trackingRange, final int)
            if (trackingRangeField == null) {
                Class<?> cls = entry.getClass();
                while (cls != null) {
                    for (String name : new String[]{"trackingRange", "field_73130_b"}) {
                        try {
                            Field f = cls.getDeclaredField(name);
                            f.setAccessible(true);
                            // Strip final so we can write to it
                            Field modifiers = Field.class.getDeclaredField("modifiers");
                            modifiers.setAccessible(true);
                            modifiers.setInt(f, f.getModifiers() & ~Modifier.FINAL);
                            trackingRangeField = f;
                            McHeliWingman.logger.info("[UavChunkStreamer] Resolved trackingRange field: {}", name);
                            break;
                        } catch (NoSuchFieldException ignored) {}
                    }
                    if (trackingRangeField != null) break;
                    cls = cls.getSuperclass();
                }
            }

            // Resolve field_187262_f (maxTrackingDistanceThreshold)
            if (maxThresholdField == null) {
                Class<?> cls = entry.getClass();
                while (cls != null) {
                    for (String name : new String[]{"maxTrackingDistanceThreshold", "field_187262_f"}) {
                        try {
                            Field f = cls.getDeclaredField(name);
                            f.setAccessible(true);
                            maxThresholdField = f;
                            McHeliWingman.logger.info("[UavChunkStreamer] Resolved maxThreshold field: {}", name);
                            break;
                        } catch (NoSuchFieldException ignored) {}
                    }
                    if (maxThresholdField != null) break;
                    cls = cls.getSuperclass();
                }
            }

            if (trackingRangeField != null) {
                trackingRangeField.setInt(entry, UNLIMITED_RANGE);
            } else {
                McHeliWingman.logger.warn("[UavChunkStreamer] trackingRangeField still null");
            }
            if (maxThresholdField != null) {
                maxThresholdField.setInt(entry, UNLIMITED_RANGE);
            } else {
                McHeliWingman.logger.warn("[UavChunkStreamer] maxThresholdField still null");
            }

        } catch (Exception e) {
            McHeliWingman.logger.warn("[UavChunkStreamer] expandTrackingRange failed: {}", e.toString());
        }
    }

    // =========================================================================
    // Phase.END — PlayerChunkMap subscriptions
    // =========================================================================

    private void tickChunkSubscriptions(WorldServer ws) {
        PlayerChunkMap pcm = ws.getPlayerChunkMap();
        int viewRadius = getViewRadius(pcm);

        Set<UUID> processed = new HashSet<>();

        for (Entity entity : new ArrayList<>(ws.loadedEntityList)) {
            if (!McheliReflect.isAircraft(entity) || entity.isDead) continue;

            Object station = McheliReflect.getUavStation(entity);
            if (station == null) continue;

            Entity rider = McheliReflect.getStationRider(station);
            if (!(rider instanceof EntityPlayerMP)) continue;
            EntityPlayerMP player = (EntityPlayerMP) rider;

            UUID id = entity.getUniqueID();
            processed.add(id);

            int acCX = (int) Math.floor(entity.posX / 16.0);
            int acCZ = (int) Math.floor(entity.posZ / 16.0);
            int plCX = (int) Math.floor(player.posX / 16.0);
            int plCZ = (int) Math.floor(player.posZ / 16.0);

            // Chunks outside player's view radius around the aircraft.
            Set<ChunkPos> desiredOut = new HashSet<>();
            for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
                for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
                    ChunkPos pos = new ChunkPos(acCX + dx, acCZ + dz);
                    if (!isInViewRange(pos, plCX, plCZ, viewRadius)) {
                        desiredOut.add(pos);
                    }
                }
            }

            Set<ChunkPos> currentOut = subscribed.getOrDefault(id, Collections.emptySet());

            // Remove subscriptions no longer needed (only outside view range).
            for (ChunkPos old : currentOut) {
                if (!desiredOut.contains(old) && !isInViewRange(old, plCX, plCZ, viewRadius)) {
                    removePlayerFromEntry(pcm, player, old);
                }
            }

            // Add new out-of-view subscriptions.
            for (ChunkPos pos : desiredOut) {
                if (!currentOut.contains(pos)) {
                    addPlayerToEntry(pcm, player, pos);
                }
            }

            subscribed.put(id, new HashSet<>(desiredOut));
            subscribedPlayer.put(id, player);
        }

        // Cleanup: aircraft that are no longer under UAV control in this world.
        Iterator<Map.Entry<UUID, Set<ChunkPos>>> it = subscribed.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Set<ChunkPos>> entry = it.next();
            UUID uid = entry.getKey();
            if (processed.contains(uid)) continue;

            EntityPlayerMP player = subscribedPlayer.get(uid);
            if (player != null && player.world != ws) continue; // different world

            subscribedPlayer.remove(uid);
            if (player != null && !player.isDead) {
                int plCX = (int) Math.floor(player.posX / 16.0);
                int plCZ = (int) Math.floor(player.posZ / 16.0);
                for (ChunkPos pos : entry.getValue()) {
                    if (!isInViewRange(pos, plCX, plCZ, getViewRadius(pcm))) {
                        removePlayerFromEntry(pcm, player, pos);
                    }
                }
            }
            it.remove();
            // WingmanUavRegistry cleanup is handled in tickExpandTrackingRanges via registeredIds.
        }
    }

    // =========================================================================
    // Player disconnect cleanup
    // =========================================================================

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;

        Iterator<Map.Entry<UUID, Set<ChunkPos>>> it = subscribed.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Set<ChunkPos>> entry = it.next();
            if (subscribedPlayer.get(entry.getKey()) != player) continue;
            subscribedPlayer.remove(entry.getKey());
            it.remove();
        }
    }

    // =========================================================================
    // PlayerChunkMap helpers
    // =========================================================================

    private void addPlayerToEntry(PlayerChunkMap pcm, EntityPlayerMP player, ChunkPos pos) {
        try {
            PlayerChunkMapEntry entry = getOrCreateEntry(pcm, pos.x, pos.z);
            if (entry != null && !entry.containsPlayer(player)) {
                entry.addPlayer(player);
                McHeliWingman.logger.debug("[UavChunkStreamer] + {} → chunk ({},{})",
                        player.getName(), pos.x, pos.z);
            }
        } catch (Exception e) {
            McHeliWingman.logger.warn("[UavChunkStreamer] addPlayer ({},{}) failed: {}",
                    pos.x, pos.z, e.getMessage());
        }
    }

    private void removePlayerFromEntry(PlayerChunkMap pcm, EntityPlayerMP player, ChunkPos pos) {
        try {
            PlayerChunkMapEntry entry = pcm.getEntry(pos.x, pos.z);
            if (entry != null && entry.containsPlayer(player)) {
                entry.removePlayer(player);
                McHeliWingman.logger.debug("[UavChunkStreamer] - {} ← chunk ({},{})",
                        player.getName(), pos.x, pos.z);
            }
        } catch (Exception e) {
            McHeliWingman.logger.warn("[UavChunkStreamer] removePlayer ({},{}) failed: {}",
                    pos.x, pos.z, e.getMessage());
        }
    }

    private static boolean isInViewRange(ChunkPos pos, int plCX, int plCZ, int viewRadius) {
        return Math.abs(pos.x - plCX) <= viewRadius && Math.abs(pos.z - plCZ) <= viewRadius;
    }

    private int getViewRadius(PlayerChunkMap pcm) {
        if (viewRadiusField == null) {
            for (String name : new String[]{"playerViewRadius", "field_72698_e"}) {
                try {
                    Field f = PlayerChunkMap.class.getDeclaredField(name);
                    f.setAccessible(true);
                    viewRadiusField = f;
                    McHeliWingman.logger.info("[UavChunkStreamer] Resolved view radius field: {}", name);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
        }
        if (viewRadiusField != null) {
            try { return (int) viewRadiusField.get(pcm); } catch (Exception ignored) {}
        }
        return 16;
    }

    private PlayerChunkMapEntry getOrCreateEntry(PlayerChunkMap pcm, int x, int z) throws Exception {
        if (getOrCreateEntryMethod == null) {
            for (String name : new String[]{"getOrCreateEntry", "func_187302_c"}) {
                try {
                    Method m = PlayerChunkMap.class.getDeclaredMethod(name, int.class, int.class);
                    m.setAccessible(true);
                    getOrCreateEntryMethod = m;
                    McHeliWingman.logger.info("[UavChunkStreamer] Resolved getOrCreateEntry: {}", name);
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
        }
        if (getOrCreateEntryMethod != null) {
            return (PlayerChunkMapEntry) getOrCreateEntryMethod.invoke(pcm, x, z);
        }
        return pcm.getEntry(x, z); // fallback: won't create new entry
    }
}
