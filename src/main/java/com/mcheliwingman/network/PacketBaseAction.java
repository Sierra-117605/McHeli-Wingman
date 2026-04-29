package com.mcheliwingman.network;

import com.mcheliwingman.mission.AutonomousState;
import com.mcheliwingman.mission.MissionOrder;
import com.mcheliwingman.mission.MissionType;
import com.mcheliwingman.mission.TaxiRoute;
import com.mcheliwingman.registry.TaxiRouteRegistry;
import com.mcheliwingman.util.McheliReflect;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client → Server: タキシールートの保存/削除、またはミッションオーダーの発令を行う。
 */
public class PacketBaseAction implements IMessage {

    public static final int SAVE_ROUTE     = 0;
    public static final int DELETE_ROUTE   = 1;
    public static final int DISPATCH_ORDER = 2;

    public int action;

    // SAVE_ROUTE / DELETE_ROUTE 共通
    public String routeId = "", baseId = "", parkingId = "", runwayId = "", runwayBId = "";
    public String waypointsCsv = "";
    /** 着陸時専用WP（CSV）。空の場合は waypointsCsv の逆順を使用。 */
    public String arrivalWaypointsCsv = "";
    /** 着陸エントリー端点 ID。空の場合は runwayId を使用。 */
    public String arrivalRunwayId = "";
    /** 駐機方位: -1=任意, 0=N, 1=E, 2=S, 3=W */
    public int parkingHeading = -1;

    // DISPATCH_ORDER
    public String wingmanUuid = "";
    public String missionTypesCsv = "";
    public String weaponsCsv = "";
    public double targetX = 0, targetZ = 0, orbitRadius = 300, cruiseAlt = 80;
    public int    strikePasses = 2, timeLimitSeconds = 600;
    public String ferryDestBase = "";
    public boolean orbitAttack = false;
    public boolean useVstol    = false;
    public String arrivalRouteId = "";

    public PacketBaseAction() {}

    // ─── Serialization ───────────────────────────────────────────────────────

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(action);
        writeStr(buf, routeId);   writeStr(buf, baseId);
        writeStr(buf, parkingId); writeStr(buf, runwayId); writeStr(buf, runwayBId);
        writeStr(buf, waypointsCsv);
        buf.writeInt(parkingHeading);
        writeStr(buf, arrivalWaypointsCsv);
        writeStr(buf, arrivalRunwayId);
        writeStr(buf, wingmanUuid);
        writeStr(buf, missionTypesCsv); writeStr(buf, weaponsCsv);
        buf.writeDouble(targetX);    buf.writeDouble(targetZ);
        buf.writeDouble(orbitRadius); buf.writeDouble(cruiseAlt);
        buf.writeInt(strikePasses);   buf.writeInt(timeLimitSeconds);
        writeStr(buf, ferryDestBase);
        buf.writeBoolean(orbitAttack);
        buf.writeBoolean(useVstol);
        writeStr(buf, arrivalRouteId);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        action    = buf.readInt();
        routeId   = readStr(buf); baseId     = readStr(buf);
        parkingId = readStr(buf); runwayId   = readStr(buf); runwayBId = readStr(buf);
        waypointsCsv = readStr(buf);
        parkingHeading = buf.isReadable() ? buf.readInt() : -1;
        arrivalWaypointsCsv = buf.isReadable() ? readStr(buf) : "";
        arrivalRunwayId     = buf.isReadable() ? readStr(buf) : "";
        wingmanUuid     = readStr(buf);
        missionTypesCsv = readStr(buf); weaponsCsv = readStr(buf);
        targetX = buf.readDouble();    targetZ    = buf.readDouble();
        orbitRadius = buf.readDouble(); cruiseAlt = buf.readDouble();
        strikePasses = buf.readInt();  timeLimitSeconds = buf.readInt();
        ferryDestBase = readStr(buf);
        orbitAttack     = buf.isReadable() && buf.readBoolean();
        useVstol        = buf.isReadable() && buf.readBoolean();
        arrivalRouteId  = buf.isReadable() ? readStr(buf) : "";
    }

    // ─── Handler (SERVER) ────────────────────────────────────────────────────

    public static class Handler implements IMessageHandler<PacketBaseAction, IMessage> {
        @Override
        public IMessage onMessage(PacketBaseAction msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            WorldServer ws = player.getServerWorld();
            ws.addScheduledTask(() -> handle(msg, player, ws));
            return null;
        }

        private void handle(PacketBaseAction msg, EntityPlayerMP player, WorldServer ws) {
            switch (msg.action) {
                case SAVE_ROUTE:     handleSaveRoute(msg, player, ws);     break;
                case DELETE_ROUTE:   handleDeleteRoute(msg, player, ws);   break;
                case DISPATCH_ORDER: handleDispatchOrder(msg, player, ws); break;
            }
        }

        // ── SAVE_ROUTE ────────────────────────────────────────────────────────

        private void handleSaveRoute(PacketBaseAction msg, EntityPlayerMP player, WorldServer ws) {
            if (msg.routeId.isEmpty() || msg.baseId.isEmpty()
                    || msg.parkingId.isEmpty() || msg.runwayId.isEmpty()) {
                player.sendMessage(new TextComponentString(
                    "§cRoute requires routeId, baseId, parkingId, runwayId."));
                return;
            }
            List<String> wps = new ArrayList<>();
            if (!msg.waypointsCsv.isEmpty()) {
                for (String s : msg.waypointsCsv.split(",")) {
                    String t = s.trim();
                    if (!t.isEmpty()) wps.add(t);
                }
            }
            List<String> arrWps = new ArrayList<>();
            if (!msg.arrivalWaypointsCsv.isEmpty()) {
                for (String s : msg.arrivalWaypointsCsv.split(",")) {
                    String t = s.trim();
                    if (!t.isEmpty()) arrWps.add(t);
                }
            }
            TaxiRoute route = new TaxiRoute(
                msg.routeId, msg.baseId, msg.parkingId, msg.runwayId, msg.runwayBId, wps,
                arrWps, msg.parkingHeading, msg.arrivalRunwayId);
            TaxiRouteRegistry.save(ws, route);
            player.sendMessage(new TextComponentString("§aRoute §e" + msg.routeId + "§a saved."));
        }

        // ── DELETE_ROUTE ──────────────────────────────────────────────────────

        private void handleDeleteRoute(PacketBaseAction msg, EntityPlayerMP player, WorldServer ws) {
            TaxiRouteRegistry.delete(ws, msg.routeId);
            player.sendMessage(new TextComponentString("§aRoute §e" + msg.routeId + "§a deleted."));
        }

        // ── DISPATCH_ORDER ────────────────────────────────────────────────────

        private void handleDispatchOrder(PacketBaseAction msg, EntityPlayerMP player, WorldServer ws) {
            UUID uid;
            try {
                uid = UUID.fromString(msg.wingmanUuid);
            } catch (Exception e) {
                player.sendMessage(new TextComponentString("§cInvalid UUID: " + msg.wingmanUuid));
                return;
            }
            Entity wingman = ws.getEntityFromUuid(uid);
            if (wingman == null) {
                player.sendMessage(new TextComponentString("§cEntity not found."));
                return;
            }

            MissionOrder order = new MissionOrder();
            order.baseId            = msg.baseId;
            order.targetX           = msg.targetX;
            order.targetZ           = msg.targetZ;
            order.orbitRadius       = msg.orbitRadius;
            order.cruiseAlt         = msg.cruiseAlt;
            order.strikePasses      = msg.strikePasses;
            order.timeLimitSeconds  = msg.timeLimitSeconds;
            order.ferryDestBase     = msg.ferryDestBase;
            order.orbitAttack       = msg.orbitAttack;
            order.useVstol          = msg.useVstol;
            order.arrivalRouteId    = msg.arrivalRouteId;

            for (String s : msg.missionTypesCsv.split(",")) {
                try { order.missionTypes.add(MissionType.valueOf(s.trim().toUpperCase())); }
                catch (Exception ignored) {}
            }
            for (String s : msg.weaponsCsv.split(",")) {
                String t = s.trim().toLowerCase();
                if (!t.isEmpty()) order.weapons.add(t);
            }

            if (order.missionTypes.isEmpty()) {
                player.sendMessage(new TextComponentString("§cNo valid mission types."));
                return;
            }

            // 燃料を満タンにしてから発令（spawned 直後で currentFuel=0 の場合の即 RTB を防ぐ）
            McheliReflect.fillFuel(wingman);

            WingmanEntry entry = WingmanRegistry.get(uid);
            if (entry == null) {
                entry = new WingmanEntry();
                WingmanRegistry.put(uid, entry);
            }
            entry.order                  = order;
            entry.orderTimer             = 0;
            entry.orbitAngle             = 0.0;
            entry.strikePassesRemaining  = order.strikePasses;
            entry.reconMobCount          = 0;
            entry.rtbReason              = "";
            entry.attackMode             = WingmanEntry.ATK_NONE; // 再発令時に攻撃モードが残るのを防ぐ
            entry.diagTick               = 0;
            entry.autoState              = AutonomousState.NONE; // tickOrder() が初期化
            // VTOL ノズルを格納モードにリセット。
            // 以前の VTOL_TAKEOFF ミッションや手動 VTOL 操作でノズルが展開されたまま残っていると、
            // スロットル全開時に垂直推力が発生して滑走なしに即離陸してしまう。
            // vtolOnSent=true により、次の WTH tick で forceVtolOff() が呼ばれノズルが格納される。
            // VTOL ヘリパッドルートの場合は initOrder() が vtolHoverMode=true に上書きする。
            entry.vtolHoverMode = false;
            entry.vtolOnSent    = true;

            // タキシールートを routeId → parkingId で解決。なければ空中発進。
            if (!msg.routeId.isEmpty()) {
                TaxiRoute route = TaxiRouteRegistry.findById(ws, msg.routeId);
                if (route != null) {
                    entry.assignedParkingId = route.parkingId;
                    entry.departureRouteId  = msg.routeId;  // findById で確実にルートを取得するため
                    player.sendMessage(new TextComponentString(
                        "§aTaxi route §e" + route.routeId + "§a → parking §e" + route.parkingId));
                } else {
                    player.sendMessage(new TextComponentString(
                        "§cRoute not found: " + msg.routeId + " — airborne start"));
                    entry.assignedParkingId = "";
                    entry.departureRouteId  = "";
                }
            } else {
                entry.assignedParkingId = ""; // 空中発進
                entry.departureRouteId  = "";
            }
            // 主攻撃武器を WingmanTickHandler 用に設定（GUN 以外の最初の武器）
            entry.weaponType = pickPrimaryWeapon(order);

            player.sendMessage(new TextComponentString(
                "§aOrder dispatched: §e" + msg.missionTypesCsv
                + "§a → " + msg.wingmanUuid.substring(0, 8) + "..."));
        }

        /** GUN以外の最初の武器を主攻撃武器として返す。GUNのみなら null（全武器）。 */
        private String pickPrimaryWeapon(MissionOrder order) {
            for (String w : order.weapons) {
                if (!"gun".equals(w)) return w;
            }
            return null;
        }
    }

    // ─── String helpers ──────────────────────────────────────────────────────

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
