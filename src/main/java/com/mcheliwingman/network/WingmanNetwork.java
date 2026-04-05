package com.mcheliwingman.network;

import com.mcheliwingman.network.PacketMissionAction;
import com.mcheliwingman.network.PacketPlannerData;
import com.mcheliwingman.network.PacketRouteAction;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public class WingmanNetwork {
    public static final SimpleNetworkWrapper CHANNEL =
        NetworkRegistry.INSTANCE.newSimpleChannel("mcheliwingman");

    private static int nextId = 0;

    public static void register() {
        CHANNEL.registerMessage(PacketPlannerData.Handler.class,  PacketPlannerData.class,  nextId++, Side.CLIENT);
        CHANNEL.registerMessage(PacketMissionAction.Handler.class, PacketMissionAction.class, nextId++, Side.SERVER);
        CHANNEL.registerMessage(PacketRouteAction.Handler.class,   PacketRouteAction.class,   nextId++, Side.SERVER);
    }

    public static void sendToPlayer(IMessage msg, EntityPlayerMP player) {
        CHANNEL.sendTo(msg, player);
    }

    public static void sendToServer(IMessage msg) {
        CHANNEL.sendToServer(msg);
    }
}
