package com.mcheliwingman.block;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;

public class WingmanMarkerTileEntity extends TileEntity {

    private MarkerType markerType = MarkerType.PARKING;
    private String markerId = "";   // ユーザー定義ID（滑走路・駐機場の識別子）
    private String baseId   = "";   // 所属ベースのID（BASE種別自身は空文字）

    public MarkerType getMarkerType() { return markerType; }
    public String getMarkerId()       { return markerId; }
    public String getBaseId()         { return baseId; }

    public void setMarkerType(MarkerType t) { markerType = t; markDirty(); sync(); }
    public void setMarkerId(String id)      { markerId = id;   markDirty(); sync(); }
    public void setBaseId(String id)        { baseId   = id;   markDirty(); sync(); }

    // ─── NBT ────────────────────────────────────────────────────────────────

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setString("markerType", markerType.name());
        tag.setString("markerId",   markerId);
        tag.setString("baseId",     baseId);
        return tag;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        try { markerType = MarkerType.valueOf(tag.getString("markerType")); }
        catch (Exception ignored) { markerType = MarkerType.PARKING; }
        markerId = tag.getString("markerId");
        baseId   = tag.getString("baseId");
    }

    // ─── クライアント同期（右クリックGUI のために必要） ──────────────────

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 0, writeToNBT(new NBTTagCompound()));
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        readFromNBT(pkt.getNbtCompound());
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    /** サーバー側の変更をクライアントへブロードキャスト */
    private void sync() {
        if (world != null && !world.isRemote) {
            world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
        }
    }
}
