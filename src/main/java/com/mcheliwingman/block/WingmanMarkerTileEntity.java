package com.mcheliwingman.block;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

public class WingmanMarkerTileEntity extends TileEntity {

    private MarkerType markerType = MarkerType.PARKING;
    private String markerId = "";  // ユーザー定義ID（滑走路・駐機場の識別子）

    public MarkerType getMarkerType() { return markerType; }
    public String getMarkerId()       { return markerId; }

    public void setMarkerType(MarkerType t) { markerType = t; markDirty(); }
    public void setMarkerId(String id)      { markerId = id;   markDirty(); }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setString("markerType", markerType.name());
        tag.setString("markerId",   markerId);
        return tag;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        try { markerType = MarkerType.valueOf(tag.getString("markerType")); }
        catch (Exception ignored) { markerType = MarkerType.PARKING; }
        markerId = tag.getString("markerId");
    }
}
