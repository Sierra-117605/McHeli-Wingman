package com.mcheliwingman.network;

import com.mcheliwingman.mission.MissionNode;
import com.mcheliwingman.mission.MissionPlan;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class PacketRouteAction implements IMessage {

    public static final int CREATE = 0;
    public static final int DELETE = 1;

    public int          action;
    public String       name;
    public List<String> nodes = new ArrayList<>();

    public PacketRouteAction() {}

    public PacketRouteAction(int action, String name, List<String> nodes) {
        this.action = action;
        this.name   = name;
        this.nodes  = nodes;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(action);
        writeStr(buf, name);
        buf.writeInt(nodes.size());
        for (String n : nodes) writeStr(buf, n);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        action = buf.readInt();
        name   = readStr(buf);
        int nc = buf.readInt();
        for (int i = 0; i < nc; i++) nodes.add(readStr(buf));
    }

    public static class Handler implements IMessageHandler<PacketRouteAction, IMessage> {
        @Override
        public IMessage onMessage(PacketRouteAction msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            WorldServer ws = player.getServerWorld();
            ws.addScheduledTask(() -> {
                if (msg.action == CREATE) {
                    if (msg.name.isEmpty() || msg.nodes.isEmpty()) {
                        player.sendMessage(new TextComponentString("§cRoute name and nodes required."));
                        return;
                    }
                    List<MissionNode> parsed = new ArrayList<>();
                    for (String s : msg.nodes) {
                        try { parsed.add(MissionNode.parse(s)); }
                        catch (Exception e) {
                            player.sendMessage(new TextComponentString("§cInvalid node: " + s));
                            return;
                        }
                    }
                    MissionPlan.put(msg.name, parsed);
                    player.sendMessage(new TextComponentString(
                        "§aRoute §e" + msg.name + "§a saved (" + parsed.size() + " nodes)."));
                } else {
                    boolean removed = MissionPlan.remove(msg.name);
                    player.sendMessage(removed
                        ? new TextComponentString("§aRoute §e" + msg.name + "§a deleted.")
                        : new TextComponentString("§cRoute not found: " + msg.name));
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
