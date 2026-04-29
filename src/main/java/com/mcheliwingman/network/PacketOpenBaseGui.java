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
    public String runwayAId = "";   // 後方互換用（先頭のRUNWAY_A ID）
    public String runwayBId = "";
    public List<RouteDto> routes = new ArrayList<>();
    public List<MarkerDto> parkingMarkers  = new ArrayList<>();
    public List<MarkerDto> waypointMarkers = new ArrayList<>();
    public List<MarkerDto>   runwayAMarkers  = new ArrayList<>();  // 選択UI用
    public List<MarkerDto>   runwayBMarkers  = new ArrayList<>();  // 選択UI用（RUNWAY_B）
    public List<AircraftDto> nearbyAircraft  = new ArrayList<>();  // 機体選択UI用
    public List<MarkerDto>   helipads         = new ArrayList<>();  // HELIPAD マーカー（TaxiRoute端点選択用）
    public List<MarkerDto>   helipadBMarkers  = new ArrayList<>();  // HELIPAD_B マーカー（方向指示、表示専用）

    // ─── DTO ─────────────────────────────────────────────────────────────────

    public static class RouteDto {
        public String routeId = "", parkingId = "", runwayId = "", runwayBId = "";
        public List<String> waypointIds = new ArrayList<>();
        /** 着陸時専用WPリスト。空の場合は waypointIds の逆順を使用。 */
        public List<String> arrivalWaypointIds = new ArrayList<>();
        /** 着陸エントリー端点 ID。空の場合は runwayId を使用。 */
        public String arrivalRunwayId = "";
        /** 駐機方位: -1=任意, 0=N, 1=E, 2=S, 3=W */
        public int parkingHeading = -1;
    }

    public static class MarkerDto {
        public String id = "";
        public int x, y, z;
    }

    public static class AircraftDto {
        public String uuid = "", name = "";
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
            writeStr(buf, r.runwayBId);
            buf.writeInt(r.waypointIds.size());
            for (String wp : r.waypointIds) writeStr(buf, wp);
            buf.writeInt(r.parkingHeading);
            buf.writeInt(r.arrivalWaypointIds.size());
            for (String wp : r.arrivalWaypointIds) writeStr(buf, wp);
            writeStr(buf, r.arrivalRunwayId);
        }

        buf.writeInt(parkingMarkers.size());
        for (MarkerDto m : parkingMarkers) {
            writeStr(buf, m.id); buf.writeInt(m.x); buf.writeInt(m.y); buf.writeInt(m.z);
        }

        buf.writeInt(waypointMarkers.size());
        for (MarkerDto m : waypointMarkers) {
            writeStr(buf, m.id); buf.writeInt(m.x); buf.writeInt(m.y); buf.writeInt(m.z);
        }

        buf.writeInt(runwayAMarkers.size());
        for (MarkerDto m : runwayAMarkers) {
            writeStr(buf, m.id); buf.writeInt(m.x); buf.writeInt(m.y); buf.writeInt(m.z);
        }

        buf.writeInt(nearbyAircraft.size());
        for (AircraftDto a : nearbyAircraft) {
            writeStr(buf, a.uuid); writeStr(buf, a.name);
        }

        buf.writeInt(helipads.size());
        for (MarkerDto m : helipads) {
            writeStr(buf, m.id); buf.writeInt(m.x); buf.writeInt(m.y); buf.writeInt(m.z);
        }

        buf.writeInt(helipadBMarkers.size());
        for (MarkerDto m : helipadBMarkers) {
            writeStr(buf, m.id); buf.writeInt(m.x); buf.writeInt(m.y); buf.writeInt(m.z);
        }

        buf.writeInt(runwayBMarkers.size());
        for (MarkerDto m : runwayBMarkers) {
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
            r.runwayBId = readStr(buf);
            int wn = buf.readInt();
            for (int j = 0; j < wn; j++) r.waypointIds.add(readStr(buf));
            r.parkingHeading = buf.isReadable() ? buf.readInt() : -1;
            if (buf.isReadable()) {
                int an = buf.readInt();
                for (int j = 0; j < an; j++) r.arrivalWaypointIds.add(readStr(buf));
            }
            r.arrivalRunwayId = buf.isReadable() ? readStr(buf) : "";
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

        if (buf.isReadable()) {
            n = buf.readInt();
            for (int i = 0; i < n; i++) {
                MarkerDto m = new MarkerDto();
                m.id = readStr(buf); m.x = buf.readInt(); m.y = buf.readInt(); m.z = buf.readInt();
                runwayAMarkers.add(m);
            }
        }

        if (buf.isReadable()) {
            n = buf.readInt();
            for (int i = 0; i < n; i++) {
                AircraftDto a = new AircraftDto();
                a.uuid = readStr(buf); a.name = readStr(buf);
                nearbyAircraft.add(a);
            }
        }

        if (buf.isReadable()) {
            n = buf.readInt();
            for (int i = 0; i < n; i++) {
                MarkerDto m = new MarkerDto();
                m.id = readStr(buf); m.x = buf.readInt(); m.y = buf.readInt(); m.z = buf.readInt();
                helipads.add(m);
            }
        }

        if (buf.isReadable()) {
            n = buf.readInt();
            for (int i = 0; i < n; i++) {
                MarkerDto m = new MarkerDto();
                m.id = readStr(buf); m.x = buf.readInt(); m.y = buf.readInt(); m.z = buf.readInt();
                helipadBMarkers.add(m);
            }
        }

        if (buf.isReadable()) {
            n = buf.readInt();
            for (int i = 0; i < n; i++) {
                MarkerDto m = new MarkerDto();
                m.id = readStr(buf); m.x = buf.readInt(); m.y = buf.readInt(); m.z = buf.readInt();
                runwayBMarkers.add(m);
            }
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
