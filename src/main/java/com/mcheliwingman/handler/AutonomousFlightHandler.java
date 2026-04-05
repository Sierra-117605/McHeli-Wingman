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
        // 滑走路 A（始端）と B（終端）の両マーカーが必要
        MarkerRegistry.MarkerInfo rwyA = MarkerRegistry.findById(ws, MarkerType.RUNWAY_A, node.runwayId);
        MarkerRegistry.MarkerInfo rwyB = MarkerRegistry.findById(ws, MarkerType.RUNWAY_B, node.runwayId);
        if (rwyA == null || rwyB == null) {
            McHeliWingman.logger.warn("[Auto] {} TAKEOFF: runway '{}' A or B not found, skipping", shortId(wingman), node.runwayId);
            entry.advanceMission();
            return;
        }

        double ax = rwyA.pos.getX() + 0.5, ay = rwyA.pos.getY(), az = rwyA.pos.getZ() + 0.5;
        double bx = rwyB.pos.getX() + 0.5,                       bz = rwyB.pos.getZ() + 0.5;

        // A→B の単位方向ベクトル（滑走路軸）
        double rdx = bx - ax, rdz = bz - az;
        double rlen = Math.sqrt(rdx * rdx + rdz * rdz);
        if (rlen < 1) { entry.advanceMission(); return; }  // A と B が同一座標
        double dirX = rdx / rlen, dirZ = rdz / rlen;

        switch (entry.autoState) {
            case TAXI_OUT: {
                // 始端 A へ地上滑走（B 方向を遠方に設定しておき滑走路に機首を向けさせる）
                double dist = Math.sqrt(Math.pow(ax - wingman.posX, 2) + Math.pow(az - wingman.posZ, 2));
                entry.autoTargetX = ax + dirX * 2000;   // A 通過後も B 方向へ誘導
                entry.autoTargetY = ay;
                entry.autoTargetZ = az + dirZ * 2000;
                if (dist < TAXI_DIST) {
                    entry.autoState = AutonomousState.TAKEOFF_ROLL;
                    McHeliWingman.logger.info("[Auto] {} at runway A, starting roll", shortId(wingman));
                }
                break;
            }
            case TAKEOFF_ROLL: {
                // スロットル全開・滑走路方向（A→B）へ直進
                setThrottle(wingman, 1.0);
                entry.autoTargetX = ax + dirX * 2000;
                entry.autoTargetY = ay;
                entry.autoTargetZ = az + dirZ * 2000;
                double spd = Math.sqrt(wingman.motionX * wingman.motionX + wingman.motionZ * wingman.motionZ);
                if (spd >= TAKEOFF_SPEED) {
                    entry.autoState = AutonomousState.CLIMB;
                    McHeliWingman.logger.info("[Auto] {} airborne", shortId(wingman));
                }
                break;
            }
            case CLIMB: {
                // 滑走路軸方向を保ったまま巡航高度まで上昇
                entry.autoTargetX = ax + dirX * 2000;
                entry.autoTargetY = CRUISE_ALT;
                entry.autoTargetZ = az + dirZ * 2000;
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
        // 滑走路 A（停止点）と B（着陸進入端）の両マーカーが必要
        MarkerRegistry.MarkerInfo rwyA = MarkerRegistry.findById(ws, MarkerType.RUNWAY_A, node.runwayId);
        MarkerRegistry.MarkerInfo rwyB = MarkerRegistry.findById(ws, MarkerType.RUNWAY_B, node.runwayId);
        if (rwyA == null || rwyB == null) {
            McHeliWingman.logger.warn("[Auto] {} LAND: runway '{}' A or B not found, skipping", shortId(wingman), node.runwayId);
            entry.advanceMission();
            return;
        }

        double ax = rwyA.pos.getX() + 0.5, ay = rwyA.pos.getY(), az = rwyA.pos.getZ() + 0.5;
        double bx = rwyB.pos.getX() + 0.5, by = rwyB.pos.getY(), bz = rwyB.pos.getZ() + 0.5;

        // A→B 単位ベクトル（離陸方向）。着陸は逆向き B→A
        double rdx = bx - ax, rdz = bz - az;
        double rlen = Math.sqrt(rdx * rdx + rdz * rdz);
        if (rlen < 1) { entry.advanceMission(); return; }
        double dirX = rdx / rlen, dirZ = rdz / rlen;

        switch (entry.autoState) {
            case DESCEND: {
                // B の先方（B→A の逆、つまり A→B 延長線上）500 ブロック・高度 80 の進入ポイントへ
                // ＝ B から A→B 方向に 500 ブロック進んだ点（B の外側）
                double epX = bx + dirX * 500;
                double epY = by + 80;
                double epZ = bz + dirZ * 500;
                entry.autoTargetX = epX; entry.autoTargetY = epY; entry.autoTargetZ = epZ;
                double dist = Math.sqrt(Math.pow(epX - wingman.posX, 2)
                                      + Math.pow(epY - wingman.posY, 2)
                                      + Math.pow(epZ - wingman.posZ, 2));
                if (dist < 40) {
                    entry.autoState = AutonomousState.APPROACH;
                    McHeliWingman.logger.info("[Auto] {} starting approach", shortId(wingman));
                }
                break;
            }
            case APPROACH: {
                // 接地点 B へグライドパス降下（進入ポイント → B に向けて B→A 方向に飛行）
                entry.autoTargetX = bx;
                entry.autoTargetY = by + 1;
                entry.autoTargetZ = bz;
                double hDist = Math.sqrt(Math.pow(bx - wingman.posX, 2) + Math.pow(bz - wingman.posZ, 2));
                if (hDist < TAXI_DIST) {
                    entry.autoState = AutonomousState.LANDING;
                    McHeliWingman.logger.info("[Auto] {} touchdown at B", shortId(wingman));
                }
                break;
            }
            case LANDING: {
                // 接地後スロットルゼロ → A 方向へ惰性で転がり停止
                setThrottle(wingman, 0.0);
                double spd = Math.sqrt(wingman.motionX * wingman.motionX + wingman.motionZ * wingman.motionZ);
                if (spd < LANDING_SPEED) {
                    McHeliWingman.logger.info("[Auto] {} stopped", shortId(wingman));
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
