package com.mcheliwingman.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;

/**
 * S → C: ウィングマンパネルに必要なデータをクライアントへ送り、GUIを開かせる。
 */
public class PacketWingmanPanelData implements IMessage {

    // ─── DTO ────────────────────────────────────────────────────────────────

    public static class AircraftDto {
        public String uuid = "";
        public String name = "";
    }

    public static class WingmanDto {
        public String uuid       = "";
        public String name       = "";
        public int    slot       = 0;
        public String state      = "";
        public int    attackMode = 0;   // WingmanEntry.ATK_NONE/AUTO/MANUAL
        public String weaponType = "";  // "" = any
    }

    // ─── Payload ─────────────────────────────────────────────────────────────

    public List<AircraftDto> nearby  = new ArrayList<>();
    public List<WingmanDto>  wingmen = new ArrayList<>();

    public double sideDist  = 20.0;
    public double altOffset = 0.0;
    public double rearDist  = 30.0;
    public int    maxWings  = 4;
    public double minAlt    = 0.0;
    public double maxAlt    = 0.0;

    // ─── Serialization ───────────────────────────────────────────────────────

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(nearby.size());
        for (AircraftDto d : nearby) {
            ByteBufUtils.writeUTF8String(buf, d.uuid);
            ByteBufUtils.writeUTF8String(buf, d.name);
        }

        buf.writeInt(wingmen.size());
        for (WingmanDto d : wingmen) {
            ByteBufUtils.writeUTF8String(buf, d.uuid);
            ByteBufUtils.writeUTF8String(buf, d.name);
            buf.writeInt(d.slot);
            ByteBufUtils.writeUTF8String(buf, d.state);
            buf.writeInt(d.attackMode);
            ByteBufUtils.writeUTF8String(buf, d.weaponType);
        }

        buf.writeDouble(sideDist);
        buf.writeDouble(altOffset);
        buf.writeDouble(rearDist);
        buf.writeInt(maxWings);
        buf.writeDouble(minAlt);
        buf.writeDouble(maxAlt);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int nc = buf.readInt();
        for (int i = 0; i < nc; i++) {
            AircraftDto d = new AircraftDto();
            d.uuid = ByteBufUtils.readUTF8String(buf);
            d.name = ByteBufUtils.readUTF8String(buf);
            nearby.add(d);
        }

        int wc = buf.readInt();
        for (int i = 0; i < wc; i++) {
            WingmanDto d = new WingmanDto();
            d.uuid       = ByteBufUtils.readUTF8String(buf);
            d.name       = ByteBufUtils.readUTF8String(buf);
            d.slot       = buf.readInt();
            d.state      = ByteBufUtils.readUTF8String(buf);
            d.attackMode = buf.readInt();
            d.weaponType = ByteBufUtils.readUTF8String(buf);
            wingmen.add(d);
        }

        sideDist  = buf.readDouble();
        altOffset = buf.readDouble();
        rearDist  = buf.readDouble();
        maxWings  = buf.readInt();
        minAlt    = buf.readDouble();
        maxAlt    = buf.readDouble();
    }

    // ─── Handler ─────────────────────────────────────────────────────────────

    @SideOnly(Side.CLIENT)
    public static class Handler implements IMessageHandler<PacketWingmanPanelData, IMessage> {
        @Override
        public IMessage onMessage(PacketWingmanPanelData msg, MessageContext ctx) {
            Minecraft mc = Minecraft.getMinecraft();
            mc.addScheduledTask(() ->
                mc.displayGuiScreen(new com.mcheliwingman.client.GuiWingmanPanel(msg)));
            return null;
        }
    }
}
