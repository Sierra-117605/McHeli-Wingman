package com.mcheliwingman.wingman;

import com.mcheliwingman.mission.AutonomousState;
import com.mcheliwingman.mission.MissionNode;
import com.mcheliwingman.mission.MissionOrder;
import net.minecraft.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class WingmanEntry {

    // Attack mode constants
    public static final int ATK_NONE   = 0;
    public static final int ATK_MANUAL = 1;
    public static final int ATK_AUTO   = 2;

    public WingmanState state;
    public Entity leader;
    public int formationSlot;

    public int attackMode = ATK_NONE;
    public UUID manualTargetId = null;
    public UUID currentAutoTarget = null;
    public int weaponSeat = 0;
    /** McHeli武器種フィルタ (null = 全種試す) */
    public String weaponType = null;

    // ─── 旧MissionNode系 自律飛行 ────────────────────────────────────────────
    public AutonomousState autoState   = AutonomousState.NONE;
    public List<MissionNode> mission   = null;
    public int missionIndex            = 0;
    public int missionNodeTimer        = 0;
    public double autoTargetX = 0, autoTargetY = 0, autoTargetZ = 0;

    // ─── 新MissionOrder系 自律飛行 ───────────────────────────────────────────

    /** 発令されたミッション（nullなら旧系またはアイドル） */
    public MissionOrder order = null;

    /** オンステーション経過tick */
    public int orderTimer = 0;

    /** 旋回角度（CAP/CAS orbit用、ラジアン） */
    public double orbitAngle = 0.0;

    /** ストライク残りパス数 */
    public int strikePassesRemaining = 0;

    /** 現在割り当てられた駐機スポットID（TAXI_OUT/IN で使用） */
    public String assignedParkingId = "";

    /** タキシーWPキュー（TAXI_OUT/IN フェーズで順に消化） */
    public List<String> taxiWpQueue = new ArrayList<>();

    /** タキシーWPキューの現在インデックス */
    public int taxiWpIndex = 0;

    /** 偵察フェーズで検知したMob数 */
    public int reconMobCount = 0;

    /** RTBトリガー理由（ログ用） */
    public String rtbReason = "";

    // ─── 判定メソッド ─────────────────────────────────────────────────────────

    public boolean isAutonomous() {
        return (autoState != AutonomousState.NONE && mission != null) || order != null;
    }

    /** MissionOrder系で動作中か */
    public boolean hasOrder() {
        return order != null;
    }

    public MissionNode currentNode() {
        if (mission == null || missionIndex >= mission.size()) return null;
        return mission.get(missionIndex);
    }

    public void advanceMission() {
        missionIndex++;
        missionNodeTimer = 0;
    }

    // ─── コンストラクタ ───────────────────────────────────────────────────────

    public WingmanEntry(Entity leader, int slot) {
        this.state         = WingmanState.FOLLOWING;
        this.leader        = leader;
        this.formationSlot = slot;
    }

    /** 自律モード専用エントリ */
    public WingmanEntry() {
        this.state         = WingmanState.FOLLOWING;
        this.leader        = null;
        this.formationSlot = 0;
    }
}
