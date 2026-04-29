package com.mcheliwingman.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.HashMap;
import java.util.Map;

/**
 * Server → Client: 自律飛行中の目標機首方向とスロットルを通知する。
 * クライアントは受信した値を ClientAutopilotHandler で Phase.END に適用し、
 * McHeli の onUpdateAircraft() によるキー入力ベース上書きを打ち消す。
 *
 * targetYaw      == Float.NaN → ヨー自律モード解除（エントリ削除）。
 * targetThrottle == Float.NaN → スロットル自律モード解除（エントリ削除）。
 */
public class PacketAutopilotVisual implements IMessage {

    public int   entityId;
    public float targetYaw;      // Float.NaN = clear yaw
    public float targetThrottle; // Float.NaN = clear throttle

    // ─── Client-side storage (client VM only) ────────────────────────────────
    @SideOnly(Side.CLIENT)
    public static final Map<Integer, Float> CLIENT_HEADINGS  = new HashMap<>();
    @SideOnly(Side.CLIENT)
    public static final Map<Integer, Float> CLIENT_THROTTLES = new HashMap<>();

    // ─── Constructors ─────────────────────────────────────────────────────────
    public PacketAutopilotVisual() {}

    public PacketAutopilotVisual(int entityId, float targetYaw, float targetThrottle) {
        this.entityId       = entityId;
        this.targetYaw      = targetYaw;
        this.targetThrottle = targetThrottle;
    }

    // ─── Serialization ───────────────────────────────────────────────────────
    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeFloat(targetYaw);
        buf.writeFloat(targetThrottle);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        entityId       = buf.readInt();
        targetYaw      = buf.readFloat();
        targetThrottle = buf.readFloat();
    }

    // ─── Handler (CLIENT) ────────────────────────────────────────────────────
    @SideOnly(Side.CLIENT)
    public static class Handler implements IMessageHandler<PacketAutopilotVisual, IMessage> {
        @Override
        public IMessage onMessage(PacketAutopilotVisual msg, MessageContext ctx) {
            if (Float.isNaN(msg.targetYaw)) {
                CLIENT_HEADINGS.remove(msg.entityId);
            } else {
                CLIENT_HEADINGS.put(msg.entityId, msg.targetYaw);
            }
            if (Float.isNaN(msg.targetThrottle)) {
                CLIENT_THROTTLES.remove(msg.entityId);
            } else {
                CLIENT_THROTTLES.put(msg.entityId, msg.targetThrottle);
            }
            return null;
        }
    }
}
