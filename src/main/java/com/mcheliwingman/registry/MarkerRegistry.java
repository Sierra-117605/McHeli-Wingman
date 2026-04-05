package com.mcheliwingman.registry;

import com.mcheliwingman.McHeliWingman;
import com.mcheliwingman.block.MarkerType;
import com.mcheliwingman.block.WingmanMarkerTileEntity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import java.util.*;

/**
 * ワールドごとに WingmanMarkerBlock の位置・種別・IDを永続管理する。
 * WorldSavedData として保存されるためサーバー再起動後も維持される。
 */
public class MarkerRegistry extends WorldSavedData {

    private static final String KEY = "wingman_markers";

    /** マーカー情報 */
    public static class MarkerInfo {
        public final BlockPos    pos;
        public final MarkerType  type;
        public final String      id;

        public MarkerInfo(BlockPos pos, MarkerType type, String id) {
            this.pos  = pos;
            this.type = type;
            this.id   = id;
        }
    }

    private final List<MarkerInfo> markers = new ArrayList<>();

    public MarkerRegistry() { super(KEY); }
    public MarkerRegistry(String name) { super(name); }

    // ─── Static access ───────────────────────────────────────────────────────

    private static MarkerRegistry get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        MarkerRegistry inst = (MarkerRegistry) storage.getOrLoadData(MarkerRegistry.class, KEY);
        if (inst == null) {
            inst = new MarkerRegistry();
            storage.setData(KEY, inst);
        }
        return inst;
    }

    public static void register(World world, BlockPos pos, WingmanMarkerTileEntity te) {
        MarkerRegistry reg = get(world);
        reg.markers.removeIf(m -> m.pos.equals(pos));
        reg.markers.add(new MarkerInfo(pos, te.getMarkerType(), te.getMarkerId()));
        reg.markDirty();
        McHeliWingman.logger.debug("[MarkerRegistry] registered {} {} id={}", te.getMarkerType(), pos, te.getMarkerId());
    }

    public static void unregister(World world, BlockPos pos) {
        MarkerRegistry reg = get(world);
        reg.markers.removeIf(m -> m.pos.equals(pos));
        reg.markDirty();
    }

    public static void setId(World world, BlockPos pos, String id) {
        MarkerRegistry reg = get(world);
        for (int i = 0; i < reg.markers.size(); i++) {
            MarkerInfo m = reg.markers.get(i);
            if (m.pos.equals(pos)) {
                reg.markers.set(i, new MarkerInfo(pos, m.type, id));
                reg.markDirty();
                return;
            }
        }
    }

    /** 全マーカーのスナップショット。 */
    public static List<MarkerInfo> snapshot(World world) {
        return Collections.unmodifiableList(get(world).markers);
    }

    /** ID で検索（type も一致が必要）。 */
    public static MarkerInfo findById(World world, MarkerType type, String id) {
        for (MarkerInfo m : get(world).markers) {
            if (m.type == type && id.equals(m.id)) return m;
        }
        return null;
    }

    /** 特定タイプのマーカーを全て返す。 */
    public static List<MarkerInfo> findByType(World world, MarkerType type) {
        List<MarkerInfo> result = new ArrayList<>();
        for (MarkerInfo m : get(world).markers) {
            if (m.type == type) result.add(m);
        }
        return result;
    }

    // ─── Serialization ───────────────────────────────────────────────────────

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        markers.clear();
        NBTTagList list = tag.getTagList("markers", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound c = list.getCompoundTagAt(i);
            BlockPos pos = new BlockPos(c.getInteger("x"), c.getInteger("y"), c.getInteger("z"));
            MarkerType type;
            try { type = MarkerType.valueOf(c.getString("type")); }
            catch (Exception e) { type = MarkerType.PARKING; }
            String id = c.getString("id");
            markers.add(new MarkerInfo(pos, type, id));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (MarkerInfo m : markers) {
            NBTTagCompound c = new NBTTagCompound();
            c.setInteger("x", m.pos.getX());
            c.setInteger("y", m.pos.getY());
            c.setInteger("z", m.pos.getZ());
            c.setString("type", m.type.name());
            c.setString("id",   m.id);
            list.appendTag(c);
        }
        tag.setTag("markers", list);
        return tag;
    }
}
