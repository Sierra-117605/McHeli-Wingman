package com.mcheliwingman.network;

import com.mcheliwingman.block.MarkerType;
import com.mcheliwingman.block.WingmanMarkerTileEntity;
import com.mcheliwingman.registry.MarkerRegistry;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.nio.charset.StandardCharsets;

/**
 * クライアント（マーカーGUI）→ サーバー へマーカー設定変更を送信するパケット。
 */
public class PacketMarkerUpdate implements IMessage {

    public int    x, y, z;
    public String typeName;
    public String id;
    public String baseId;

    public PacketMarkerUpdate() {}

    public PacketMarkerUpdate(BlockPos pos, MarkerType type, String id, String baseId) {
        this.x        = pos.getX();
        this.y        = pos.getY();
        this.z        = pos.getZ();
        this.typeName = type.name();
        this.id       = id;
        this.baseId   = baseId;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x); buf.writeInt(y); buf.writeInt(z);
        writeStr(buf, typeName);
        writeStr(buf, id);
        writeStr(buf, baseId);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt(); y = buf.readInt(); z = buf.readInt();
        typeName = readStr(buf);
        id       = readStr(buf);
        baseId   = readStr(buf);
    }

    public static class Handler implements IMessageHandler<PacketMarkerUpdate, IMessage> {
        @Override
        public IMessage onMessage(PacketMarkerUpdate msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            WorldServer ws = player.getServerWorld();
            ws.addScheduledTask(() -> {
                BlockPos pos = new BlockPos(msg.x, msg.y, msg.z);
                TileEntity te = ws.getTileEntity(pos);
                if (!(te instanceof WingmanMarkerTileEntity)) return;

                MarkerType type;
                try { type = MarkerType.valueOf(msg.typeName); }
                catch (Exception e) { type = MarkerType.PARKING; }

                WingmanMarkerTileEntity wte = (WingmanMarkerTileEntity) te;
                wte.setMarkerType(type);
                wte.setMarkerId(msg.id);
                wte.setBaseId(type == MarkerType.BASE ? "" : msg.baseId);

                // レジストリ更新
                MarkerRegistry.register(ws, pos, wte);
            });
            return null;
        }
    }

    private static void writeStr(ByteBuf buf, String s) {
        if (s == null) s = "";
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        buf.writeInt(b.length);
        buf.writeBytes(b);
    }

    private static String readStr(ByteBuf buf) {
        int len = buf.readInt();
        byte[] b = new byte[len];
        buf.readBytes(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
