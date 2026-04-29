package com.mcheliwingman.network;

import com.mcheliwingman.config.WingmanConfig;
import com.mcheliwingman.wingman.WingmanEntry;
import com.mcheliwingman.wingman.WingmanRegistry;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.Map;
import java.util.UUID;

/**
 * C → S: ウィングマンパネルからの各種操作。
 */
public class PacketWingmanPanelAction implements IMessage {

    public static final int FOLLOW    = 0;
    public static final int STOP      = 1;
    public static final int STOP_ALL  = 2;
    public static final int AUTO      = 3;
    public static final int HOLD      = 4;
    public static final int WEAPON    = 5;
    public static final int FORMATION = 6;

    public int    action      = 0;
    public String uuid        = "";   // ウィングマン UUID (FOLLOW/STOP/AUTO/HOLD/WEAPON)
    public String extra       = "";   // weaponType (WEAPON), target UUID (ENGAGE) 等

    // FORMATION専用フィールド
    public double sideDist  = 20.0;
    public double altOffset = 0.0;
    public double rearDist  = 30.0;
    public int    maxWings  = 4;
    public double minAlt    = 0.0;
    public double maxAlt    = 0.0;

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(action);
        ByteBufUtils.writeUTF8String(buf, uuid);
        ByteBufUtils.writeUTF8String(buf, extra);
        buf.writeDouble(sideDist);
        buf.writeDouble(altOffset);
        buf.writeDouble(rearDist);
        buf.writeInt(maxWings);
        buf.writeDouble(minAlt);
        buf.writeDouble(maxAlt);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        action    = buf.readInt();
        uuid      = ByteBufUtils.readUTF8String(buf);
        extra     = ByteBufUtils.readUTF8String(buf);
        sideDist  = buf.readDouble();
        altOffset = buf.readDouble();
        rearDist  = buf.readDouble();
        maxWings  = buf.readInt();
        minAlt    = buf.readDouble();
        maxAlt    = buf.readDouble();
    }

    public static class Handler implements IMessageHandler<PacketWingmanPanelAction, IMessage> {
        @Override
        public IMessage onMessage(PacketWingmanPanelAction msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            WorldServer ws = (WorldServer) player.world;
            ws.addScheduledTask(() -> handle(msg, player, ws));
            return null;
        }

        private static void handle(PacketWingmanPanelAction msg, EntityPlayerMP player, WorldServer ws) {
            switch (msg.action) {

                case FOLLOW: {
                    Entity leader = player.getRidingEntity();
                    if (leader == null) break;
                    UUID uid = tryParseUUID(msg.uuid);
                    if (uid == null) break;
                    Entity wingman = ws.getEntityFromUuid(uid);
                    if (wingman == null) break;
                    int slot = WingmanRegistry.countForLeader(leader);
                    if (slot >= WingmanConfig.maxWingmen) break;
                    WingmanRegistry.put(uid, new WingmanEntry(leader, slot));
                    break;
                }

                case STOP: {
                    UUID uid = tryParseUUID(msg.uuid);
                    if (uid == null) break;
                    WingmanRegistry.remove(uid);
                    break;
                }

                case STOP_ALL: {
                    Entity leader = player.getRidingEntity();
                    if (leader == null) break;
                    WingmanRegistry.removeForLeader(leader);
                    break;
                }

                case AUTO: {
                    UUID uid = tryParseUUID(msg.uuid);
                    if (uid == null) break;
                    WingmanEntry e = WingmanRegistry.get(uid);
                    if (e != null) {
                        e.attackMode = WingmanEntry.ATK_AUTO;
                        e.manualTargetId = null;
                    }
                    break;
                }

                case HOLD: {
                    UUID uid = tryParseUUID(msg.uuid);
                    if (uid == null) break;
                    WingmanEntry e = WingmanRegistry.get(uid);
                    if (e != null) {
                        e.attackMode = WingmanEntry.ATK_NONE;
                        e.manualTargetId = null;
                    }
                    break;
                }

                case WEAPON: {
                    UUID uid = tryParseUUID(msg.uuid);
                    if (uid == null) break;
                    WingmanEntry e = WingmanRegistry.get(uid);
                    if (e != null) {
                        e.weaponType = msg.extra.isEmpty() ? null : msg.extra;
                    }
                    break;
                }

                case FORMATION: {
                    WingmanConfig.formationSideDist   = msg.sideDist;
                    WingmanConfig.formationAltOffset  = msg.altOffset;
                    WingmanConfig.formationRearDist   = msg.rearDist;
                    WingmanConfig.maxWingmen          = Math.max(1, Math.min(64, msg.maxWings));
                    WingmanConfig.minAttackAltitude   = Math.max(0, msg.minAlt);
                    WingmanConfig.maxAttackAltitude   = Math.max(0, msg.maxAlt);
                    break;
                }
            }
        }

        private static UUID tryParseUUID(String s) {
            try { return UUID.fromString(s); } catch (Exception e) { return null; }
        }
    }
}
