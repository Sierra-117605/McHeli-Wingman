package com.mcheliwingman.handler;

import com.mcheliwingman.McHeliWingman;
import com.mcheliwingman.config.WingmanConfig;
import com.mcheliwingman.wingman.WingmanEntry;
import com.mcheliwingman.wingman.WingmanRegistry;
import com.mcheliwingman.wingman.WingmanState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.IMob;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

/**
 * Per-tick wingman logic.
 *
 * 設計方針:
 *   機体本来の飛行物理（McHeli）を尊重し、setRotYaw/setRotPitch をレート制限付きで操作して誘導する。
 *   velocity/motion は直接書き換えない。
 *
 * Phase.START: エンジン・姿勢角セット（McHeli更新前）
 * Phase.END  : 攻撃発射・ギア同期
 */
public class WingmanTickHandler {

    // 到達判定距離
    private static final double ARRIVAL_THRESHOLD = 5.0;
    // 停止ホールド判定距離（親機停止時にこの距離以内なら停止待機）
    private static final double HOLD_THRESHOLD = 25.0;
    // 親機停止判定速度（ブロック/tick）
    private static final double LEADER_STOP_SPEED = 0.4;
    // 自動攻撃索敵レンジ
    private static final double AUTO_ATTACK_RANGE = 200.0;
    // 発射後の逃避時間（tick）— この間は現在ヨーを維持してエスケープ
    private static final int POST_FIRE_ESCAPE_TICKS = 40;

    // 武器種別スタンドオフ距離（接敵目標水平距離）— 近づき過ぎない
    private static double standoffForWeapon(String type) {
        if (type == null) return 120.0;
        switch (type.toLowerCase()) {
            case "gun": case "machinegun": case "machinegun1": case "machinegun2":
            case "cannon":                return 80.0;
            case "cas":                   return 100.0;
            case "rocket": case "mkrocket": return 150.0;
            case "missile": case "aamissile": case "atmissile": case "tvmissile":
                                          return 250.0;
            case "asmissile":             return 300.0;
            case "bomb":                  return 200.0;   // 爆弾は特に離す
            case "torpedo":               return 40.0;
            default:                      return 120.0;
        }
    }

    // 武器種別高度オフセット（ターゲットY + この値 が目標高度）
    private static double altOffsetForWeapon(String type) {
        if (type == null) return 50.0;
        switch (type.toLowerCase()) {
            case "gun": case "machinegun": case "machinegun1": case "machinegun2":
            case "cannon":   return 40.0;
            case "cas":      return 50.0;
            case "rocket": case "mkrocket": return 60.0;
            case "missile": case "aamissile": case "atmissile": case "tvmissile":
                             return 80.0;
            case "asmissile": return 200.0;
            case "bomb":     return 200.0;   // 十分な高度から投下
            case "torpedo":  return 5.0;
            default:         return 50.0;
        }
    }

    // 武器種別発射有効レンジ（この距離以内で発射）
    private static double fireRangeForWeapon(String type) {
        if (type == null) return 150.0;
        switch (type.toLowerCase()) {
            case "gun": case "machinegun": case "machinegun1": case "machinegun2":
            case "cannon":   return 120.0;
            case "cas":      return 140.0;
            case "rocket": case "mkrocket": return 200.0;
            case "missile": case "aamissile": case "atmissile": case "tvmissile":
                             return 300.0;
            case "asmissile": return 380.0;
            case "bomb":     return 350.0;
            case "torpedo":  return 80.0;
            default:         return 150.0;
        }
    }

    // 武器種別クールダウン(tick)
    private static int fireCooldownForType(String type) {
        if (type == null) return 10;
        switch (type.toLowerCase()) {
            case "gun": case "machinegun": case "machinegun1": case "machinegun2": return 2;
            case "cas":      return 3;
            case "cannon":   return 5;
            case "rocket": case "mkrocket": return 10;
            case "aamissile": case "atmissile": case "missile": return 20;
            case "asmissile": return 60;   // 対地ミサイルは1発打ったら間隔を広く
            case "tvmissile": return 30;
            case "bomb":     return 80;    // 爆弾は爆発回避のため間隔を大きく
            case "torpedo":  return 30;
            default:         return 10;
        }
    }

    // 旋回レート制限 (°/tick) — 固定翼は緩やかに、ヘリは機動性高め
    private static final float MAX_YAW_RATE_PLANE = 1.5f;
    private static final float MAX_YAW_RATE_HELI  = 4.0f;
    // ピッチレート制限 (°/tick)
    private static final float MAX_PITCH_RATE = 3.0f;
    // 最大ピッチ角
    private static final float MAX_PITCH_UP   = 25.0f;
    private static final float MAX_PITCH_DOWN = -20.0f;

    // ─── Reflection caches ───────────────────────────────────────────────────

    // Throttle
    private Method   setCurrentThrottleMethod;
    private Method   getCurrentThrottleMethod;
    private Field    throttleDataParamField;
    private boolean  throttleResolved = false;

    // 直接回転制御メソッド（UAV無ライダーでも機能）
    private Method   setRotYawMethod, setRotPitchMethod;
    private boolean  rotationResolved = false;

    // 攻撃エイム用フィールド
    private Field    lastRiderYawField, lastRiderPitchField;
    private boolean  controlResolved = false;

    // getRotYaw/getRotPitch（現在の機体角度取得用）
    private Method   getRotYawMethod, getRotPitchMethod;

    // Gear
    private Method   isLandingGearFolded, foldLandingGear, unfoldLandingGear, canFoldLandingGear;

    // VTOL制御（固定翼機）
    private Method   getVtolModeMethod, swithVtolModeMethod;
    private boolean  vtolResolved = false;

    // Weapons
    private Field    weaponsField;
    private Class<?> weaponParamClass;
    private Constructor<?> weaponParamCtor;
    private Field    wpUser, wpPosX, wpPosY, wpPosZ, wpRotYaw, wpRotPitch, wpEntity;
    private Method   weaponSetUse, weaponSetCanUse, weaponSetGetCurrentWeapon, weaponBaseShot;
    private boolean  weaponDebugLogged = false;
    private Field    lastRiddenByEntityField;

    // ファイアレートの自前管理
    private final java.util.Map<UUID, Integer> fireCooldowns = new java.util.HashMap<>();
    // 発射後エスケープカウンタ（残りtick > 0 中は現在ヨーを維持してエスケープ飛行）
    private final java.util.Map<UUID, Integer> postFireEscape = new java.util.HashMap<>();

    // ─── Phase.START ─────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onWorldTickStart(TickEvent.WorldTickEvent event) {
        if (event.world.isRemote || event.phase != TickEvent.Phase.START) return;
        WorldServer ws = (WorldServer) event.world;

        for (Map.Entry<UUID, WingmanEntry> e : WingmanRegistry.snapshot().entrySet()) {
            Entity wingman = ws.getEntityFromUuid(e.getKey());
            if (wingman == null || wingman.isDead) continue;
            WingmanEntry entry = e.getValue();

            // 固定翼: VTOLモードを強制OFF
            if (!isHelicopter(wingman)) {
                forceVtolOff(wingman);
            }

            if (entry.attackMode != WingmanEntry.ATK_NONE) {
                // 攻撃中: 全力スロットル
                maintainEngine(wingman, 1.0);
                steerToTarget(ws, wingman, entry);
            } else {
                // フォーメーション追従: 指定スロットまでの距離に応じてスロットル調整
                double[] fp = formationPos(entry.leader, entry.formationSlot);
                double dx = fp[0] - wingman.posX;
                double dy = fp[1] - wingman.posY;
                double dz = fp[2] - wingman.posZ;
                double distToSlot = Math.sqrt(dx * dx + dy * dy + dz * dz);

                if (isLeaderStopped(entry.leader) && distToSlot < HOLD_THRESHOLD) {
                    holdStop(wingman, entry.leader);
                } else {
                    maintainEngineAdaptive(wingman, entry.leader, distToSlot);
                    steerToTarget(ws, wingman, entry);
                }
            }
        }
    }

    /**
     * McHeliの飛行物理を尊重した操縦制御。
     *
     * McHeli内部: onUpdate_Server は getRotYaw()/getRotPitch() を使って速度ベクトルを更新する。
     * ライダーなしUAVでは setAngles() が呼ばれないため lastRiderYaw/moveLeft 等は効果なし。
     * → setRotYaw / setRotPitch を直接レート制限付きで書き換えることで機体を誘導する。
     */
    private void steerToTarget(WorldServer ws, Entity wingman, WingmanEntry entry) {
        resolveRotationMethods(wingman);
        resolveControlFields(wingman);  // lastRiderYaw は攻撃エイム用に残す

        // 発射後エスケープ中は現在ヨーを維持（旋回しない）
        UUID wid = wingman.getUniqueID();
        int escTicks = postFireEscape.getOrDefault(wid, 0);
        if (escTicks > 0) {
            postFireEscape.put(wid, escTicks - 1);
            return;  // ヨー/ピッチ変更なし → 直進エスケープ
        }

        double[] movTarget = computeMoveTarget(ws, wingman, entry);
        double[] aimTarget = computeAimTarget(ws, wingman, entry);
        double[] aimRef    = (aimTarget != null) ? aimTarget : movTarget;

        boolean heli = isHelicopter(wingman);
        boolean inAttack = (entry.attackMode != WingmanEntry.ATK_NONE);
        // 攻撃中は旋回レートを高めてスピード感ある機動に
        float maxYawRate = inAttack
            ? (heli ? 6.0f : 3.0f)
            : (heli ? MAX_YAW_RATE_HELI : MAX_YAW_RATE_PLANE);

        // 現在の機体角度を取得
        float currentYaw   = getCurrentRotYaw(wingman);
        float currentPitch = getCurrentRotPitch(wingman);

        if (aimRef != null) {
            double dx = aimRef[0] - wingman.posX;
            double dz = aimRef[2] - wingman.posZ;
            double dy = aimRef[1] - wingman.posY;

            // ─── Yaw: 常にターゲット方向へ機体を向ける ───────────────────
            float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float yawDiff = targetYaw - currentYaw;
            while (yawDiff >  180f) yawDiff -= 360f;
            while (yawDiff < -180f) yawDiff += 360f;
            float yawStep = Math.max(-maxYawRate, Math.min(maxYawRate, yawDiff));
            setRotYaw(wingman, currentYaw + yawStep);
            try { if (lastRiderYawField != null) lastRiderYawField.setFloat(wingman, targetYaw); }
            catch (Exception ignored) {}

            // ─── Pitch ───────────────────────────────────────────────────
            double hDist = Math.sqrt(dx * dx + dz * dz);
            // 固定砲（gun/cannon/cas）: 機首ごとターゲットに向ける
            // それ以外の武器（missile/bomb等）: 機体は水平維持、lastRiderPitchでエイム
            boolean fixedGun = inAttack && isFixedGunWeapon(entry.weaponType);

            float targetPitch;
            if (fixedGun) {
                // 機首でターゲットに直接エイム
                targetPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(hDist, 1.0)));
                targetPitch = Math.max(MAX_PITCH_DOWN, Math.min(MAX_PITCH_UP, targetPitch));
            } else {
                // 高度維持ピッチ（movTargetのY座標に向かう分だけ）
                // aimTargetのdyではなくmovTargetのdyで計算して機体を水平に保つ
                double movDy = (movTarget != null) ? (movTarget[1] - wingman.posY) : dy;
                if (heli) {
                    targetPitch = (float) -Math.toDegrees(Math.atan2(movDy, Math.max(hDist, 0.1)));
                    targetPitch = Math.max(-20f, Math.min(20f, targetPitch));
                } else {
                    targetPitch = (float) -Math.toDegrees(Math.atan2(movDy, Math.max(hDist, 1.0)));
                    targetPitch = Math.max(MAX_PITCH_DOWN, Math.min(MAX_PITCH_UP, targetPitch));
                }
            }
            float pitchStep = Math.max(-MAX_PITCH_RATE, Math.min(MAX_PITCH_RATE, targetPitch - currentPitch));
            setRotPitch(wingman, currentPitch + pitchStep);

            // lastRiderPitch: 攻撃中は武器エイム用に実際のターゲットへの角度を渡す
            float aimPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(hDist, 1.0)));
            try { if (lastRiderPitchField != null) lastRiderPitchField.setFloat(wingman, aimPitch); }
            catch (Exception ignored) {}
        }
    }

    /** 機首方向固定の直射武器（gun/cannon/cas）か判定。これ以外はフリールック可能とみなす。 */
    private static boolean isFixedGunWeapon(String type) {
        if (type == null) return false;
        switch (type.toLowerCase()) {
            case "gun": case "machinegun": case "machinegun1": case "machinegun2":
            case "cannon": case "cas":
                return true;
            default:
                return false;
        }
    }

    // ─── Phase.END ───────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onWorldTickEnd(TickEvent.WorldTickEvent event) {
        if (event.world.isRemote || event.phase != TickEvent.Phase.END) return;
        WorldServer ws = (WorldServer) event.world;

        for (Map.Entry<UUID, WingmanEntry> e : WingmanRegistry.snapshot().entrySet()) {
            UUID id = e.getKey();
            WingmanEntry entry = e.getValue();

            if (entry.leader == null || entry.leader.isDead) {
                WingmanRegistry.remove(id);
                McHeliWingman.logger.info("[Wingman] {} unregistered — leader gone", id);
                continue;
            }

            Entity wingman = ws.getEntityFromUuid(id);
            if (wingman == null || wingman.isDead) continue;

            // 攻撃: 発射のみ（移動はSTARTで処理済み）
            if (entry.attackMode != WingmanEntry.ATK_NONE) {
                Entity target = resolveTarget(ws, wingman, entry);
                if (target != null && !target.isDead) {
                    double dx   = target.posX - wingman.posX;
                    double dy   = (target.posY + 1.5) - wingman.posY;
                    double dz   = target.posZ - wingman.posZ;
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    float  yaw  = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    float  hd   = (float) Math.sqrt(dx * dx + dz * dz);
                    float  pitch = (float) -Math.toDegrees(Math.atan2(dy, hd));
                    if (dist <= fireRangeForWeapon(entry.weaponType)) {
                        tryFire(wingman, entry.leader, target, yaw, pitch, entry.weaponType);
                    }
                }
            }

            syncGear(wingman, entry.leader);
        }
    }

    // ─── Target computation ──────────────────────────────────────────────────

    /** 移動目標座標。武器種別高度オフセット・スタンドオフ込み。 */
    private double[] computeMoveTarget(WorldServer ws, Entity wingman, WingmanEntry entry) {
        if (entry.attackMode != WingmanEntry.ATK_NONE) {
            Entity target = resolveTarget(ws, wingman, entry);
            if (target != null && !target.isDead) {
                double standoff  = standoffForWeapon(entry.weaponType);
                double altOffset = altOffsetForWeapon(entry.weaponType);
                double dx    = target.posX - wingman.posX;
                double dz    = target.posZ - wingman.posZ;
                double hDist = Math.sqrt(dx * dx + dz * dz);
                double idealTY = target.posY + altOffset;

                if (hDist > standoff) {
                    // スタンドオフ距離まで接近
                    double ratio = (hDist - standoff) / hDist;
                    return new double[]{
                        wingman.posX + dx * ratio,
                        idealTY,
                        wingman.posZ + dz * ratio
                    };
                } else {
                    // スタンドオフ圏内: ターゲットから離れる方向に逃げる
                    // 単位ベクトル(wingman→target)の逆方向へ standoff*1.5 の地点を目標にする
                    double norm = Math.max(hDist, 0.1);
                    double awayX = -dx / norm;
                    double awayZ = -dz / norm;
                    return new double[]{
                        target.posX + awayX * standoff * 1.5,
                        idealTY,
                        target.posZ + awayZ * standoff * 1.5
                    };
                }
            }
        }
        if (entry.state == WingmanState.FOLLOWING) {
            return formationPos(entry.leader, entry.formationSlot);
        }
        return null;
    }

    /** エイム目標。攻撃中のみターゲット座標を返す。 */
    private double[] computeAimTarget(WorldServer ws, Entity wingman, WingmanEntry entry) {
        if (entry.attackMode == WingmanEntry.ATK_NONE) return null;
        Entity target = resolveTarget(ws, wingman, entry);
        if (target == null || target.isDead) return null;
        return new double[]{target.posX, target.posY + 1.5, target.posZ};
    }

    /**
     * フォーメーション座標。
     *   side > 0: 通常V字/ダイヤ隊形 (rank = slot/2+1, 左右交互)
     *   side = 0: 縦列 (slot 0 = 1×rear後方, slot N = (N+1)×rear後方, 親機と同方向)
     */
    private double[] formationPos(Entity leader, int slot) {
        double sideDist = WingmanConfig.formationSideDist;
        double altOff   = WingmanConfig.formationAltOffset;
        double rearDist = WingmanConfig.formationRearDist;

        double yawRad = Math.toRadians(leader.rotationYaw);
        double fwdX   = -Math.sin(yawRad);
        double fwdZ   =  Math.cos(yawRad);

        if (sideDist == 0.0) {
            // 縦列: 各機がリアに等間隔で一列
            int rank = slot + 1;
            return new double[]{
                leader.posX - fwdX * rearDist * rank,
                leader.posY + altOff,
                leader.posZ - fwdZ * rearDist * rank
            };
        }

        double rigX   =  Math.cos(yawRad);
        double rigZ   =  Math.sin(yawRad);
        int    rank     = slot / 2 + 1;
        double sideSign = (slot % 2 == 0) ? 1.0 : -1.0;

        return new double[]{
            leader.posX + rigX * sideDist * sideSign * rank - fwdX * rearDist * rank,
            leader.posY + altOff,
            leader.posZ + rigZ * sideDist * sideSign * rank - fwdZ * rearDist * rank
        };
    }

    // ─── Attack ──────────────────────────────────────────────────────────────

    private Entity resolveTarget(WorldServer ws, Entity wingman, WingmanEntry entry) {
        if (entry.attackMode == WingmanEntry.ATK_MANUAL && entry.manualTargetId != null)
            return ws.getEntityFromUuid(entry.manualTargetId);
        if (entry.attackMode == WingmanEntry.ATK_AUTO) {
            java.util.Set<UUID> taken = getTargetedUUIDs(wingman.getUniqueID(), entry.leader);
            Entity target = findNearestHostile(wingman, AUTO_ATTACK_RANGE, taken);
            entry.currentAutoTarget = (target != null) ? target.getUniqueID() : null;
            return target;
        }
        return null;
    }

    private Entity findNearestHostile(Entity wingman, double range, java.util.Set<UUID> exclude) {
        double bestSq = range * range;
        Entity best   = null;
        for (Entity e : wingman.world.loadedEntityList) {
            if (e == wingman || !(e instanceof IMob) || e.isDead) continue;
            if (exclude != null && exclude.contains(e.getUniqueID())) continue;
            double dsq = wingman.getDistanceSq(e);
            if (dsq < bestSq) { bestSq = dsq; best = e; }
        }
        return best;
    }

    /** 同一リーダーの他子機が攻撃中のターゲットUUIDセットを返す（分散攻撃用）。 */
    private java.util.Set<UUID> getTargetedUUIDs(UUID selfId, Entity leader) {
        java.util.Set<UUID> taken = new java.util.HashSet<>();
        for (Map.Entry<UUID, WingmanEntry> e : WingmanRegistry.snapshot().entrySet()) {
            if (e.getKey().equals(selfId)) continue;
            WingmanEntry other = e.getValue();
            if (other.leader != leader) continue;
            if (other.attackMode == WingmanEntry.ATK_MANUAL && other.manualTargetId != null)
                taken.add(other.manualTargetId);
            else if (other.attackMode == WingmanEntry.ATK_AUTO && other.currentAutoTarget != null)
                taken.add(other.currentAutoTarget);
        }
        return taken;
    }

    private void tryFire(Entity aircraft, Entity leader, Entity target,
                         float yaw, float pitch, String weaponType) {
        try {
            resolveWeaponReflection(aircraft);
            if (weaponsField == null || weaponParamCtor == null) return;

            Object[] wps = (Object[]) weaponsField.get(aircraft);
            if (wps == null || wps.length == 0) return;

            if (!weaponDebugLogged) { weaponDebugLogged = true; logWeaponInfo(wps); }

            UUID id = aircraft.getUniqueID();
            int cd = fireCooldowns.getOrDefault(id, 0);
            if (cd > 0) { fireCooldowns.put(id, cd - 1); return; }

            Object chosen = pickWeapon(wps, weaponType, true);
            if (chosen == null) chosen = pickWeapon(wps, weaponType, false);
            if (chosen == null) return;

            String resolvedType = resolveWeaponType(chosen, weaponType);

            // 弾薬が空の場合は最大値まで補充（UAVは自律補給）
            ensureAmmoLoaded(chosen);

            boolean isASMissile = "asmissile".equalsIgnoreCase(resolvedType);

            Object param = isASMissile
                    ? buildParamFull(aircraft, null, target.posX, target.posY, target.posZ, yaw, pitch)
                    : buildParamFull(aircraft, target, aircraft.posX, aircraft.posY, aircraft.posZ, yaw, pitch);
            if (param == null) return;

            boolean fired = fireDirectShot(chosen, param);
            if (!fired) fired = fireViaUse(aircraft, leader, chosen, param);

            if (fired) {
                int cooldown = fireCooldownForType(resolvedType);
                fireCooldowns.put(id, cooldown);
                // 発射後エスケープ（爆弾・ミサイル系は長め）
                int escapeTicks = isHeavyWeapon(resolvedType) ? POST_FIRE_ESCAPE_TICKS : 0;
                if (escapeTicks > 0) postFireEscape.put(id, escapeTicks);
                McHeliWingman.logger.debug("[Wingman] fired type={} cooldown={} escape={}", resolvedType, cooldown, escapeTicks);
            } else {
                McHeliWingman.logger.debug("[Wingman] tryFire failed type={}", resolvedType);
            }
        } catch (Exception e) {
            McHeliWingman.logger.warn("[Wingman] tryFire exception: {}", e.toString());
        }
    }

    private static boolean isHeavyWeapon(String type) {
        if (type == null) return false;
        switch (type.toLowerCase()) {
            case "bomb": case "asmissile": case "missile":
            case "aamissile": case "atmissile": case "tvmissile":
                return true;
            default: return false;
        }
    }

    /** 弾薬が空なら最大値まで補充する（UAV自律補給）。 */
    private void ensureAmmoLoaded(Object weaponSet) {
        try {
            Method getMax = weaponSet.getClass().getMethod("getAmmoNumMax");
            Method getNum = weaponSet.getClass().getMethod("getAmmoNum");
            Method setNum = weaponSet.getClass().getMethod("setAmmoNum", int.class);
            int max = (int) getMax.invoke(weaponSet);
            if (max > 0 && (int) getNum.invoke(weaponSet) <= 0) {
                setNum.invoke(weaponSet, max);
            }
        } catch (Exception ignored) {}
    }

    private Object pickWeapon(Object[] wps, String weaponType, boolean requireCanUse) {
        for (Object ws : wps) {
            if (ws == null) continue;
            String t = resolveWeaponType(ws, "");
            // dummyは常にスキップ
            if ("dummy".equalsIgnoreCase(t)) continue;
            // targetingpodはコマンドで明示指定された場合のみ使用
            if ("targetingpod".equalsIgnoreCase(t) && !"targetingpod".equalsIgnoreCase(weaponType)) continue;
            if (weaponType != null && !matchesWeaponType(ws, weaponType)) continue;
            if (requireCanUse && weaponSetCanUse != null) {
                try { if (!(Boolean) weaponSetCanUse.invoke(ws)) continue; }
                catch (Exception ignored) {}
            }
            return ws;
        }
        return null;
    }

    private String resolveWeaponType(Object weaponSet, String fallback) {
        try {
            Object info = weaponSet.getClass().getMethod("getInfo").invoke(weaponSet);
            if (info == null) return fallback;
            String t = (String) info.getClass().getField("type").get(info);
            return t != null ? t : fallback;
        } catch (Exception e) { return fallback; }
    }

    private boolean matchesWeaponType(Object weaponSet, String expectedType) {
        String actual = resolveWeaponType(weaponSet, null);
        if (actual == null) return false;
        if (expectedType.equalsIgnoreCase(actual)) return true;
        String exp = expectedType.toLowerCase();
        String act = actual.toLowerCase();
        switch (exp) {
            case "gun":       return act.contains("gun") || act.equals("cas");
            case "cannon":    return act.contains("cannon");
            case "missile":   return act.contains("missile");
            case "asmissile": return act.equals("asmissile");
            case "rocket":    return act.contains("rocket");
            case "bomb":      return act.contains("bomb");
            case "torpedo":   return act.contains("torpedo");
            default:          return false;
        }
    }

    private boolean fireDirectShot(Object weaponSet, Object param) {
        try {
            if (weaponSetGetCurrentWeapon == null || weaponBaseShot == null) return false;
            Object wb = weaponSetGetCurrentWeapon.invoke(weaponSet);
            if (wb == null) return false;
            return Boolean.TRUE.equals(weaponBaseShot.invoke(wb, param));
        } catch (Exception e) {
            McHeliWingman.logger.debug("[Wingman] fireDirectShot: {}", e.getMessage());
            return false;
        }
    }

    private boolean fireViaUse(Entity aircraft, Entity leader, Object weaponSet, Object param) {
        if (weaponSetUse == null) return false;
        Entity prevRider = null;
        try {
            if (lastRiddenByEntityField != null) {
                prevRider = (Entity) lastRiddenByEntityField.get(aircraft);
                lastRiddenByEntityField.set(aircraft, leader);
            }
            return Boolean.TRUE.equals(weaponSetUse.invoke(weaponSet, param));
        } catch (Exception e) {
            McHeliWingman.logger.debug("[Wingman] fireViaUse: {}", e.getMessage());
            return false;
        } finally {
            if (lastRiddenByEntityField != null) {
                try { lastRiddenByEntityField.set(aircraft, prevRider); } catch (Exception ignored) {}
            }
        }
    }

    private Object buildParamFull(Entity aircraft, Entity targetEntity,
                                   double px, double py, double pz, float yaw, float pitch) {
        try {
            Object param = weaponParamCtor.newInstance();
            if (wpUser   != null) wpUser.set(param, aircraft);
            if (wpEntity != null) wpEntity.set(param, targetEntity);
            if (wpPosX   != null) wpPosX.setDouble(param, px);
            if (wpPosY   != null) wpPosY.setDouble(param, py);
            if (wpPosZ   != null) wpPosZ.setDouble(param, pz);
            if (wpRotYaw != null) wpRotYaw.setFloat(param, yaw);
            if (wpRotPitch != null) wpRotPitch.setFloat(param, pitch);
            return param;
        } catch (Exception e) { return null; }
    }

    private void logWeaponInfo(Object[] wps) {
        McHeliWingman.logger.info("[Wingman] Weapon slots: {}", wps.length);
        for (int i = 0; i < wps.length; i++) {
            Object ws = wps[i];
            if (ws == null) { McHeliWingman.logger.info("[Wingman]   [{}] null", i); continue; }
            try {
                String name    = (String) ws.getClass().getMethod("getName").invoke(ws);
                boolean canUse = (Boolean) ws.getClass().getMethod("canUse").invoke(ws);
                Object info    = ws.getClass().getMethod("getInfo").invoke(ws);
                String type    = info != null ? (String) info.getClass().getField("type").get(info) : "?";
                boolean ridOnly = false;
                if (info != null) try { ridOnly = info.getClass().getField("ridableOnly").getBoolean(info); } catch (Exception ignored) {}
                McHeliWingman.logger.info("[Wingman]   [{}] name={} type={} canUse={} ridableOnly={}", i, name, type, canUse, ridOnly);
            } catch (Exception e) {
                McHeliWingman.logger.info("[Wingman]   [{}] error: {}", i, e.getMessage());
            }
        }
    }

    // ─── Engine ──────────────────────────────────────────────────────────────

    // スロットルが切り替わり始める距離（ブロック）
    private static final double THROTTLE_BLEND_FAR  = 80.0;  // この距離以上: 全力
    private static final double THROTTLE_BLEND_NEAR = 12.0;  // この距離以下: 親機同調

    /**
     * フォーメーション追従用適応スロットル。
     * 遠い: 全力 / 近い: 親機スロットルに同調して追い抜き防止。
     */
    @SuppressWarnings("unchecked")
    private void maintainEngineAdaptive(Entity aircraft, Entity leader, double distToSlot) {
        resolveThrottle(aircraft);
        double leaderThrottle = getEntityThrottle(leader);

        double targetThrottle;
        if (distToSlot >= THROTTLE_BLEND_FAR) {
            targetThrottle = 1.0;
        } else if (distToSlot <= THROTTLE_BLEND_NEAR) {
            targetThrottle = leaderThrottle;
        } else {
            double t = (distToSlot - THROTTLE_BLEND_NEAR) / (THROTTLE_BLEND_FAR - THROTTLE_BLEND_NEAR);
            targetThrottle = leaderThrottle + t * (1.0 - leaderThrottle);
        }
        // 未到着時の最低スロットル: 停止中の親機でも子機は位置に向かって動き続けられる
        double minThrottle = distToSlot > ARRIVAL_THRESHOLD ? 0.25 : 0.05;
        targetThrottle = Math.max(minThrottle, Math.min(1.0, targetThrottle));

        applyThrottle(aircraft, targetThrottle);
    }

    /** 指定スロットル値を直接適用する（攻撃モード・強制指定用）。 */
    @SuppressWarnings("unchecked")
    private void maintainEngine(Entity aircraft, double throttle) {
        resolveThrottle(aircraft);
        applyThrottle(aircraft, throttle);
    }

    @SuppressWarnings("unchecked")
    private void applyThrottle(Entity aircraft, double throttle) {
        try { if (setCurrentThrottleMethod != null) setCurrentThrottleMethod.invoke(aircraft, throttle); }
        catch (Exception ignored) {}
        try {
            if (throttleDataParamField != null) {
                DataParameter<Integer> p = (DataParameter<Integer>) throttleDataParamField.get(null);
                aircraft.getDataManager().set(p, (int) (throttle * 100));
            }
        } catch (Exception ignored) {}
    }

    /** McHeli aircraft の現在スロットル値を取得する。取得失敗時は 0.5 を返す。 */
    private double getEntityThrottle(Entity entity) {
        resolveThrottle(entity);
        try {
            if (getCurrentThrottleMethod != null)
                return (Double) getCurrentThrottleMethod.invoke(entity);
        } catch (Exception ignored) {}
        return 0.5;
    }

    // ─── Gear ────────────────────────────────────────────────────────────────

    private void syncGear(Entity wingman, Entity leader) {
        resolveGearMethods(leader);
        if (isLandingGearFolded == null) return;
        try {
            boolean lf = (boolean) isLandingGearFolded.invoke(leader);
            boolean wf = (boolean) isLandingGearFolded.invoke(wingman);
            boolean wc = (boolean) canFoldLandingGear.invoke(wingman);
            if (!wc) return;
            if (lf && !wf) foldLandingGear.invoke(wingman);
            else if (!lf && wf) unfoldLandingGear.invoke(wingman);
        } catch (Exception ex) {
            McHeliWingman.logger.debug("[Wingman] syncGear: {}", ex.getMessage());
        }
    }

    // ─── Leader / hold helpers ───────────────────────────────────────────────

    private static boolean isLeaderStopped(Entity leader) {
        double spd = Math.sqrt(
            leader.motionX * leader.motionX +
            leader.motionY * leader.motionY +
            leader.motionZ * leader.motionZ);
        return spd < LEADER_STOP_SPEED;
    }

    /** スロットルゼロで自然減速し、親機の向きに徐々に揃える（停止ホールド用）。 */
    private void holdStop(Entity aircraft, Entity leader) {
        resolveThrottle(aircraft);
        applyThrottle(aircraft, 0.0);
        // 停止中は親機と同方向を向く（地上整列）
        resolveRotationMethods(aircraft);
        float leaderYaw = leader.rotationYaw;
        float childYaw  = getCurrentRotYaw(aircraft);
        float yawDiff   = leaderYaw - childYaw;
        while (yawDiff >  180f) yawDiff -= 360f;
        while (yawDiff < -180f) yawDiff += 360f;
        float yawStep = Math.max(-MAX_YAW_RATE_HELI, Math.min(MAX_YAW_RATE_HELI, yawDiff));
        setRotYaw(aircraft, childYaw + yawStep);
    }

    // ─── Aircraft type / VTOL ────────────────────────────────────────────────

    private boolean isHelicopter(Entity aircraft) {
        try {
            return "heli".equalsIgnoreCase((String) aircraft.getClass().getMethod("getKindName").invoke(aircraft));
        } catch (Exception e) { return true; }
    }

    private void forceVtolOff(Entity aircraft) {
        if (!vtolResolved) {
            vtolResolved = true;
            getVtolModeMethod   = findMethod(aircraft.getClass(), "getVtolMode");
            swithVtolModeMethod = findMethod(aircraft.getClass(), "swithVtolMode", boolean.class);
            McHeliWingman.logger.info("[Wingman] VTOL methods resolved: {}",
                    getVtolModeMethod != null && swithVtolModeMethod != null);
        }
        if (getVtolModeMethod == null || swithVtolModeMethod == null) return;
        try {
            if ((int) getVtolModeMethod.invoke(aircraft) != 0) {
                swithVtolModeMethod.invoke(aircraft, false);
            }
        } catch (Exception ignored) {}
    }

    // ─── Reflection resolution ───────────────────────────────────────────────

    private void resolveThrottle(Entity aircraft) {
        if (throttleResolved) return;
        throttleResolved = true;
        setCurrentThrottleMethod = findMethod(aircraft.getClass(), "setCurrentThrottle", double.class);
        getCurrentThrottleMethod = findMethod(aircraft.getClass(), "getCurrentThrottle");
        throttleDataParamField   = findStaticField(aircraft.getClass(), "THROTTLE");
        McHeliWingman.logger.info("[Wingman] Throttle resolved: set={} get={}",
                setCurrentThrottleMethod != null, getCurrentThrottleMethod != null);
    }

    private void resolveRotationMethods(Entity entity) {
        if (rotationResolved) return;
        rotationResolved = true;
        setRotYawMethod   = findMethod(entity.getClass(), "setRotYaw", float.class);
        setRotPitchMethod = findMethod(entity.getClass(), "setRotPitch", float.class);
        getRotYawMethod   = findMethod(entity.getClass(), "getRotYaw");
        getRotPitchMethod = findMethod(entity.getClass(), "getRotPitch");
        McHeliWingman.logger.info("[Wingman] Rotation methods: setYaw={} setPitch={} getYaw={} getPitch={}",
                setRotYawMethod != null, setRotPitchMethod != null,
                getRotYawMethod != null, getRotPitchMethod != null);
    }

    private void resolveControlFields(Entity entity) {
        if (controlResolved) return;
        controlResolved = true;
        lastRiderYawField   = findField(entity.getClass(), "lastRiderYaw");
        lastRiderPitchField = findField(entity.getClass(), "lastRiderPitch");
        McHeliWingman.logger.info("[Wingman] Control fields: lastRiderYaw={} lastRiderPitch={}",
                lastRiderYawField != null, lastRiderPitchField != null);
    }

    private float getCurrentRotYaw(Entity aircraft) {
        try {
            if (getRotYawMethod != null) return (Float) getRotYawMethod.invoke(aircraft);
        } catch (Exception ignored) {}
        return aircraft.rotationYaw;
    }

    private float getCurrentRotPitch(Entity aircraft) {
        try {
            if (getRotPitchMethod != null) return (Float) getRotPitchMethod.invoke(aircraft);
        } catch (Exception ignored) {}
        return aircraft.rotationPitch;
    }

    private void setRotYaw(Entity aircraft, float yaw) {
        try {
            if (setRotYawMethod != null) setRotYawMethod.invoke(aircraft, yaw);
        } catch (Exception ignored) {}
    }

    private void setRotPitch(Entity aircraft, float pitch) {
        try {
            if (setRotPitchMethod != null) setRotPitchMethod.invoke(aircraft, pitch);
        } catch (Exception ignored) {}
    }

    private void resolveGearMethods(Entity aircraft) {
        if (isLandingGearFolded != null) return;
        Class<?> cls = aircraft.getClass();
        while (cls != null) {
            try {
                isLandingGearFolded = cls.getDeclaredMethod("isLandingGearFolded"); isLandingGearFolded.setAccessible(true);
                foldLandingGear     = cls.getDeclaredMethod("foldLandingGear");     foldLandingGear.setAccessible(true);
                unfoldLandingGear   = cls.getDeclaredMethod("unfoldLandingGear");   unfoldLandingGear.setAccessible(true);
                canFoldLandingGear  = cls.getDeclaredMethod("canFoldLandingGear");  canFoldLandingGear.setAccessible(true);
                McHeliWingman.logger.info("[Wingman] Gear methods resolved from {}", cls.getSimpleName());
                return;
            } catch (NoSuchMethodException ignored) { cls = cls.getSuperclass(); }
        }
    }

    private void resolveWeaponReflection(Entity aircraft) {
        if (weaponsField != null) return;
        try {
            weaponsField              = findField(aircraft.getClass(), "weapons");
            weaponParamClass          = Class.forName("mcheli.weapon.MCH_WeaponParam");
            weaponParamCtor           = weaponParamClass.getConstructor();
            wpEntity                  = weaponParamClass.getField("entity");
            wpUser                    = weaponParamClass.getField("user");
            wpPosX                    = weaponParamClass.getField("posX");
            wpPosY                    = weaponParamClass.getField("posY");
            wpPosZ                    = weaponParamClass.getField("posZ");
            wpRotYaw                  = weaponParamClass.getField("rotYaw");
            wpRotPitch                = weaponParamClass.getField("rotPitch");
            Class<?> wsClass          = Class.forName("mcheli.weapon.MCH_WeaponSet");
            Class<?> wbClass          = Class.forName("mcheli.weapon.MCH_WeaponBase");
            weaponSetUse              = wsClass.getMethod("use", weaponParamClass);
            weaponSetCanUse           = wsClass.getMethod("canUse");
            weaponSetGetCurrentWeapon = wsClass.getMethod("getCurrentWeapon");
            weaponBaseShot            = wbClass.getMethod("shot", weaponParamClass);
            lastRiddenByEntityField   = findField(aircraft.getClass(), "lastRiddenByEntity");
            McHeliWingman.logger.info("[Wingman] Weapon reflection resolved. shot={} lastRiddenByEntity={}",
                    weaponBaseShot != null, lastRiddenByEntityField != null);
        } catch (Exception e) {
            McHeliWingman.logger.warn("[Wingman] Weapon reflection failed: {}", e.toString());
        }
    }

    // ─── Reflection helpers ──────────────────────────────────────────────────

    private static Field findField(Class<?> cls, String name) {
        while (cls != null) {
            try { Field f = cls.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { cls = cls.getSuperclass(); }
        }
        return null;
    }

    private static Field findStaticField(Class<?> cls, String name) {
        while (cls != null) {
            try {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getName().equals(name) && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true); return f;
                    }
                }
            } catch (Exception ignored) {}
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        while (cls != null) {
            try { Method m = cls.getDeclaredMethod(name, params); m.setAccessible(true); return m; }
            catch (NoSuchMethodException ignored) { cls = cls.getSuperclass(); }
        }
        return null;
    }
}
