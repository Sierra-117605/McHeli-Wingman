package com.mcheliwingman.registry;

import com.mcheliwingman.mission.TaxiRoute;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import java.util.*;

/** タキシールートをワールド単位で永続管理する。 */
public class TaxiRouteRegistry extends WorldSavedData {

    private static final String KEY = "wingman_taxi_routes";
    private final Map<String, TaxiRoute> routes = new LinkedHashMap<>();

    public TaxiRouteRegistry()            { super(KEY); }
    public TaxiRouteRegistry(String name) { super(name); }

    private static TaxiRouteRegistry get(World world) {
        MapStorage ms = world.getPerWorldStorage();
        TaxiRouteRegistry r = (TaxiRouteRegistry) ms.getOrLoadData(TaxiRouteRegistry.class, KEY);
        if (r == null) { r = new TaxiRouteRegistry(); ms.setData(KEY, r); }
        return r;
    }

    // ─── API ─────────────────────────────────────────────────────────────────

    public static void save(World world, TaxiRoute route) {
        TaxiRouteRegistry reg = get(world);
        reg.routes.put(route.routeId, route);
        reg.markDirty();
    }

    public static void delete(World world, String routeId) {
        TaxiRouteRegistry reg = get(world);
        reg.routes.remove(routeId);
        reg.markDirty();
    }

    /** 指定基地のタキシールート一覧 */
    public static List<TaxiRoute> getForBase(World world, String baseId) {
        List<TaxiRoute> result = new ArrayList<>();
        for (TaxiRoute r : get(world).routes.values()) {
            if (baseId.equals(r.baseId)) result.add(r);
        }
        return result;
    }

    /** 駐機スポットIDからルートを検索 */
    public static TaxiRoute findByParking(World world, String parkingId) {
        for (TaxiRoute r : get(world).routes.values()) {
            if (parkingId.equals(r.parkingId)) return r;
        }
        return null;
    }

    /** ルートIDから直接取得 */
    public static TaxiRoute findById(World world, String routeId) {
        return get(world).routes.get(routeId);
    }

    /** 全ルートのスナップショット */
    public static List<TaxiRoute> snapshot(World world) {
        return new ArrayList<>(get(world).routes.values());
    }

    // ─── NBT ─────────────────────────────────────────────────────────────────

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        routes.clear();
        NBTTagList list = tag.getTagList("routes", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound c = list.getCompoundTagAt(i);
            List<String> wps = new ArrayList<>();
            NBTTagList wpList = c.getTagList("waypoints", Constants.NBT.TAG_STRING);
            for (int j = 0; j < wpList.tagCount(); j++) wps.add(wpList.getStringTagAt(j));
            TaxiRoute r = new TaxiRoute(
                c.getString("routeId"), c.getString("baseId"),
                c.getString("parkingId"), c.getString("runwayId"), wps);
            routes.put(r.routeId, r);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (TaxiRoute r : routes.values()) {
            NBTTagCompound c = new NBTTagCompound();
            c.setString("routeId",   r.routeId);
            c.setString("baseId",    r.baseId);
            c.setString("parkingId", r.parkingId);
            c.setString("runwayId",  r.runwayId);
            NBTTagList wpList = new NBTTagList();
            for (String wp : r.waypointIds) wpList.appendTag(new NBTTagString(wp));
            c.setTag("waypoints", wpList);
            list.appendTag(c);
        }
        tag.setTag("routes", list);
        return tag;
    }
}
