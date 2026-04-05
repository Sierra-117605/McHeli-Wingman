package com.mcheliwingman.network;

import com.mcheliwingman.mission.AutonomousState;
import com.mcheliwingman.mission.MissionPlan;
import com.mcheliwingman.wingman.WingmanEntry;
import com.mcheliwingman.wingman.WingmanRegistry;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public class PacketMissionAction implements IMessage {

    public static final int ASSIGN = 0;
    public static final int ABORT  = 1;

    public int    action;
    public String uuidStr;
    public String routeName;

    public PacketMissionAction() {}

    public PacketMissionAction(int action, String uuidStr, String routeName) {
        this.action    = action;
        this.uuidStr   = uuidStr;
        this.routeName = routeName;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(action);
        writeStr(buf, uuidStr);
        writeStr(buf, routeName);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        action    = buf.readInt();
        uuidStr   = readStr(buf);
        routeName = readStr(buf);
    }

    public static class Handler implements IMessageHandler<PacketMissionAction, IMessage> {
        @Override
        public IMessage onMessage(PacketMissionAction msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            WorldServer ws = player.getServerWorld();
            ws.addScheduledTask(() -> {
                if (msg.action == ASSIGN) {
                    UUID uid;
                    try { uid = UUID.fromString(msg.uuidStr); } catch (Exception e) { return; }
                    Entity wingman = ws.getEntityFromUuid(uid);
                    if (wingman == null) {
                        player.sendMessage(new TextComponentString("§cEntity not found."));
                        return;
                    }
                    List<com.mcheliwingman.mission.MissionNode> nodes = MissionPlan.get(msg.routeName);
                    if (nodes == null) {
                        player.sendMessage(new TextComponentString("§cRoute not found: " + msg.routeName));
                        return;
                    }
                    WingmanEntry entry = WingmanRegistry.get(uid);
                    if (entry == null) {
                        entry = new WingmanEntry();
                        WingmanRegistry.put(uid, entry);
                    }
                    entry.mission = nodes;
                    entry.missionIndex = 0;
                    entry.missionNodeTimer = 0;
                    entry.autoState = AutonomousState.ENROUTE;
                    player.sendMessage(new TextComponentString(
                        "§aMission §e" + msg.routeName + "§a assigned to " + msg.uuidStr.substring(0, 8) + "..."));
                } else {
                    // ABORT
                    if (msg.uuidStr.isEmpty()) {
                        int count = 0;
                        for (WingmanEntry e : WingmanRegistry.snapshot().values()) {
                            if (e.isAutonomous()) { e.mission = null; e.autoState = AutonomousState.NONE; count++; }
                        }
                        player.sendMessage(new TextComponentString("§aAborted " + count + " mission(s)."));
                    } else {
                        UUID uid;
                        try { uid = UUID.fromString(msg.uuidStr); } catch (Exception e) { return; }
                        WingmanEntry entry = WingmanRegistry.get(uid);
                        if (entry != null) { entry.mission = null; entry.autoState = AutonomousState.NONE; }
                        player.sendMessage(new TextComponentString("§aMission aborted."));
                    }
                }
            });
            return null;
        }
    }

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
