package com.mcheliwingman.network;

import com.mcheliwingman.client.GuiBaseConfig;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Server → Client: 基地コンフィグGUIを開くよう指示する。
 * BASE マーカーブロックを右クリックしたときに送信される。
 */
public class PacketOpenBaseGui implements IMessage {

    public int bx, by, bz;
    public String baseId = "";
    public String runwayAId = "";
    public String runwayBId = "";
    public List<RouteDto> routes = new ArrayList<>();
    public List<MarkerDto> parkingMarkers = new ArrayList<>();
    public List<MarkerDto> waypointMarkers = new ArrayList<>();

    // ─── DTO ─────────────────────────────────────────────────────────────────

    public static class RouteDto {
        public String routeId = "", parkingId = "", runwayId = "";
        public List<String> waypointIds = new ArrayList<>();
    }

    public static class MarkerDto {
        public String id = "";
        public int x, y, z;
    }

    // ─── Constructors ─────────────────────────────────────────────────────────

    public PacketOpenBaseGui() {}

    public PacketOpenBaseGui(BlockPos pos, String baseId) {
        this.bx = pos.getX();
        this.by = pos.getY();
        this.bz = pos.getZ();
        this.baseId = baseId;
    }

    // ─── Serialization ───────────────────────────────────────────────────────

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(bx); buf.writeInt(by); buf.writeInt(bz);
        writeStr(buf, baseId);
        writeStr(buf, runwayAId); writeStr(buf, runwayBId);

        buf.writeInt(routes.size());
        for (RouteDto r : routes) {
            writeStr(buf, r.routeId); writeStr(buf, r.parkingId); writeStr(buf, r.runwayId);
            buf.writeInt(r.waypointIds.size());
            for (String wp : r.waypointIds) writeStr(buf, wp);
        }

        buf.writeInt(parkingMarkers.size());
        for (MarkerDto m : parkingMarkers) {
            writeStr(buf, m.id); buf.writeInt(m.x); buf.writeInt(m.y); buf.writeInt(m.z);
        }

        buf.writeInt(waypointMarkers.size());
        for (MarkerDto m : waypointMarkers) {
            writeStr(buf, m.id); buf.writeInt(m.x); buf.writeInt(m.y); buf.writeInt(m.z);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        bx = buf.readInt(); by = buf.readInt(); bz = buf.readInt();
        baseId = readStr(buf);
        runwayAId = readStr(buf); runwayBId = readStr(buf);

        int n = buf.readInt();
        for (int i = 0; i < n; i++) {
            RouteDto r = new RouteDto();
            r.routeId = readStr(buf); r.parkingId = readStr(buf); r.runwayId = readStr(buf);
            int wn = buf.readInt();
            for (int j = 0; j < wn; j++) r.waypointIds.add(readStr(buf));
            routes.add(r);
        }

        n = buf.readInt();
        for (int i = 0; i < n; i++) {
            MarkerDto m = new MarkerDto();
            m.id = readStr(buf); m.x = buf.readInt(); m.y = buf.readInt(); m.z = buf.readInt();
            parkingMarkers.add(m);
        }

        n = buf.readInt();
        for (int i = 0; i < n; i++) {
            MarkerDto m = new MarkerDto();
            m.id = readStr(buf); m.x = buf.readInt(); m.y = buf.readInt(); m.z = buf.readInt();
            waypointMarkers.add(m);
        }
    }

    // ─── Handler (CLIENT) ────────────────────────────────────────────────────

    @SideOnly(Side.CLIENT)
    public static class Handler implements IMessageHandler<PacketOpenBaseGui, IMessage> {
        @Override
        public IMessage onMessage(PacketOpenBaseGui msg, MessageContext ctx) {
            Minecraft mc = Minecraft.getMinecraft();
            mc.addScheduledTask(() -> mc.displayGuiScreen(new GuiBaseConfig(msg)));
            return null;
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static void writeStr(ByteBuf buf, String s) {
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
