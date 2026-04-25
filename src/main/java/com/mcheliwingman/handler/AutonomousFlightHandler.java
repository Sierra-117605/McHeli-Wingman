package com.mcheliwingman.handler;

import com.mcheliwingman.McHeliWingman;
import com.mcheliwingman.block.MarkerType;
import com.mcheliwingman.mission.AutonomousState;
import com.mcheliwingman.mission.MissionNode;
import com.mcheliwingman.mission.MissionOrder;
import com.mcheliwingman.mission.MissionType;
import com.mcheliwingman.mission.TaxiRoute;
import com.mcheliwingman.registry.MarkerRegistry;
import com.mcheliwingman.registry.TaxiRouteRegistry;
import com.mcheliwingman.util.McheliReflect;
import com.mcheliwingman.wingman.WingmanEntry;
import com.mcheliwingman.wingman.WingmanRegistry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.IMob;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
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

    // 燃料
    private static final double BINGO_FUEL_RATIO   = 0.20;  // 残燃料がこの比率以下でRTBトリガー

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

            MarkerRegistry.MarkerInfo rwyA = null, rwyB = null;

            if (entry.hasOrder()) {
                // MissionOrder 系: TaxiRoute / baseId から滑走路を解決
                MarkerRegistry.MarkerInfo[] rwy = getOrderRunway(ws, entry, entry.order);
                if (rwy != null) { rwyA = rwy[0]; rwyB = rwy[1]; }
            } else {
                // MissionNode 系
                MissionNode node = entry.currentNode();
                if (node == null || node.type != MissionNode.Type.TAKEOFF) continue;
                rwyA = MarkerRegistry.resolveMarker(ws, node.runwayId, MarkerType.RUNWAY_A);
                rwyB = MarkerRegistry.resolveMarker(ws, node.runwayId, MarkerType.RUNWAY_B);
            }
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
                forceYaw(wingman, runwayYaw);
            }
        }
    }

    private void tickMission(WorldServer ws, Entity wingman, WingmanEntry entry) {
        // MissionOrder 系（新）を優先
        if (entry.hasOrder()) {
            tickOrder(ws, wingman, entry);
            return;
        }

        // MissionNode 系（旧）
        MissionNode node = entry.currentNode();
        if (node == null) {
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
        MarkerRegistry.MarkerInfo rwyA = MarkerRegistry.resolveMarker(ws, node.runwayId, MarkerType.RUNWAY_A);
        MarkerRegistry.MarkerInfo rwyB = MarkerRegistry.resolveMarker(ws, node.runwayId, MarkerType.RUNWAY_B);
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
                // A 端へ直接向かう（低速タクシー）—— MissionNode 系のシンプルな動作
                double dist = Math.sqrt(Math.pow(ax - wingman.posX, 2) + Math.pow(az - wingman.posZ, 2));
                entry.autoTargetX = ax;
                entry.autoTargetY = ay;
                entry.autoTargetZ = az;
                setThrottle(wingman, 0.3);
                if (entry.missionNodeTimer++ % 40 == 0) {
                    McHeliWingman.logger.info("[Auto] {} TAXI_OUT dist={}", shortId(wingman), (int)dist);
                }
                if (dist < TAXI_DIST) {
                    entry.autoState = AutonomousState.ALIGN;
                    entry.missionNodeTimer = 0;
                }
                break;
            }
            case ALIGN:
            case TAKEOFF_ROLL:
            case CLIMB: {
                // 共通ヘルパーに委譲
                boolean done = tickAlignToClimb(ws, wingman, entry, rwyA, rwyB, CRUISE_ALT);
                if (done) {
                    McHeliWingman.logger.info("[Auto] {} reached cruise alt", shortId(wingman));
                    entry.advanceMission();
                }
                break;
            }
            default:
                entry.autoState = AutonomousState.TAXI_OUT;
                entry.missionNodeTimer = 0;
                McHeliWingman.logger.info("[Auto] {} TAKEOFF start, runway='{}' A=({},{},{}) B=({},{},{})",
                    shortId(wingman), node.runwayId,
                    (int)ax, (int)ay, (int)az, (int)bx, (int)rwyB.pos.getY(), (int)bz);
        }
    }

    // ─── LAND ────────────────────────────────────────────────────────────────

    private void tickLand(WorldServer ws, Entity wingman, WingmanEntry entry, MissionNode node) {
        MarkerRegistry.MarkerInfo rwyA = MarkerRegistry.resolveMarker(ws, node.runwayId, MarkerType.RUNWAY_A);
        MarkerRegistry.MarkerInfo rwyB = MarkerRegistry.resolveMarker(ws, node.runwayId, MarkerType.RUNWAY_B);
        if (rwyA == null || rwyB == null) {
            McHeliWingman.logger.warn("[Auto] {} LAND: runway '{}' A or B not found, skipping", shortId(wingman), node.runwayId);
            entry.advanceMission();
            return;
        }

        double ax = rwyA.pos.getX() + 0.5, ay = rwyA.pos.getY(), az = rwyA.pos.getZ() + 0.5;
        double bx = rwyB.pos.getX() + 0.5, by = rwyB.pos.getY(), bz = rwyB.pos.getZ() + 0.5;

        // 共通ヘルパーに委譲（MissionNode系でも MissionOrder系と同じ実装を使う）
        boolean landed = tickLandingCircuit(ws, wingman, entry, rwyA, rwyB);
        if (landed) {
            McHeliWingman.logger.info("[Auto] {} stopped", shortId(wingman));
            entry.advanceMission();
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

    // =========================================================================
    // MissionOrder 系 — 新ミッション実行エンジン
    // =========================================================================

    private void tickOrder(WorldServer ws, Entity wingman, WingmanEntry entry) {
        MissionOrder order = entry.order;

        // ─── RTBトリガー判定（ON_STATION中のみ）─────────────────────────────
        if (entry.autoState == AutonomousState.ON_STATION
                || entry.autoState == AutonomousState.STRIKE_PASS) {
            entry.orderTimer++;
            // 時間切れ
            if (order.timeLimitMinutes > 0 && entry.orderTimer >= order.timeLimitTicks()) {
                triggerRtb(wingman, entry, "time limit");
                return;
            }
            // ビンゴ燃料
            if (isBingo(wingman, order, ws)) {
                triggerRtb(wingman, entry, "bingo fuel");
                return;
            }
        }

        switch (entry.autoState) {

            // ─── 初期化 ────────────────────────────────────────────────────
            case NONE:
            case PARKED:
                initOrder(ws, wingman, entry, order);
                break;

            // ─── 地上タキシー（出発） ──────────────────────────────────────
            case TAXI_OUT:
                tickOrderTaxiOut(ws, wingman, entry, order);
                break;

            // ─── 離陸フェーズ（既存ヘルパー流用）─────────────────────────
            case ALIGN:
            case TAKEOFF_ROLL:
            case CLIMB: {
                MarkerRegistry.MarkerInfo[] rwy = getOrderRunway(ws, entry, order);
                if (rwy == null) {
                    // 滑走路情報なし → 直接巡航へ
                    entry.autoState = AutonomousState.TRANSIT_TO;
                    break;
                }
                boolean climbDone = tickAlignToClimb(ws, wingman, entry, rwy[0], rwy[1], order.cruiseAlt);
                if (climbDone) {
                    entry.autoState = AutonomousState.TRANSIT_TO;
                    McHeliWingman.logger.info("[Order] {} cruise altitude reached, TRANSIT_TO ({},{})",
                        shortId(wingman), (int)order.targetX, (int)order.targetZ);
                }
                break;
            }

            // ─── 任務エリアへ直行 ──────────────────────────────────────────
            case TRANSIT_TO:
                tickOrderTransit(ws, wingman, entry, order);
                break;

            // ─── オンステーション ──────────────────────────────────────────
            case ON_STATION:
                tickOrderOnStation(ws, wingman, entry, order);
                break;

            // ─── ストライクパス ────────────────────────────────────────────
            case STRIKE_PASS:
                tickOrderStrikePass(ws, wingman, entry, order);
                break;

            // ─── 帰投 ──────────────────────────────────────────────────────
            case RTB:
                tickOrderRtb(ws, wingman, entry, order);
                break;

            // ─── 着陸サーキット（既存ヘルパー流用）───────────────────────
            case DESCEND:
            case CIRCUIT_DOWNWIND:
            case CIRCUIT_BASE:
            case CIRCUIT_FINAL:
            case LANDING: {
                MarkerRegistry.MarkerInfo[] rwy = getOrderRunway(ws, entry, order);
                if (rwy == null) {
                    // 滑走路情報なし → 直接駐機完了
                    finishLanding(ws, wingman, entry, order);
                    break;
                }
                boolean landed = tickLandingCircuit(ws, wingman, entry, rwy[0], rwy[1]);
                if (landed) {
                    finishLanding(ws, wingman, entry, order);
                }
                break;
            }

            // ─── 地上タキシー（帰還） ──────────────────────────────────────
            case TAXI_IN:
                tickOrderTaxiIn(ws, wingman, entry, order);
                break;
        }
    }

    /** MissionOrder 発令時の初期化。地上駐機中ならTAXI_OUTへ、空中ならTRANSIT_TOへ。 */
    private void initOrder(WorldServer ws, Entity wingman, WingmanEntry entry, MissionOrder order) {
        // assignedParkingId が設定済みかつタキシールートが存在すれば地上から出発
        if (!entry.assignedParkingId.isEmpty()) {
            TaxiRoute route = TaxiRouteRegistry.findByParking(ws, entry.assignedParkingId);
            if (route != null) {
                entry.taxiWpQueue = new ArrayList<>(route.fullDeparture());
                entry.taxiWpIndex = 0;
                entry.autoState   = AutonomousState.TAXI_OUT;
                McHeliWingman.logger.info("[Order] {} TAXI_OUT start ({} wps)", shortId(wingman),
                    entry.taxiWpQueue.size());
                return;
            }
        }
        // 空中 or タキシールートなし → 直接巡航開始
        entry.autoState = AutonomousState.TRANSIT_TO;
        McHeliWingman.logger.info("[Order] {} airborne start, TRANSIT_TO ({},{})",
            shortId(wingman), (int)order.targetX, (int)order.targetZ);
    }

    /** タキシーWPキューに沿って地上移動（出発用）。 */
    private void tickOrderTaxiOut(WorldServer ws, Entity wingman, WingmanEntry entry, MissionOrder order) {
        if (entry.taxiWpIndex >= entry.taxiWpQueue.size()) {
            // キュー完了 → 滑走路端に到達 → アライン開始
            entry.autoState        = AutonomousState.ALIGN;
            entry.missionNodeTimer = 0;
            McHeliWingman.logger.info("[Order] {} reached runway, starting ALIGN", shortId(wingman));
            return;
        }
        String wpId = entry.taxiWpQueue.get(entry.taxiWpIndex);
        BlockPosXZ wp = resolveAnyMarker(ws, wpId);
        if (wp == null) { entry.taxiWpIndex++; return; }

        double tx = wp.x + 0.5, tz = wp.z + 0.5;
        entry.autoTargetX = tx;
        entry.autoTargetY = wingman.posY;
        entry.autoTargetZ = tz;
        setThrottle(wingman, 0.3);

        double dist = Math.sqrt(Math.pow(tx - wingman.posX, 2) + Math.pow(tz - wingman.posZ, 2));
        if (dist < TAXI_DIST) {
            McHeliWingman.logger.info("[Order] {} TAXI_OUT WP {}/{}: {}", shortId(wingman),
                entry.taxiWpIndex + 1, entry.taxiWpQueue.size(), wpId);
            entry.taxiWpIndex++;
        }
    }

    /** 任務エリアへの直行飛行。 */
    private void tickOrderTransit(WorldServer ws, Entity wingman, WingmanEntry entry, MissionOrder order) {
        setThrottle(wingman, 1.0);
        entry.autoTargetX = order.targetX;
        entry.autoTargetY = order.cruiseAlt;
        entry.autoTargetZ = order.targetZ;

        double dx = order.targetX - wingman.posX;
        double dz = order.targetZ - wingman.posZ;
        if (Math.sqrt(dx * dx + dz * dz) < ARRIVE_DIST) {
            entry.autoState  = AutonomousState.ON_STATION;
            entry.orderTimer = 0;
            McHeliWingman.logger.info("[Order] {} ON_STATION types={}", shortId(wingman),
                order.missionTypes);
        }
    }

    /** オンステーション実行。ミッション種別に応じた行動。 */
    private void tickOrderOnStation(WorldServer ws, Entity wingman, WingmanEntry entry, MissionOrder order) {
        // STRIKE は即パスへ移行
        if (order.hasType(MissionType.STRIKE) && entry.strikePassesRemaining > 0) {
            entry.autoState        = AutonomousState.STRIKE_PASS;
            entry.missionNodeTimer = 0;
            return;
        }
        // FERRY: 到着後 100tick 待機してRTB
        if (order.hasType(MissionType.FERRY)) {
            if (entry.orderTimer >= 100) triggerRtb(wingman, entry, "ferry complete");
            return;
        }
        // RECON: 旋回しながらMob数カウント
        if (order.hasType(MissionType.RECON) && !order.hasType(MissionType.CAP)
                && !order.hasType(MissionType.CAS) && !order.hasType(MissionType.ESCORT)) {
            tickRecon(ws, wingman, entry, order);
            return;
        }
        // CAP / CAS / ESCORT: 旋回 + 自動攻撃
        orbit(entry, wingman, order);
        entry.attackMode = WingmanEntry.ATK_AUTO;

        // RECON を追加していた場合はMob数もカウント
        if (order.hasType(MissionType.RECON)) {
            countMobs(wingman, order, entry);
        }
    }

    /** ストライクパス実行。 */
    private void tickOrderStrikePass(WorldServer ws, Entity wingman, WingmanEntry entry, MissionOrder order) {
        entry.missionNodeTimer++;
        entry.attackMode = WingmanEntry.ATK_AUTO;
        // ターゲットへ飛び込む（orbit で代替: 狭い半径=100 で攻撃パス）
        double angle = entry.orbitAngle + Math.PI; // 逆側から突入
        double r = Math.min(order.orbitRadius * 0.3, 100.0);
        entry.autoTargetX = order.targetX + Math.cos(angle) * r;
        entry.autoTargetY = order.cruiseAlt;
        entry.autoTargetZ = order.targetZ + Math.sin(angle) * r;
        entry.orbitAngle += 0.012;

        // 1パス = 300tick (15秒)
        if (entry.missionNodeTimer >= 300) {
            entry.strikePassesRemaining--;
            entry.missionNodeTimer = 0;
            McHeliWingman.logger.info("[Order] {} strike pass done, remaining={}", shortId(wingman),
                entry.strikePassesRemaining);
            if (entry.strikePassesRemaining <= 0) {
                triggerRtb(wingman, entry, "strike complete");
            } else {
                entry.autoState = AutonomousState.ON_STATION; // 次パスまで旋回
            }
        }
    }

    /** 偵察専用行動：旋回しながらMob数を集計し、任務時間後にRTBレポート。 */
    private void tickRecon(WorldServer ws, Entity wingman, WingmanEntry entry, MissionOrder order) {
        orbit(entry, wingman, order);
        entry.attackMode = WingmanEntry.ATK_NONE;
        countMobs(wingman, order, entry);
    }

    /** 帰投飛行。基地の滑走路B側を目指し、近づいたら降下サーキットへ。 */
    private void tickOrderRtb(WorldServer ws, Entity wingman, WingmanEntry entry, MissionOrder order) {
        setThrottle(wingman, 1.0);
        entry.attackMode = WingmanEntry.ATK_NONE;

        MarkerRegistry.MarkerInfo rwyB = MarkerRegistry.findByBase(ws, order.baseId, MarkerType.RUNWAY_B);
        if (rwyB == null) rwyB = MarkerRegistry.findById(ws, MarkerType.RUNWAY_B, order.baseId);
        if (rwyB == null) {
            // 滑走路情報なし → PARKED 扱いで終了
            entry.order     = null;
            entry.autoState = AutonomousState.PARKED;
            McHeliWingman.logger.warn("[Order] {} RTB: no runway B for base '{}', aborting",
                shortId(wingman), order.baseId);
            return;
        }

        double bx = rwyB.pos.getX() + 0.5;
        double bz = rwyB.pos.getZ() + 0.5;
        entry.autoTargetX = bx;
        entry.autoTargetY = order.cruiseAlt;
        entry.autoTargetZ = bz;

        double dx = bx - wingman.posX, dz = bz - wingman.posZ;
        // 滑走路から200ブロック以内になったら降下サーキット開始
        if (Math.sqrt(dx * dx + dz * dz) < 200.0) {
            entry.autoState        = AutonomousState.DESCEND;
            entry.missionNodeTimer = 0;
            McHeliWingman.logger.info("[Order] {} entering landing circuit for base '{}'",
                shortId(wingman), order.baseId);
        }
    }

    /** 着陸後の処理。タキシールートがあれば TAXI_IN、なければそのまま PARKED。 */
    private void finishLanding(WorldServer ws, Entity wingman, WingmanEntry entry, MissionOrder order) {
        entry.attackMode = WingmanEntry.ATK_NONE;

        // 空き駐機スポットを探す
        MarkerRegistry.MarkerInfo parking = MarkerRegistry.findAvailableParking(
            ws, order.baseId, ws.loadedEntityList);
        if (parking == null && !entry.assignedParkingId.isEmpty()) {
            // 以前の駐機スポットへ戻る
            parking = MarkerRegistry.findById(ws, MarkerType.PARKING, entry.assignedParkingId);
        }

        if (parking != null) {
            TaxiRoute route = TaxiRouteRegistry.findByParking(ws, parking.id);
            if (route != null) {
                entry.assignedParkingId = parking.id;
                entry.taxiWpQueue = new ArrayList<>(route.fullArrival());
                entry.taxiWpIndex = 0;
                entry.autoState   = AutonomousState.TAXI_IN;
                McHeliWingman.logger.info("[Order] {} TAXI_IN to parking '{}'",
                    shortId(wingman), parking.id);
                return;
            }
        }

        // タキシールートなし → 駐機完了
        entry.autoState = AutonomousState.PARKED;
        entry.order     = null;
        McHeliWingman.logger.info("[Order] {} mission complete (no taxi route)", shortId(wingman));
    }

    /** タキシーWPキューに沿って地上移動（帰還用）。 */
    private void tickOrderTaxiIn(WorldServer ws, Entity wingman, WingmanEntry entry, MissionOrder order) {
        if (entry.taxiWpIndex >= entry.taxiWpQueue.size()) {
            // 駐機スポットに到達 → 完了
            setThrottle(wingman, 0.0);
            entry.autoState = AutonomousState.PARKED;
            entry.order     = null;
            McHeliWingman.logger.info("[Order] {} PARKED at '{}'",
                shortId(wingman), entry.assignedParkingId);
            return;
        }
        String wpId = entry.taxiWpQueue.get(entry.taxiWpIndex);
        BlockPosXZ wp = resolveAnyMarker(ws, wpId);
        if (wp == null) { entry.taxiWpIndex++; return; }

        double tx = wp.x + 0.5, tz = wp.z + 0.5;
        entry.autoTargetX = tx;
        entry.autoTargetY = wingman.posY;
        entry.autoTargetZ = tz;
        setThrottle(wingman, 0.2);

        double dist = Math.sqrt(Math.pow(tx - wingman.posX, 2) + Math.pow(tz - wingman.posZ, 2));
        if (dist < PARK_DIST) {
            McHeliWingman.logger.info("[Order] {} TAXI_IN WP {}/{}: {}", shortId(wingman),
                entry.taxiWpIndex + 1, entry.taxiWpQueue.size(), wpId);
            entry.taxiWpIndex++;
        }
    }

    // ─── MissionOrder 共通ヘルパー ────────────────────────────────────────────

    /** 軌道旋回目標を設定する（CAP/CAS/ESCORT/RECON）。 */
    private void orbit(WingmanEntry entry, Entity wingman, MissionOrder order) {
        setThrottle(wingman, 1.0);
        entry.orbitAngle += 0.007;  // rad/tick → 約 900 tick で一周
        double r = order.orbitRadius;
        entry.autoTargetX = order.targetX + Math.cos(entry.orbitAngle) * r;
        entry.autoTargetY = order.cruiseAlt;
        entry.autoTargetZ = order.targetZ + Math.sin(entry.orbitAngle) * r;
    }

    /** 旋回エリア内の IMob 数をカウントして reconMobCount に累積する。 */
    private void countMobs(Entity wingman, MissionOrder order, WingmanEntry entry) {
        if (entry.orderTimer % 100 != 0) return; // 5秒ごとにカウント
        int count = 0;
        double r2 = order.orbitRadius * order.orbitRadius;
        for (Entity e : wingman.world.loadedEntityList) {
            if (!(e instanceof IMob) || e.isDead) continue;
            double dx = e.posX - order.targetX, dz = e.posZ - order.targetZ;
            if (dx * dx + dz * dz < r2) count++;
        }
        entry.reconMobCount = count;
    }

    /** RTBをトリガーして理由をログに残す。 */
    private void triggerRtb(Entity wingman, WingmanEntry entry, String reason) {
        entry.rtbReason    = reason;
        entry.autoState    = AutonomousState.RTB;
        entry.attackMode   = WingmanEntry.ATK_NONE;
        McHeliWingman.logger.info("[Order] {} RTB triggered: {}", shortId(wingman), reason);
        // RECON の場合は近くのプレイヤーに報告
        if (entry.order != null && entry.order.hasType(MissionType.RECON)) {
            for (net.minecraft.entity.player.EntityPlayer p : wingman.world.playerEntities) {
                if (p.getDistance(wingman) < 500) {
                    p.sendMessage(new net.minecraft.util.text.TextComponentString(
                        "§e[Recon] " + shortId(wingman) + ": §f" + entry.reconMobCount
                        + " mobs detected in target area (RTB: " + reason + ")"));
                }
            }
        }
    }

    /** ビンゴ燃料チェック。残燃料が BINGO_FUEL_RATIO 以下でtrue。 */
    private boolean isBingo(Entity wingman, MissionOrder order, WorldServer ws) {
        if (McheliReflect.hasInfiniteFuel(wingman)) return false;
        double fuel    = McheliReflect.getFuel(wingman);
        double maxFuel = McheliReflect.getMaxFuel(wingman);
        if (fuel < 0 || maxFuel <= 0) return false;
        return (fuel / maxFuel) < BINGO_FUEL_RATIO;
    }

    /** MissionOrder 用の RUNWAY_A/B を取得する。[0]=A, [1]=B。見つからなければ null。 */
    private MarkerRegistry.MarkerInfo[] getOrderRunway(WorldServer ws,
            WingmanEntry entry, MissionOrder order) {
        // TaxiRoute から runwayId を使う
        TaxiRoute route = null;
        if (!entry.assignedParkingId.isEmpty()) {
            route = TaxiRouteRegistry.findByParking(ws, entry.assignedParkingId);
        }
        MarkerRegistry.MarkerInfo rwyA, rwyB;
        if (route != null) {
            rwyA = MarkerRegistry.findById(ws, MarkerType.RUNWAY_A, route.runwayId);
        } else {
            rwyA = MarkerRegistry.findByBase(ws, order.baseId, MarkerType.RUNWAY_A);
        }
        rwyB = MarkerRegistry.findByBase(ws, order.baseId, MarkerType.RUNWAY_B);
        if (rwyA == null || rwyB == null) return null;
        return new MarkerRegistry.MarkerInfo[]{rwyA, rwyB};
    }

    /**
     * ALIGN → TAKEOFF_ROLL → CLIMB を実行する共有ヘルパー。
     * CLIMBが目標高度に到達したら true を返す。
     * entry.autoState は内部で書き換える（ALIGN→TAKEOFF_ROLL→CLIMB）。
     */
    private boolean tickAlignToClimb(WorldServer ws, Entity wingman, WingmanEntry entry,
            MarkerRegistry.MarkerInfo rwyA, MarkerRegistry.MarkerInfo rwyB, double targetAlt) {

        double ax = rwyA.pos.getX() + 0.5, ay = rwyA.pos.getY(), az = rwyA.pos.getZ() + 0.5;
        double bx = rwyB.pos.getX() + 0.5,                       bz = rwyB.pos.getZ() + 0.5;
        double rdx = bx - ax, rdz = bz - az;
        double rlen = Math.sqrt(rdx * rdx + rdz * rdz);
        if (rlen < 1) return true;
        double dirX = rdx / rlen, dirZ = rdz / rlen;
        float runwayYaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));

        switch (entry.autoState) {
            case ALIGN: {
                entry.missionNodeTimer++;
                setThrottle(wingman, 0.0);
                entry.autoTargetX = wingman.posX + dirX * 1000;
                entry.autoTargetY = ay;
                entry.autoTargetZ = wingman.posZ + dirZ * 1000;
                float yawDiff = runwayYaw - wingman.rotationYaw;
                while (yawDiff >  180f) yawDiff -= 360f;
                while (yawDiff < -180f) yawDiff += 360f;
                boolean aligned  = Math.abs(yawDiff) < ALIGN_TOLERANCE;
                double  alignSpd = Math.sqrt(wingman.motionX * wingman.motionX + wingman.motionZ * wingman.motionZ);
                boolean slow     = alignSpd < 0.4;
                boolean timedOut = entry.missionNodeTimer > 400;
                if ((aligned && slow) || timedOut) {
                    forceYaw(wingman, runwayYaw);
                    entry.missionNodeTimer = 0;
                    entry.autoState = AutonomousState.TAKEOFF_ROLL;
                }
                return false;
            }
            case TAKEOFF_ROLL: {
                double rollDist = (wingman.posX - ax) * dirX + (wingman.posZ - az) * dirZ;
                if (rollDist < MIN_ROLL_DIST) {
                    double t = Math.max(0.0, Math.min(1.0, rollDist / MIN_ROLL_DIST));
                    setThrottle(wingman, 0.3 + t * 0.7);
                    forceYaw(wingman, runwayYaw);
                    entry.autoTargetX = wingman.posX + dirX * 1000;
                    entry.autoTargetY = ay;
                    entry.autoTargetZ = wingman.posZ + dirZ * 1000;
                    entry.missionNodeTimer++;
                } else {
                    setThrottle(wingman, 1.0);
                    centerlineTarget(entry, wingman, ax, targetAlt, az, dirX, dirZ, LOOKAHEAD_DIST);
                }
                if (rollDist >= MIN_ROLL_DIST && wingman.posY > ay + 3) {
                    entry.autoState = AutonomousState.CLIMB;
                }
                return false;
            }
            case CLIMB: {
                setThrottle(wingman, 1.0);
                double climbTargetY = Math.min(targetAlt, wingman.posY + 15.0);
                centerlineTarget(entry, wingman, ax, climbTargetY, az, dirX, dirZ, LOOKAHEAD_DIST);
                if (wingman.posY >= targetAlt - 5) {
                    return true; // 上昇完了
                }
                return false;
            }
            default:
                // 予期しない状態 → ALIGN にリセット
                entry.autoState = AutonomousState.ALIGN;
                entry.missionNodeTimer = 0;
                return false;
        }
    }

    /**
     * DESCEND → CIRCUIT_* → LANDING を実行する共有ヘルパー。
     * LANDING で速度が LANDING_SPEED 以下になったら true を返す。
     */
    private boolean tickLandingCircuit(WorldServer ws, Entity wingman, WingmanEntry entry,
            MarkerRegistry.MarkerInfo rwyA, MarkerRegistry.MarkerInfo rwyB) {

        double ax = rwyA.pos.getX() + 0.5, ay = rwyA.pos.getY(), az = rwyA.pos.getZ() + 0.5;
        double bx = rwyB.pos.getX() + 0.5, by = rwyB.pos.getY(), bz = rwyB.pos.getZ() + 0.5;
        double rdx = bx - ax, rdz = bz - az;
        double rlen = Math.sqrt(rdx * rdx + rdz * rdz);
        if (rlen < 1) return true;
        double dirX = rdx / rlen, dirZ = rdz / rlen;
        double perpX = -dirZ, perpZ = dirX;
        double circuitY = by + 35;

        switch (entry.autoState) {
            case DESCEND: {
                double descThr = 0.65 + (circuitY - wingman.posY) * 0.02;
                setThrottle(wingman, Math.max(0.35, Math.min(0.9, descThr)));
                double epX = ax + perpX * CIRCUIT_OFFSET;
                double epZ = az + perpZ * CIRCUIT_OFFSET;
                entry.autoTargetX = epX; entry.autoTargetY = circuitY; entry.autoTargetZ = epZ;
                if (Math.sqrt(Math.pow(epX - wingman.posX, 2) + Math.pow(epZ - wingman.posZ, 2)) < 30) {
                    entry.autoState = AutonomousState.CIRCUIT_DOWNWIND;
                }
                return false;
            }
            case CIRCUIT_DOWNWIND: {
                double dwThr = 0.65 + (circuitY - wingman.posY) * 0.02;
                setThrottle(wingman, Math.max(0.4, Math.min(0.9, dwThr)));
                double dwX = bx + dirX * CIRCUIT_FINAL_DIST + perpX * CIRCUIT_OFFSET;
                double dwZ = bz + dirZ * CIRCUIT_FINAL_DIST + perpZ * CIRCUIT_OFFSET;
                entry.autoTargetX = dwX; entry.autoTargetY = circuitY; entry.autoTargetZ = dwZ;
                entry.missionNodeTimer++;
                if (Math.sqrt(Math.pow(dwX - wingman.posX, 2) + Math.pow(dwZ - wingman.posZ, 2)) < 30) {
                    entry.missionNodeTimer = 0;
                    entry.autoState = AutonomousState.CIRCUIT_BASE;
                }
                return false;
            }
            case CIRCUIT_BASE: {
                double bsThr = 0.65 + (circuitY - wingman.posY) * 0.02;
                setThrottle(wingman, Math.max(0.4, Math.min(0.9, bsThr)));
                double fX = bx + dirX * CIRCUIT_FINAL_DIST;
                double fZ = bz + dirZ * CIRCUIT_FINAL_DIST;
                entry.autoTargetX = fX; entry.autoTargetY = circuitY; entry.autoTargetZ = fZ;
                entry.missionNodeTimer++;
                if (Math.sqrt(Math.pow(fX - wingman.posX, 2) + Math.pow(fZ - wingman.posZ, 2)) < 40) {
                    entry.missionNodeTimer = 0;
                    entry.autoState = AutonomousState.CIRCUIT_FINAL;
                }
                return false;
            }
            case CIRCUIT_FINAL: {
                double projFromB = (wingman.posX - bx) * dirX + (wingman.posZ - bz) * dirZ;
                double tProj     = Math.max(0, projFromB - LOOKAHEAD_DIST);
                double touchdownAlt = by + 1;
                double glideAlt = touchdownAlt + Math.max(0, projFromB)
                    * ((circuitY - touchdownAlt) / CIRCUIT_FINAL_DIST);
                glideAlt = Math.max(touchdownAlt, Math.min(circuitY, glideAlt));
                boolean onGround = wingman.posY <= by + 1;
                double gsError   = wingman.posY - glideAlt;
                if (onGround) {
                    setThrottle(wingman, 0.0);
                    entry.autoTargetX = bx + dirX * tProj;
                    entry.autoTargetY = by - 10;
                    entry.autoTargetZ = bz + dirZ * tProj;
                } else {
                    double thr = 0.55 - gsError * 0.02;
                    if (projFromB < 80) thr = Math.min(thr, 0.1 + (projFromB / 80.0) * 0.4);
                    setThrottle(wingman, Math.max(0.1, Math.min(0.75, thr)));
                    entry.autoTargetX = bx + dirX * tProj;
                    entry.autoTargetY = glideAlt;
                    entry.autoTargetZ = bz + dirZ * tProj;
                }
                entry.missionNodeTimer++;
                double hDist = Math.sqrt(Math.pow(bx - wingman.posX, 2) + Math.pow(bz - wingman.posZ, 2));
                if ((hDist < TOUCHDOWN_DIST && wingman.posY <= by + 5)
                        || projFromB <= 0 || (onGround && hDist < TOUCHDOWN_DIST * 3)) {
                    entry.autoState = AutonomousState.LANDING;
                }
                return false;
            }
            case LANDING: {
                setThrottle(wingman, 0.0);
                entry.autoTargetX = wingman.posX + (-dirX) * 40;
                entry.autoTargetY = by - 10;
                entry.autoTargetZ = wingman.posZ + (-dirZ) * 40;
                double spd = Math.sqrt(wingman.motionX * wingman.motionX + wingman.motionZ * wingman.motionZ);
                if (spd < LANDING_SPEED) {
                    McHeliWingman.logger.info("[Order] {} landed", shortId(wingman));
                    return true;
                }
                return false;
            }
            default:
                entry.autoState = AutonomousState.DESCEND;
                return false;
        }
    }

    /** マーカーIDからブロック座標を返す（型問わず最初にIDが一致したもの）。 */
    private BlockPosXZ resolveAnyMarker(WorldServer ws, String markerId) {
        for (MarkerRegistry.MarkerInfo m : MarkerRegistry.snapshot(ws)) {
            if (markerId.equals(m.id)) return new BlockPosXZ(m.pos.getX(), m.pos.getZ());
        }
        return null;
    }

    /** XZ のみの軽量な座標ペア。 */
    private static class BlockPosXZ {
        final int x, z;
        BlockPosXZ(int x, int z) { this.x = x; this.z = z; }
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
        MarkerRegistry.MarkerInfo parking = MarkerRegistry.resolveMarker(ws, node.parkingId, MarkerType.PARKING);
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
