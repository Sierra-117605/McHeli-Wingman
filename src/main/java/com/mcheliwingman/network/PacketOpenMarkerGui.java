package com.mcheliwingman.network;

import com.mcheliwingman.block.MarkerType;
import com.mcheliwingman.client.GuiMarkerConfig;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.nio.charset.StandardCharsets;

/**
 * Server → Client: マーカーブロック設定GUIを開くよう指示する。
 * Forge の IGuiHandler 経路が動作しない環境向けの代替実装。
 */
public class PacketOpenMarkerGui implements IMessage {

    private int x, y, z;
    private String typeName;
    private String id;
    private String baseId;
    private int    parkingHeading = -1;

    public PacketOpenMarkerGui() {}

    public PacketOpenMarkerGui(BlockPos pos, MarkerType type, String id, String baseId, int parkingHeading) {
        this.x              = pos.getX();
        this.y              = pos.getY();
        this.z              = pos.getZ();
        this.typeName       = type.name();
        this.id             = id;
        this.baseId         = baseId;
        this.parkingHeading = parkingHeading;
    }

    // ─── エンコード ───────────────────────────────────────────────────────────

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        writeString(buf, typeName);
        writeString(buf, id);
        writeString(buf, baseId);
        buf.writeInt(parkingHeading);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x        = buf.readInt();
        y        = buf.readInt();
        z        = buf.readInt();
        typeName       = readString(buf);
        id             = readString(buf);
        baseId         = readString(buf);
        parkingHeading = buf.isReadable(4) ? buf.readInt() : -1;
    }

    private static void writeString(ByteBuf buf, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readString(ByteBuf buf) {
        int len = buf.readInt();
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ─── ハンドラ（クライアント側） ──────────────────────────────────────────

    public static class Handler implements IMessageHandler<PacketOpenMarkerGui, IMessage> {
        @Override
        public IMessage onMessage(PacketOpenMarkerGui msg, MessageContext ctx) {
            Minecraft mc = Minecraft.getMinecraft();
            mc.addScheduledTask(() -> {
                MarkerType type;
                try {
                    type = MarkerType.valueOf(msg.typeName);
                } catch (Exception e) {
                    type = MarkerType.PARKING;
                }
                BlockPos pos = new BlockPos(msg.x, msg.y, msg.z);
                mc.displayGuiScreen(new GuiMarkerConfig(pos, type, msg.id, msg.baseId, msg.parkingHeading));
            });
            return null;
        }
    }
}
