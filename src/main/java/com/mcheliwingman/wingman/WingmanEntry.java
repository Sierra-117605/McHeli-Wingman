package com.mcheliwingman.wingman;

import com.mcheliwingman.mission.AutonomousState;
import com.mcheliwingman.mission.MissionNode;
import net.minecraft.entity.Entity;

import java.util.List;
import java.util.UUID;

public class WingmanEntry {

    // Attack mode constants (avoid separate enum to prevent classloader issues)
    public static final int ATK_NONE   = 0;
    public static final int ATK_MANUAL = 1;
    public static final int ATK_AUTO   = 2;

    public WingmanState state;
    public Entity leader;
    public int formationSlot;

    public int attackMode = ATK_NONE;
    public UUID manualTargetId = null;
    public UUID currentAutoTarget = null;  // AUTO攻撃中の現在ターゲット（分散攻撃用）
    public int weaponSeat = 0;
    /** McHeli武器種フィルタ (null = 全種試す, "gun"/"missile"/"bomb"等) */
    public String weaponType = null;

    // ─── 自律飛行 ─────────────────────────────────────────────────────────────
    public AutonomousState autoState   = AutonomousState.NONE;
    public List<MissionNode> mission   = null;  // 実行中のミッションプラン
    public int missionIndex            = 0;     // 現在実行中のノードインデックス
    public int missionNodeTimer        = 0;     // LOITERなどのノード内タイマー
    // 自律飛行中の目標座標（AutonomousFlightHandlerが毎tick書き込む）
    public double autoTargetX = 0, autoTargetY = 0, autoTargetZ = 0;

    public boolean isAutonomous() {
        return autoState != AutonomousState.NONE && mission != null;
    }

    public MissionNode currentNode() {
        if (mission == null || missionIndex >= mission.size()) return null;
        return mission.get(missionIndex);
    }

    public void advanceMission() {
        missionIndex++;
        missionNodeTimer = 0;
    }

    public WingmanEntry(Entity leader, int slot) {
        this.state         = WingmanState.FOLLOWING;
        this.leader        = leader;
        this.formationSlot = slot;
    }

    /** 自律モード専用エントリ（leader不要）。 */
    public WingmanEntry() {
        this.state         = WingmanState.FOLLOWING;
        this.leader        = null;
        this.formationSlot = 0;
    }
}
