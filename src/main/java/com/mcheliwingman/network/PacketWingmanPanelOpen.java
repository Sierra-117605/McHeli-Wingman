package com.mcheliwingman.network;

import com.mcheliwingman.config.WingmanConfig;
import com.mcheliwingman.util.McheliReflect;
import com.mcheliwingman.wingman.WingmanEntry;
import com.mcheliwingman.wingman.WingmanRegistry;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.Map;
import java.util.UUID;

/**
 * C → S: 搭乗中プレイヤーがウィングマンパネルの表示を要求する。
 * サーバーはデータを収集して PacketWingmanPanelData を返す。
 */
public class PacketWingmanPanelOpen implements IMessage {

    @Override public void fromBytes(ByteBuf buf) {}
    @Override public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<PacketWingmanPanelOpen, IMessage> {
        @Override
        public IMessage onMessage(PacketWingmanPanelOpen msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            WorldServer ws = (WorldServer) player.world;
            ws.addScheduledTask(() -> {
                PacketWingmanPanelData pkt = buildData(player, ws);
                WingmanNetwork.sendToPlayer(pkt, player);
            });
            return null;
        }

        private static PacketWingmanPanelData buildData(EntityPlayerMP player, WorldServer ws) {
            PacketWingmanPanelData pkt = new PacketWingmanPanelData();

            // 編隊パラメータ
            pkt.sideDist  = WingmanConfig.formationSideDist;
            pkt.altOffset = WingmanConfig.formationAltOffset;
            pkt.rearDist  = WingmanConfig.formationRearDist;
            pkt.maxWings  = WingmanConfig.maxWingmen;
            pkt.minAlt    = WingmanConfig.minAttackAltitude;
            pkt.maxAlt    = WingmanConfig.maxAttackAltitude;

            Entity leader = player.getRidingEntity();
            if (!McheliReflect.isAircraft(leader)) return pkt;

            Map<UUID, WingmanEntry> registry = WingmanRegistry.snapshot();

            // ウィングマン一覧（このリーダーに従属しているもの）
            for (Map.Entry<UUID, WingmanEntry> e : registry.entrySet()) {
                WingmanEntry entry = e.getValue();
                if (entry.leader != leader) continue;

                Entity wEnt = ws.getEntityFromUuid(e.getKey());
                PacketWingmanPanelData.WingmanDto dto = new PacketWingmanPanelData.WingmanDto();
                dto.uuid       = e.getKey().toString();
                dto.name       = getAircraftName(wEnt);
                dto.slot       = entry.formationSlot;
                dto.state      = entry.leader != null ? "FOLLOWING" : entry.autoState.name();
                dto.attackMode = entry.attackMode;
                dto.weaponType = entry.weaponType != null ? entry.weaponType : "";
                pkt.wingmen.add(dto);
            }

            // 近傍の未割り当て機体（512ブロック以内）
            for (Entity e : ws.loadedEntityList) {
                if (!McheliReflect.isAircraft(e)) continue;
                if (e == leader) continue;
                if (registry.containsKey(e.getUniqueID())) continue;
                if (leader.getDistanceSq(e) > 512.0 * 512.0) continue;

                PacketWingmanPanelData.AircraftDto dto = new PacketWingmanPanelData.AircraftDto();
                dto.uuid = e.getUniqueID().toString();
                dto.name = getAircraftName(e);
                pkt.nearby.add(dto);
                if (pkt.nearby.size() >= 8) break;
            }

            return pkt;
        }

        private static String getAircraftName(Entity e) {
            if (e == null) return "?";
            for (String m : new String[]{"getTypeName", "getKindName"}) {
                try {
                    Object r = e.getClass().getMethod(m).invoke(e);
                    if (r instanceof String && !((String) r).isEmpty()) return (String) r;
                } catch (Exception ignored) {}
            }
            return e.getClass().getSimpleName();
        }
    }
}
