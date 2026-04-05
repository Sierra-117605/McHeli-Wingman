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
    private static final double ARRIVE_DIST        = 8.0;   // FLY_TO 到達判定
    private static final double TAXI_DIST          = 5.0;   // 地上滑走 到達判定
    private static final double CRUISE_ALT         = 80.0;  // デフォルト巡航高度（Y座標）
    private static final double TAKEOFF_SPEED      = 0.6;   // 離陸判定速度 (blocks/tick)
    private static final double LANDING_SPEED      = 0.1;   // 着陸完了判定速度

    // 離陸整列・中心線追従
    private static final double ALIGN_TOLERANCE    = 15.0;  // 機首整列許容角度 (°)
    private static final double LOOKAHEAD_DIST     = 40.0;  // 滑走路中心線先読み距離 (blocks)

    // 着陸サーキット
    private static final double CIRCUIT_MIN_AGL    = 20.0;  // 滑走路面からの最低回路高度 (runway が高台の場合の保険)
    private static final double CIRCUIT_OFFSET     = 100.0; // 滑走路中心からの横距離 (blocks)
    private static final double CIRCUIT_FINAL_DIST = 300.0; // ファイナル進入開始距離 (B から blocks)
    private static final double TOUCHDOWN_DIST     = 15.0;  // 接地判定距離 (固定翼の速度でも確実に検出できる大きめ値)

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
        MarkerRegistry.MarkerInfo rwyA = MarkerRegistry.findById(ws, MarkerType.RUNWAY_A, node.runwayId);
        MarkerRegistry.MarkerInfo rwyB = MarkerRegistry.findById(ws, MarkerType.RUNWAY_B, node.runwayId);
        if (rwyA == null || rwyB == null) {
            McHeliWingman.logger.warn("[Auto] {} TAKEOFF: runway '{}' A or B not found, skipping", shortId(wingman), node.runwayId);
            entry.advanceMission();
            return;
        }

        double ax = rwyA.pos.getX() + 0.5, ay = rwyA.pos.getY(), az = rwyA.pos.getZ() + 0.5;
        double bx = rwyB.pos.getX() + 0.5,                       bz = rwyB.pos.getZ() + 0.5;

        double rdx = bx - ax, rdz = bz - az;
        double rlen = Math.sqrt(rdx * rdx + rdz * rdz);
        if (rlen < 1) { entry.advanceMission(); return; }
        double dirX = rdx / rlen, dirZ = rdz / rlen;

        // 滑走路方向の Minecraft yaw (atan2(-dx, dz))
        float runwayYaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));

        switch (entry.autoState) {
            case TAXI_OUT: {
                // A へ向かいながら B 方向を遠方目標に設定 → 機首が滑走路軸に揃う
                double dist = Math.sqrt(Math.pow(ax - wingman.posX, 2) + Math.pow(az - wingman.posZ, 2));
                entry.autoTargetX = ax + dirX * 2000;
                entry.autoTargetY = ay;
                entry.autoTargetZ = az + dirZ * 2000;
                if (dist < TAXI_DIST) {
                    entry.autoState = AutonomousState.ALIGN;
                    entry.missionNodeTimer = 0;
                    McHeliWingman.logger.info("[Auto] {} at runway A, aligning with runway", shortId(wingman));
                }
                break;
            }
            case ALIGN: {
                // 機首が滑走路方向に揃うまで待機（最大 300 tick でタイムアウト）
                entry.missionNodeTimer++;
                entry.autoTargetX = ax + dirX * 2000;
                entry.autoTargetY = ay;
                entry.autoTargetZ = az + dirZ * 2000;
                float yawDiff = runwayYaw - wingman.rotationYaw;
                while (yawDiff >  180f) yawDiff -= 360f;
                while (yawDiff < -180f) yawDiff += 360f;
                boolean aligned   = Math.abs(yawDiff) < ALIGN_TOLERANCE;
                boolean timedOut  = entry.missionNodeTimer > 300;
                if (aligned || timedOut) {
                    entry.missionNodeTimer = 0;
                    entry.autoState = AutonomousState.TAKEOFF_ROLL;
                    McHeliWingman.logger.info("[Auto] {} aligned{}, starting roll", shortId(wingman),
                                              timedOut && !aligned ? " (timeout)" : "");
                }
                break;
            }
            case TAKEOFF_ROLL: {
                // 全スロットル + 中心線追従（横ズレ補正）
                setThrottle(wingman, 1.0);
                centerlineTarget(entry, wingman, ax, ay, az, dirX, dirZ, LOOKAHEAD_DIST);
                double spd = Math.sqrt(wingman.motionX * wingman.motionX + wingman.motionZ * wingman.motionZ);
                if (spd >= TAKEOFF_SPEED) {
                    entry.autoState = AutonomousState.CLIMB;
                    McHeliWingman.logger.info("[Auto] {} airborne", shortId(wingman));
                }
                break;
            }
            case CLIMB: {
                // 滑走路軸上を中心線追従しながら巡航高度まで上昇
                centerlineTarget(entry, wingman, ax, CRUISE_ALT, az, dirX, dirZ, LOOKAHEAD_DIST);
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
        MarkerRegistry.MarkerInfo rwyA = MarkerRegistry.findById(ws, MarkerType.RUNWAY_A, node.runwayId);
        MarkerRegistry.MarkerInfo rwyB = MarkerRegistry.findById(ws, MarkerType.RUNWAY_B, node.runwayId);
        if (rwyA == null || rwyB == null) {
            McHeliWingman.logger.warn("[Auto] {} LAND: runway '{}' A or B not found, skipping", shortId(wingman), node.runwayId);
            entry.advanceMission();
            return;
        }

        double ax = rwyA.pos.getX() + 0.5, ay = rwyA.pos.getY(), az = rwyA.pos.getZ() + 0.5;
        double bx = rwyB.pos.getX() + 0.5, by = rwyB.pos.getY(), bz = rwyB.pos.getZ() + 0.5;

        double rdx = bx - ax, rdz = bz - az;
        double rlen = Math.sqrt(rdx * rdx + rdz * rdz);
        if (rlen < 1) { entry.advanceMission(); return; }
        double dirX = rdx / rlen, dirZ = rdz / rlen;

        // 左ペルペンジキュラー: A→B を 90° CCW 回転 = (-dirZ, dirX)
        // 着陸方向 (B→A に向かう機体から見た左側 = 標準左旋回サーキット)
        double perpX = -dirZ, perpZ = dirX;
        // 巡航高度をサーキット高度として使用。滑走路が高台の場合は CIRCUIT_MIN_AGL を保証。
        double circuitY = Math.max(CRUISE_ALT, by + CIRCUIT_MIN_AGL);

        switch (entry.autoState) {
            case DESCEND: {
                // サーキット入口: B の左側 CIRCUIT_OFFSET ブロック・高度 circuitY
                double epX = bx + perpX * CIRCUIT_OFFSET;
                double epZ = bz + perpZ * CIRCUIT_OFFSET;
                entry.autoTargetX = epX;
                entry.autoTargetY = circuitY;
                entry.autoTargetZ = epZ;
                double hDist = Math.sqrt(Math.pow(epX - wingman.posX, 2) + Math.pow(epZ - wingman.posZ, 2));
                if (hDist < 30) {
                    entry.autoState = AutonomousState.CIRCUIT_DOWNWIND;
                    McHeliWingman.logger.info("[Auto] {} entering downwind", shortId(wingman));
                }
                break;
            }
            case CIRCUIT_DOWNWIND: {
                // ダウンウィンド: B-側オフセット → A-側オフセット (滑走路と平行・逆方向)
                double dwX = ax + perpX * CIRCUIT_OFFSET;
                double dwZ = az + perpZ * CIRCUIT_OFFSET;
                entry.autoTargetX = dwX;
                entry.autoTargetY = circuitY;
                entry.autoTargetZ = dwZ;
                double dist = Math.sqrt(Math.pow(dwX - wingman.posX, 2) + Math.pow(dwZ - wingman.posZ, 2));
                if (dist < 30) {
                    entry.autoState = AutonomousState.CIRCUIT_BASE;
                    McHeliWingman.logger.info("[Auto] {} turning base", shortId(wingman));
                }
                break;
            }
            case CIRCUIT_BASE: {
                // ベースレグ: ファイナル延長線上 (B の先方 CIRCUIT_FINAL_DIST) へ向かう
                double fX = bx + dirX * CIRCUIT_FINAL_DIST;
                double fZ = bz + dirZ * CIRCUIT_FINAL_DIST;
                entry.autoTargetX = fX;
                entry.autoTargetY = circuitY;
                entry.autoTargetZ = fZ;
                double dist = Math.sqrt(Math.pow(fX - wingman.posX, 2) + Math.pow(fZ - wingman.posZ, 2));
                if (dist < 40) {
                    entry.autoState = AutonomousState.CIRCUIT_FINAL;
                    McHeliWingman.logger.info("[Auto] {} on final", shortId(wingman));
                }
                break;
            }
            case CIRCUIT_FINAL: {
                // ファイナル: 中心線追従 + グライドスロープで B に向けて降下
                // B を基準に、B の先方 (dir 方向) に機体がいる距離を求める
                double projFromB = (wingman.posX - bx) * dirX + (wingman.posZ - bz) * dirZ;
                double tProj = Math.max(0, projFromB - LOOKAHEAD_DIST);

                // グライドスロープ: circuitY (巡航高度) → 滑走路面 を CIRCUIT_FINAL_DIST で線形補間
                double touchdownAlt = by + 1;
                double glideAlt = touchdownAlt + tProj * ((circuitY - touchdownAlt) / CIRCUIT_FINAL_DIST);

                // 中心線追従: B + dir*tProj が目標点（横ズレ補正）
                entry.autoTargetX = bx + dirX * tProj;
                entry.autoTargetY = glideAlt;
                entry.autoTargetZ = bz + dirZ * tProj;

                double hDist = Math.sqrt(Math.pow(bx - wingman.posX, 2) + Math.pow(bz - wingman.posZ, 2));
                if (hDist < TOUCHDOWN_DIST) {
                    entry.autoState = AutonomousState.LANDING;
                    McHeliWingman.logger.info("[Auto] {} touchdown at B", shortId(wingman));
                }
                break;
            }
            case LANDING: {
                // 接地後スロットルゼロ → A 方向へ惰性停止
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

    /**
     * 滑走路中心線追従ターゲット。
     * 機体を軸に射影し、LOOKAHEAD 先の中心線上の点を autoTarget に設定する。
     * 横ズレが生じても連続的に補正される。
     *
     * @param ax/az  滑走路 A 端の XZ 座標（軸の起点）
     * @param targetY  目標高度（地上滑走時は ay、上昇時は CRUISE_ALT など）
     * @param dirX/dirZ  A→B 方向の単位ベクトル
     * @param lookahead  先読み距離 (blocks)
     */
    private static void centerlineTarget(WingmanEntry entry, Entity wingman,
                                          double ax, double targetY, double az,
                                          double dirX, double dirZ, double lookahead) {
        double proj = (wingman.posX - ax) * dirX + (wingman.posZ - az) * dirZ;
        double t = proj + lookahead;
        entry.autoTargetX = ax + dirX * t;
        entry.autoTargetY = targetY;
        entry.autoTargetZ = az + dirZ * t;
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
