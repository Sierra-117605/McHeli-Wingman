package com.mcheliwingman.network;

import com.mcheliwingman.client.GuiWingmanPlanner;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class PacketPlannerData implements IMessage {

    public List<UavDto>    uavs    = new ArrayList<>();
    public List<RouteDto>  routes  = new ArrayList<>();
    public List<MarkerDto> markers = new ArrayList<>();
    public double playerX, playerY, playerZ;

    public static class UavDto {
        public String uuid, name, state;
        public int nodeIdx, nodeCount;
    }

    public static class RouteDto {
        public String name;
        public List<String> nodes = new ArrayList<>();
    }

    public static class MarkerDto {
        public String type, id;
        public int x, y, z;
    }

    // --- serialization ---

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(uavs.size());
        for (UavDto d : uavs) {
            writeStr(buf, d.uuid); writeStr(buf, d.name); writeStr(buf, d.state);
            buf.writeInt(d.nodeIdx); buf.writeInt(d.nodeCount);
        }
        buf.writeInt(routes.size());
        for (RouteDto d : routes) {
            writeStr(buf, d.name); buf.writeInt(d.nodes.size());
            for (String n : d.nodes) writeStr(buf, n);
        }
        buf.writeInt(markers.size());
        for (MarkerDto d : markers) {
            writeStr(buf, d.type); writeStr(buf, d.id);
            buf.writeInt(d.x); buf.writeInt(d.y); buf.writeInt(d.z);
        }
        buf.writeDouble(playerX); buf.writeDouble(playerY); buf.writeDouble(playerZ);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int n = buf.readInt();
        for (int i = 0; i < n; i++) {
            UavDto d = new UavDto();
            d.uuid = readStr(buf); d.name = readStr(buf); d.state = readStr(buf);
            d.nodeIdx = buf.readInt(); d.nodeCount = buf.readInt();
            uavs.add(d);
        }
        n = buf.readInt();
        for (int i = 0; i < n; i++) {
            RouteDto d = new RouteDto();
            d.name = readStr(buf);
            int nc = buf.readInt();
            for (int j = 0; j < nc; j++) d.nodes.add(readStr(buf));
            routes.add(d);
        }
        n = buf.readInt();
        for (int i = 0; i < n; i++) {
            MarkerDto d = new MarkerDto();
            d.type = readStr(buf); d.id = readStr(buf);
            d.x = buf.readInt(); d.y = buf.readInt(); d.z = buf.readInt();
            markers.add(d);
        }
        playerX = buf.readDouble(); playerY = buf.readDouble(); playerZ = buf.readDouble();
    }

    // --- handler (CLIENT side) ---

    @SideOnly(Side.CLIENT)
    public static class Handler implements IMessageHandler<PacketPlannerData, IMessage> {
        @Override
        public IMessage onMessage(PacketPlannerData msg, MessageContext ctx) {
            Minecraft mc = Minecraft.getMinecraft();
            mc.addScheduledTask(() ->
                mc.displayGuiScreen(new GuiWingmanPlanner(msg.uavs, msg.routes, msg.markers, msg.playerX, msg.playerY, msg.playerZ)));
            return null;
        }
    }

    // --- helpers ---

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
