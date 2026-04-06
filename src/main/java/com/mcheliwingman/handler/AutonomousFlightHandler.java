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
    private static final double ARRIVE_DIST        = 30.0;  // FLY_TO 到達判定（XZ水平距離）
    private static final double TAXI_DIST          = 30.0;  // 地上滑走 到達判定（高速固定翼でも検出）
    private static final double PARK_DIST          = 8.0;   // 駐機到達判定（小さくして正確に停止）
    private static final double CRUISE_ALT         = 80.0;  // デフォルト巡航高度（Y座標）
    private static final double TAKEOFF_SPEED      = 1.5;   // 離陸判定速度 (blocks/tick)
    private static final double LANDING_SPEED      = 0.1;   // 着陸完了判定速度

    // 離陸整列・中心線追従
    private static final double ALIGN_TOLERANCE    = 5.0;   // 機首整列許容角度 (°) — 小さくしてロール中のヨードリフトを防ぐ
    private static final double LOOKAHEAD_DIST     = 40.0;  // 滑走路中心線先読み距離 (blocks)
    private static final double MIN_ROLL_DIST      = 130.0; // ローテーション前の最低地上滑走距離 (blocks)

    // 着陸サーキット
    private static final double CIRCUIT_OFFSET     = 80.0;  // 滑走路中心からの横距離 (blocks)
    private static final double CIRCUIT_FINAL_DIST = 250.0; // ファイナル進入開始距離 — 大きすぎるとチャンク外逸脱
    private static final double TOUCHDOWN_DIST     = 20.0;  // 接地判定距離

    // リフレクション
    private Method setRotYawMethod;
    private Method setRotPitchMethod;
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

    /**
     * Phase.END: McHeli 物理更新後にも forceYaw を再適用。
     * McHeli がエンティティtick内でヨートルクを加算する場合でも、
     * クライアントへのパケット送信前にヨーを正確な値に戻す。
     */
    @SubscribeEvent
    public void onWorldTickEnd(TickEvent.WorldTickEvent event) {
        if (event.world.isRemote || event.phase != TickEvent.Phase.END) return;
        WorldServer ws = (WorldServer) event.world;

        for (Map.Entry<UUID, WingmanEntry> e : WingmanRegistry.snapshot().entrySet()) {
            WingmanEntry entry = e.getValue();
            if (!entry.isAutonomous()) continue;
            if (entry.autoState != AutonomousState.TAKEOFF_ROLL) continue;

            Entity wingman = ws.getEntityFromUuid(e.getKey());
            if (wingman == null || wingman.isDead) continue;

            MissionNode node = entry.currentNode();
            if (node == null || node.type != MissionNode.Type.TAKEOFF) continue;

            MarkerRegistry.MarkerInfo rwyA = MarkerRegistry.findById(ws, MarkerType.RUNWAY_A, node.runwayId);
            MarkerRegistry.MarkerInfo rwyB = MarkerRegistry.findById(ws, MarkerType.RUNWAY_B, node.runwayId);
            if (rwyA == null || rwyB == null) continue;

            double ax = rwyA.pos.getX() + 0.5, az = rwyA.pos.getZ() + 0.5;
            double bx = rwyB.pos.getX() + 0.5, bz = rwyB.pos.getZ() + 0.5;
            double rdx = bx - ax, rdz = bz - az;
            double rlen = Math.sqrt(rdx * rdx + rdz * rdz);
            if (rlen < 1) continue;
            double dirX = rdx / rlen, dirZ = rdz / rlen;
            float runwayYaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));

            double rollDist = (wingman.posX - ax) * dirX + (wingman.posZ - az) * dirZ;
            if (rollDist < MIN_ROLL_DIST) {
                // 地上加速フェーズのみ: McHeli 物理後にも再矯正
                forceYaw(wingman, runwayYaw);
            }
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
        setThrottle(wingman, 1.0);  // 巡航は常に全スロットル
        // 目標高度は指定値 or 巡航高度のどちらか高い方（地面レベルの waypoint で急降下しない）
        double targetY = Math.max(node.y, CRUISE_ALT);
        entry.autoTargetX = node.x;
        entry.autoTargetY = targetY;
        entry.autoTargetZ = node.z;

        // 到達判定は XZ 水平距離のみ（高度差で永遠に到達しない問題を防ぐ）
        double dx = node.x - wingman.posX;
        double dz = node.z - wingman.posZ;
        double hDist = Math.sqrt(dx * dx + dz * dz);
        if (hDist < ARRIVE_DIST) {
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
                // A 端へ直接向かう（低速タクシー）
                double dist = Math.sqrt(Math.pow(ax - wingman.posX, 2) + Math.pow(az - wingman.posZ, 2));
                entry.autoTargetX = ax;
                entry.autoTargetY = ay;
                entry.autoTargetZ = az;
                setThrottle(wingman, 0.3);  // 低速タクシー（WingmanTickHandler の全スロットルを上書き）
                if (entry.missionNodeTimer++ % 40 == 0) {
                    McHeliWingman.logger.info("[Auto] {} TAXI_OUT dist={} target=({},{},{})", shortId(wingman), (int)dist, (int)ax, (int)ay, (int)az);
                }
                if (dist < TAXI_DIST) {
                    entry.autoState = AutonomousState.ALIGN;
                    entry.missionNodeTimer = 0;
                    McHeliWingman.logger.info("[Auto] {} at runway A (dist={}), aligning yaw={} runway={}", shortId(wingman), (int)dist, (int)wingman.rotationYaw, (int)runwayYaw);
                }
                break;
            }
            case ALIGN: {
                // スロットルゼロで機首を滑走路方向に自然に揃える（WingmanTickHandler のレート制限旋回を利用）
                // forceYaw は使わない: 即時スナップで反転途中に推力ベクトルが変わり「前進」が起きるため
                entry.missionNodeTimer++;
                setThrottle(wingman, 0.0);
                // target = 現在位置から滑走路方向 1000 先 → WingmanTickHandler が 1.5°/tick でヨー制御
                entry.autoTargetX = wingman.posX + dirX * 1000;
                entry.autoTargetY = ay;
                entry.autoTargetZ = wingman.posZ + dirZ * 1000;
                float yawDiff = runwayYaw - wingman.rotationYaw;
                while (yawDiff >  180f) yawDiff -= 360f;
                while (yawDiff < -180f) yawDiff += 360f;
                boolean aligned  = Math.abs(yawDiff) < ALIGN_TOLERANCE;
                double alignSpd  = Math.sqrt(wingman.motionX * wingman.motionX + wingman.motionZ * wingman.motionZ);
                boolean slow     = alignSpd < 0.4;  // タクシー慣性が抜けるのを待つ
                boolean timedOut = entry.missionNodeTimer > 400;
                if ((aligned && slow) || timedOut) {
                    // TAKEOFF_ROLL 開始直前だけ forceYaw で正確にアライン
                    forceYaw(wingman, runwayYaw);
                    entry.missionNodeTimer = 0;
                    entry.autoState = AutonomousState.TAKEOFF_ROLL;
                    McHeliWingman.logger.info("[Auto] {} aligned{} spd={}, starting roll", shortId(wingman),
                                              timedOut && !aligned ? " (timeout)" : "",
                                              String.format("%.2f", alignSpd));
                }
                break;
            }
            case TAKEOFF_ROLL: {
                // A端からの滑走距離 (A→B方向への投影)
                double rollDist = (wingman.posX - ax) * dirX + (wingman.posZ - az) * dirZ;
                if (rollDist < MIN_ROLL_DIST) {
                    // 地上加速フェーズ: スロットルを 0.3 → 1.0 に線形ランプ
                    double t = Math.max(0.0, Math.min(1.0, rollDist / MIN_ROLL_DIST));
                    setThrottle(wingman, 0.3 + t * 0.7);
                    forceYaw(wingman, runwayYaw);
                    entry.autoTargetX = wingman.posX + dirX * 1000;
                    entry.autoTargetY = ay;
                    entry.autoTargetZ = wingman.posZ + dirZ * 1000;
                    // 10tickごとに実際のヨーをログ出力
                    if (entry.missionNodeTimer++ % 10 == 0) {
                        McHeliWingman.logger.info("[Auto] {} ROLL dist={} yaw={} target={} motX={} motZ={}",
                            shortId(wingman), String.format("%.1f", rollDist),
                            String.format("%.1f", wingman.rotationYaw),
                            String.format("%.1f", runwayYaw),
                            String.format("%.3f", wingman.motionX),
                            String.format("%.3f", wingman.motionZ));
                    }
                } else {
                    // ローテーション: 全スロットル + targetY = CRUISE_ALT で引き起こし
                    setThrottle(wingman, 1.0);
                    centerlineTarget(entry, wingman, ax, CRUISE_ALT, az, dirX, dirZ, LOOKAHEAD_DIST);
                }
                // MIN_ROLL_DIST 到達後のみ離陸判定（早期離陸を防ぐ）
                if (rollDist >= MIN_ROLL_DIST && wingman.posY > ay + 3) {
                    entry.autoState = AutonomousState.CLIMB;
                    double spd = Math.sqrt(wingman.motionX * wingman.motionX + wingman.motionZ * wingman.motionZ);
                    McHeliWingman.logger.info("[Auto] {} airborne posY={} spd={} rollDist={}", shortId(wingman),
                        String.format("%.1f", wingman.posY), String.format("%.2f", spd), String.format("%.1f", rollDist));
                }
                break;
            }
            case CLIMB: {
                // 全スロットル + 現在高度 + 15 を目標にして緩やかなピッチ上昇を維持
                // atan(15/40) ≈ 20° のピッチを保ちながら CRUISE_ALT まで徐々に上昇
                setThrottle(wingman, 1.0);
                double climbTargetY = Math.min(CRUISE_ALT, wingman.posY + 15.0);
                centerlineTarget(entry, wingman, ax, climbTargetY, az, dirX, dirZ, LOOKAHEAD_DIST);
                if (wingman.posY >= CRUISE_ALT - 5) {
                    McHeliWingman.logger.info("[Auto] {} reached cruise alt", shortId(wingman));
                    entry.advanceMission();
                }
                break;
            }
            default:
                entry.autoState = AutonomousState.TAXI_OUT;
                entry.missionNodeTimer = 0;
                McHeliWingman.logger.info("[Auto] {} TAKEOFF start, runway='{}' A=({},{},{}) B=({},{},{})", shortId(wingman),
                    node.runwayId, (int)ax, (int)ay, (int)az, (int)bx, (int)rwyB.pos.getY(), (int)bz);
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
        // サーキット高度: by+35 → FINAL_DIST=250 で進入角≈8°（11°から下げて維持可能に）
        double circuitY = by + 35;

        switch (entry.autoState) {
            case DESCEND: {
                // サーキット入口: A の左側 CIRCUIT_OFFSET ブロック・高度 circuitY
                // P制御: circuitY との差に応じてスロットルを調整
                double descThr = 0.65 + (circuitY - wingman.posY) * 0.02;
                setThrottle(wingman, Math.max(0.35, Math.min(0.9, descThr)));
                double epX = ax + perpX * CIRCUIT_OFFSET;
                double epZ = az + perpZ * CIRCUIT_OFFSET;
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
                // ダウンウィンド: A側オフセット → B の先 CIRCUIT_FINAL_DIST まで延長
                // BASEが真横(南北)フライトになるよう、B を超えてファイナル起点の真北まで進む
                // P制御でcircuitY維持（throttle=0.9固定だと上昇しすぎる）
                double dwThr = 0.65 + (circuitY - wingman.posY) * 0.02;
                setThrottle(wingman, Math.max(0.4, Math.min(0.9, dwThr)));
                double dwX = bx + dirX * CIRCUIT_FINAL_DIST + perpX * CIRCUIT_OFFSET;
                double dwZ = bz + dirZ * CIRCUIT_FINAL_DIST + perpZ * CIRCUIT_OFFSET;
                entry.autoTargetX = dwX;
                entry.autoTargetY = circuitY;
                entry.autoTargetZ = dwZ;
                double dist = Math.sqrt(Math.pow(dwX - wingman.posX, 2) + Math.pow(dwZ - wingman.posZ, 2));
                if (entry.missionNodeTimer++ % 40 == 0) {
                    McHeliWingman.logger.info("[Auto] {} DOWNWIND posY={} circuitY={} dist={}",
                        shortId(wingman), String.format("%.1f", wingman.posY),
                        String.format("%.1f", circuitY), (int)dist);
                }
                if (dist < 30) {
                    entry.missionNodeTimer = 0;
                    entry.autoState = AutonomousState.CIRCUIT_BASE;
                    McHeliWingman.logger.info("[Auto] {} turning base posY={}", shortId(wingman),
                        String.format("%.1f", wingman.posY));
                }
                break;
            }
            case CIRCUIT_BASE: {
                // ベースレグ: ファイナル起点(B の滑走路延長上 CIRCUIT_FINAL_DIST 先)へ向かう
                // DOWNWIND がその真北まで延長されているので、ここは純粋に南行き(滑走路に直交)
                // P制御でcircuitY維持
                double bsThr = 0.65 + (circuitY - wingman.posY) * 0.02;
                setThrottle(wingman, Math.max(0.4, Math.min(0.9, bsThr)));
                double fX = bx + dirX * CIRCUIT_FINAL_DIST;
                double fZ = bz + dirZ * CIRCUIT_FINAL_DIST;
                entry.autoTargetX = fX;
                entry.autoTargetY = circuitY;
                entry.autoTargetZ = fZ;
                double dist = Math.sqrt(Math.pow(fX - wingman.posX, 2) + Math.pow(fZ - wingman.posZ, 2));
                if (entry.missionNodeTimer++ % 40 == 0) {
                    McHeliWingman.logger.info("[Auto] {} BASE posY={} circuitY={} dist={}",
                        shortId(wingman), String.format("%.1f", wingman.posY),
                        String.format("%.1f", circuitY), (int)dist);
                }
                if (dist < 40) {
                    entry.missionNodeTimer = 0;
                    entry.autoState = AutonomousState.CIRCUIT_FINAL;
                    McHeliWingman.logger.info("[Auto] {} on final posY={}", shortId(wingman),
                        String.format("%.1f", wingman.posY));
                }
                break;
            }
            case CIRCUIT_FINAL: {
                // ファイナル: 中心線追従 + グライドスロープで B に向けて降下
                // B を基準に、B の先方 (dir 方向) に機体がいる距離を求める
                double projFromB = (wingman.posX - bx) * dirX + (wingman.posZ - bz) * dirZ;
                double tProj = Math.max(0, projFromB - LOOKAHEAD_DIST);

                // グライドスロープ高度
                double touchdownAlt = by + 1;
                double glideAlt = touchdownAlt + Math.max(0, projFromB) * ((circuitY - touchdownAlt) / CIRCUIT_FINAL_DIST);
                glideAlt = Math.max(touchdownAlt, Math.min(circuitY, glideAlt));

                // 地面接触検出: グライドスロープ追従をやめて着陸モードへ
                boolean onGround = wingman.posY <= by + 1;
                double gsError = wingman.posY - glideAlt;  // 正 = グライドスロープより高い

                if (onGround) {
                    // 地面に接触: スロットルゼロ + nose-down ターゲットでピッチを下げる
                    setThrottle(wingman, 0.0);
                    entry.autoTargetX = bx + dirX * tProj;
                    entry.autoTargetY = by - 10;  // 地面より下の仮想目標 → nose-down 強制
                    entry.autoTargetZ = bz + dirZ * tProj;
                } else {
                    // P制御: グライドスロープ誤差に比例してスロットルを調整
                    // gsError=0 → thr=0.55, gsError=+10 → 0.35, gsError=-10 → 0.75
                    double thr = 0.55 - gsError * 0.02;
                    // フレア: B に近い場合はさらに絞り込む
                    if (projFromB < 80) thr = Math.min(thr, 0.1 + (projFromB / 80.0) * 0.4);
                    setThrottle(wingman, Math.max(0.1, Math.min(0.75, thr)));
                    entry.autoTargetX = bx + dirX * tProj;
                    entry.autoTargetY = glideAlt;
                    entry.autoTargetZ = bz + dirZ * tProj;
                }

                if (entry.missionNodeTimer++ % 20 == 0) {
                    McHeliWingman.logger.info("[Auto] {} FINAL proj={} posY={} glide={} gsErr={} gnd={}",
                        shortId(wingman), String.format("%.0f", projFromB),
                        String.format("%.1f", wingman.posY), String.format("%.1f", glideAlt),
                        String.format("%.1f", gsError), onGround);
                }

                double hDist = Math.sqrt(Math.pow(bx - wingman.posX, 2) + Math.pow(bz - wingman.posZ, 2));
                // 接地判定: B に近い かつ 地面付近 or B を通過した or 地面接触後に B が近い
                if ((hDist < TOUCHDOWN_DIST && wingman.posY <= by + 5) || projFromB <= 0
                        || (onGround && hDist < TOUCHDOWN_DIST * 3)) {
                    entry.autoState = AutonomousState.LANDING;
                    McHeliWingman.logger.info("[Auto] {} touchdown posY={} hDistB={}", shortId(wingman),
                        String.format("%.1f", wingman.posY), String.format("%.1f", hDist));
                }
                break;
            }
            case LANDING: {
                // 接地後: スロットルゼロ、強めの機首下げで後輪めり込みを防ぐ
                setThrottle(wingman, 0.0);
                // B→A方向40ブロック先、高度は by-10 (地面より下の仮想目標)
                // → dy=-12程度 → targetPitch≈+17° (nose-down) で強制的に機首を下げる
                entry.autoTargetX = wingman.posX + (-dirX) * 40;
                entry.autoTargetY = by - 10;
                entry.autoTargetZ = wingman.posZ + (-dirZ) * 40;

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
        if (dist < PARK_DIST) {
            setThrottle(wingman, 0.0);
            entry.autoState = AutonomousState.PARKED;
            McHeliWingman.logger.info("[Auto] {} PARKED at {}", shortId(wingman), node.parkingId);
            entry.advanceMission();
        }
    }

    // ─── Throttle helper ─────────────────────────────────────────────────────

    // ─── Yaw force helper ────────────────────────────────────────────────────

    /** ヨーを直接セットして WingmanTickHandler のレートリミットを bypass する。 */
    private void forceYaw(Entity aircraft, float yaw) {
        if (!rotResolved) {
            rotResolved = true;
            setRotYawMethod = findMethod(aircraft.getClass(), "setRotYaw", float.class);
        }
        try { if (setRotYawMethod != null) setRotYawMethod.invoke(aircraft, yaw); }
        catch (Exception ignored) {}
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
