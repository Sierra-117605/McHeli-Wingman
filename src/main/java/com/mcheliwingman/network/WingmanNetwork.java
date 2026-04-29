package com.mcheliwingman.network;

import com.mcheliwingman.network.PacketMissionAction;
import com.mcheliwingman.network.PacketPlannerData;
import com.mcheliwingman.network.PacketRouteAction;
import com.mcheliwingman.network.PacketOpenBaseGui;
import com.mcheliwingman.network.PacketBaseAction;
import com.mcheliwingman.network.PacketWingmanPanelOpen;
import com.mcheliwingman.network.PacketWingmanPanelData;
import com.mcheliwingman.network.PacketWingmanPanelAction;
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
        CHANNEL.registerMessage(PacketPlannerData.Handler.class,      PacketPlannerData.class,      nextId++, Side.CLIENT);
        CHANNEL.registerMessage(PacketMissionAction.Handler.class,    PacketMissionAction.class,    nextId++, Side.SERVER);
        CHANNEL.registerMessage(PacketRouteAction.Handler.class,      PacketRouteAction.class,      nextId++, Side.SERVER);
        CHANNEL.registerMessage(PacketMarkerUpdate.Handler.class,     PacketMarkerUpdate.class,     nextId++, Side.SERVER);
        CHANNEL.registerMessage(PacketOpenMarkerGui.Handler.class,    PacketOpenMarkerGui.class,    nextId++, Side.CLIENT);
        CHANNEL.registerMessage(PacketOpenBaseGui.Handler.class,      PacketOpenBaseGui.class,      nextId++, Side.CLIENT);
        CHANNEL.registerMessage(PacketBaseAction.Handler.class,       PacketBaseAction.class,       nextId++, Side.SERVER);
        // Wingman Panel
        CHANNEL.registerMessage(PacketWingmanPanelOpen.Handler.class,  PacketWingmanPanelOpen.class,  nextId++, Side.SERVER);
        CHANNEL.registerMessage(PacketWingmanPanelData.Handler.class,  PacketWingmanPanelData.class,  nextId++, Side.CLIENT);
        CHANNEL.registerMessage(PacketWingmanPanelAction.Handler.class, PacketWingmanPanelAction.class, nextId++, Side.SERVER);
        // 自律飛行 ビジュアルヘディング同期 (server→client)
        CHANNEL.registerMessage(PacketAutopilotVisual.Handler.class, PacketAutopilotVisual.class, nextId++, Side.CLIENT);
    }

    public static void sendToPlayer(IMessage msg, EntityPlayerMP player) {
        CHANNEL.sendTo(msg, player);
    }

    public static void sendToServer(IMessage msg) {
        CHANNEL.sendToServer(msg);
    }
}
