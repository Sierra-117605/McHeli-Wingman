package com.mcheliwingman.handler;

import com.mcheliwingman.McHeliWingman;
import com.mcheliwingman.block.MarkerType;
import com.mcheliwingman.mission.AutonomousState;
import com.mcheliwingman.mission.MissionNode;
import com.mcheliwingman.registry.MarkerRegistry;
import com.mcheliwingman.wingman.WingmanEntry;
import com.mcheliwingman.wingman.WingmanRegistry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.IMob;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

/**
 * 自律飛行の状態機械。WingmanTickHandler と役割分担:
 *   WingmanTickHandler  — スロットル・ヨー・ピッチ・武器（毎tick低レベル制御）
 *   AutonomousFlightHandler — ミッションノードの進行管理（高レベル指示）
 *
 * このハンドラはミッションの「何をするか」を決定し、
 * WingmanEntry の leader / attackMode / formationSlot などを書き換えることで
 * WingmanTickHandler に実際の飛行制御を委譲する。
 */
public class AutonomousFlightHandler {

    // 各フェーズの到達判定距離
    private static final double ARRIVE_DIST   = 8.0;   // FLY_TO 到達判定
    private static final double TAXI_DIST     = 5.0;   // 地上滑走 到達判定
    private static final double CRUISE_ALT    = 80.0;  // デフォルト巡航高度（Y座標）
    private static final double TAKEOFF_SPEED = 0.6;   // 離陸判定速度 (blocks/tick)
    private static final double LANDING_SPEED = 0.1;   // 着陸完了判定速度

    // リフレクション
    private Method setRotYawMethod;
    private Method getCurrentThrottleMethod;
    private Method setCurrentThrottleMethod;
    private boolean rotResolved;
    private boolean throttleResolved;

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.world.isRemote || event.phase != TickEvent.Phase.START) return;
        WorldServer ws = (WorldServer) event.world;

        for (Map.Entry<UUID, WingmanEntry> e : WingmanRegistry.snapshot().entrySet()) {
            WingmanEntry entry = e.getValue();
            if (!entry.isAutonomous()) continue;

            Entity wingman = ws.getEntityFromUuid(e.getKey());
            if (wingman == null || wingman.isDead) continue;

            tickMission(ws, wingman, entry);
        }
    }

    private void tickMission(WorldServer ws, Entity wingman, WingmanEntry entry) {
        MissionNode node = entry.currentNode();
        if (node == null) {
            // ミッション完了
            entry.autoState = AutonomousState.PARKED;
            entry.mission   = null;
            McHeliWingman.logger.info("[Auto] {} mission complete", shortId(wingman));
            return;
        }

        switch (node.type) {
            case FLY_TO:    tickFlyTo(ws, wingman, entry, node);    break;
            case TAKEOFF:   tickTakeoff(ws, wingman, entry, node);  break;
            case LAND:      tickLand(ws, wingman, entry, node);     break;
            case ATTACK:    tickAttack(ws, wingman, entry, node);   break;
            case LOITER:    tickLoiter(ws, wingman, entry, node);   break;
            case PARK:      tickPark(ws, wingman, entry, node);     break;
        }
    }

    // ─── FLY_TO ──────────────────────────────────────────────────────────────

    private void tickFlyTo(WorldServer ws, Entity wingman, WingmanEntry entry, MissionNode node) {
        entry.autoState = AutonomousState.ENROUTE;
        // WingmanTickHandler に目標を伝えるために仮想 leader を設定する代わりに
        // 自律用の「仮想スロット0・leader=null」で computeMoveTarget が formationPos を返す。
        // → AutonomousFlightHandler が直接 setPosition の目的地を指定する方式をとる。
        // 実装: leader を null にしてフライトロジックは WingmanTickHandler の steerToTarget に任せ、
        //       formationPos の代わりに autoTargetPos をセットする。
        // ここでは entry.autoTargetX/Y/Z に目標を書き込み、WingmanTickHandler 側で参照する。
        entry.autoTargetX = node.x;
        entry.autoTargetY = node.y;
        entry.autoTargetZ = node.z;
        entry.autoState   = AutonomousState.ENROUTE;

        double dx = node.x - wingman.posX;
        double dy = node.y - wingman.posY;
        double dz = node.z - wingman.posZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < ARRIVE_DIST) {
            McHeliWingman.logger.info("[Auto] {} FLY_TO reached ({},{},{})", shortId(wingman), (int)node.x, (int)node.y, (int)node.z);
            entry.advanceMission();
        }
    }

    // ─── TAKEOFF ─────────────────────────────────────────────────────────────

    private void tickTakeoff(WorldServer ws, Entity wingman, WingmanEntry entry, MissionNode node) {
        switch (entry.autoState) {
            case TAXI_OUT: {
                // 滑走路端Aへ地上滑走
                MarkerRegistry.MarkerInfo rwy = MarkerRegistry.findById(ws, MarkerType.RUNWAY_A, node.runwayId);
                if (rwy == null) { entry.advanceMission(); return; }
                double tx = rwy.pos.getX() + 0.5;
                double tz = rwy.pos.getZ() + 0.5;
                double dist = Math.sqrt(Math.pow(tx - wingman.posX, 2) + Math.pow(tz - wingman.posZ, 2));
                entry.autoTargetX = tx;
                entry.autoTargetY = wingman.posY;  // 地上滑走: Y維持
                entry.autoTargetZ = tz;
                if (dist < TAXI_DIST) {
                    entry.autoState = AutonomousState.TAKEOFF_ROLL;
                }
                break;
            }
            case TAKEOFF_ROLL: {
                // スロットル全開・滑走路方向へ直進
                setThrottle(wingman, 1.0);
                double spd = Math.sqrt(wingman.motionX * wingman.motionX + wingman.motionZ * wingman.motionZ);
                if (spd >= TAKEOFF_SPEED) {
                    entry.autoState = AutonomousState.CLIMB;
                    McHeliWingman.logger.info("[Auto] {} airborne", shortId(wingman));
                }
                break;
            }
            case CLIMB: {
                // 現在の向きで前方500ブロックを目標にしながら巡航高度まで上昇
                // （autoTargetX/Z を自機位置にすると目標が動いて旋回してしまうため）
                double yawRad = Math.toRadians(wingman.rotationYaw);
                entry.autoTargetX = wingman.posX - Math.sin(yawRad) * 500;
                entry.autoTargetY = CRUISE_ALT;
                entry.autoTargetZ = wingman.posZ + Math.cos(yawRad) * 500;
                if (wingman.posY >= CRUISE_ALT - 5) {
                    McHeliWingman.logger.info("[Auto] {} reached cruise alt", shortId(wingman));
                    entry.advanceMission();
                }
                break;
            }
            default:
                entry.autoState = AutonomousState.TAXI_OUT;
        }
    }

    // ─── LAND ────────────────────────────────────────────────────────────────

    private void tickLand(WorldServer ws, Entity wingman, WingmanEntry entry, MissionNode node) {
        switch (entry.autoState) {
            case DESCEND: {
                // 滑走路端Bの上空 50ブロックへ降下
                MarkerRegistry.MarkerInfo rwy = MarkerRegistry.findById(ws, MarkerType.RUNWAY_B, node.runwayId);
                if (rwy == null) { entry.advanceMission(); return; }
                double tx = rwy.pos.getX() + 0.5;
                double ty = rwy.pos.getY() + 50;
                double tz = rwy.pos.getZ() + 0.5;
                entry.autoTargetX = tx; entry.autoTargetY = ty; entry.autoTargetZ = tz;
                double dist = Math.sqrt(Math.pow(tx - wingman.posX, 2) + Math.pow(ty - wingman.posY, 2) + Math.pow(tz - wingman.posZ, 2));
                if (dist < 20) entry.autoState = AutonomousState.APPROACH;
                break;
            }
            case APPROACH: {
                // 滑走路端Bへ直接降下アプローチ
                MarkerRegistry.MarkerInfo rwy = MarkerRegistry.findById(ws, MarkerType.RUNWAY_B, node.runwayId);
                if (rwy == null) { entry.advanceMission(); return; }
                double tx = rwy.pos.getX() + 0.5;
                double ty = rwy.pos.getY() + 1;
                double tz = rwy.pos.getZ() + 0.5;
                entry.autoTargetX = tx; entry.autoTargetY = ty; entry.autoTargetZ = tz;
                double hDist = Math.sqrt(Math.pow(tx - wingman.posX, 2) + Math.pow(tz - wingman.posZ, 2));
                if (hDist < TAXI_DIST) entry.autoState = AutonomousState.LANDING;
                break;
            }
            case LANDING: {
                // 接地後スロットルゼロ
                setThrottle(wingman, 0.0);
                double spd = Math.sqrt(wingman.motionX * wingman.motionX + wingman.motionZ * wingman.motionZ);
                if (spd < LANDING_SPEED) {
                    McHeliWingman.logger.info("[Auto] {} landed", shortId(wingman));
                    entry.advanceMission();
                }
                break;
            }
            default:
                entry.autoState = AutonomousState.DESCEND;
        }
    }

    // ─── ATTACK ──────────────────────────────────────────────────────────────

    private void tickAttack(WorldServer ws, Entity wingman, WingmanEntry entry, MissionNode node) {
        entry.autoState = AutonomousState.ATTACK;
        // 半径内に敵がいる間は ATK_AUTO を維持、いなくなったら次ノードへ
        boolean hasTarget = false;
        for (Entity e : wingman.world.loadedEntityList) {
            if (!(e instanceof IMob) || e.isDead) continue;
            if (wingman.getDistanceSq(e) < node.attackRadius * node.attackRadius) { hasTarget = true; break; }
        }
        entry.attackMode = hasTarget ? WingmanEntry.ATK_AUTO : WingmanEntry.ATK_NONE;
        if (!hasTarget) {
            McHeliWingman.logger.info("[Auto] {} ATTACK node complete (no targets in radius)", shortId(wingman));
            entry.advanceMission();
        }
    }

    // ─── LOITER ──────────────────────────────────────────────────────────────

    private void tickLoiter(WorldServer ws, Entity wingman, WingmanEntry entry, MissionNode node) {
        entry.autoState = AutonomousState.LOITER;
        entry.missionNodeTimer++;
        // 旋回: 現在位置を中心に半径20ブロックで旋回するため、目標点を回転させる
        double angle = (entry.missionNodeTimer * 2.0) * Math.PI / 200.0;  // 200tickで一周
        entry.autoTargetX = wingman.posX + Math.cos(angle) * 20;
        entry.autoTargetY = wingman.posY;
        entry.autoTargetZ = wingman.posZ + Math.sin(angle) * 20;
        if (entry.missionNodeTimer >= node.durationTicks) {
            McHeliWingman.logger.info("[Auto] {} LOITER complete", shortId(wingman));
            entry.advanceMission();
        }
    }

    // ─── PARK ────────────────────────────────────────────────────────────────

    private void tickPark(WorldServer ws, Entity wingman, WingmanEntry entry, MissionNode node) {
        entry.autoState = AutonomousState.TAXI_IN;
        MarkerRegistry.MarkerInfo parking = MarkerRegistry.findById(ws, MarkerType.PARKING, node.parkingId);
        if (parking == null) { entry.advanceMission(); return; }
        double tx = parking.pos.getX() + 0.5;
        double tz = parking.pos.getZ() + 0.5;
        entry.autoTargetX = tx;
        entry.autoTargetY = wingman.posY;
        entry.autoTargetZ = tz;
        setThrottle(wingman, 0.2);  // 低速タクシー
        double dist = Math.sqrt(Math.pow(tx - wingman.posX, 2) + Math.pow(tz - wingman.posZ, 2));
        if (dist < TAXI_DIST) {
            setThrottle(wingman, 0.0);
            entry.autoState = AutonomousState.PARKED;
            McHeliWingman.logger.info("[Auto] {} PARKED at {}", shortId(wingman), node.parkingId);
            entry.advanceMission();
        }
    }

    // ─── Throttle helper ─────────────────────────────────────────────────────

    private void setThrottle(Entity aircraft, double throttle) {
        if (!throttleResolved) {
            throttleResolved = true;
            setCurrentThrottleMethod = findMethod(aircraft.getClass(), "setCurrentThrottle", double.class);
        }
        try { if (setCurrentThrottleMethod != null) setCurrentThrottleMethod.invoke(aircraft, throttle); }
        catch (Exception ignored) {}
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        while (cls != null) {
            try { Method m = cls.getDeclaredMethod(name, params); m.setAccessible(true); return m; }
            catch (NoSuchMethodException ignored) { cls = cls.getSuperclass(); }
        }
        return null;
    }

    private static String shortId(Entity e) {
        return e.getUniqueID().toString().substring(0, 8);
    }
}
