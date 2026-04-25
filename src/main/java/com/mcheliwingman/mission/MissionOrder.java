package com.mcheliwingman.mission;

import java.util.HashSet;
import java.util.Set;

/**
 * ミッション発令データ。GUIから生成されサーバーに送信される。
 * WingmanEntry に格納され AutonomousFlightHandler が参照する。
 */
public class MissionOrder {

    /** 選択された任務種別（排他チェック済み） */
    public Set<MissionType> missionTypes = new HashSet<>();

    /** 選択された武装セット */
    public Set<String> weapons = new HashSet<>();

    /** 目標エリア中心座標（XZ） */
    public double targetX = 0;
    public double targetZ = 0;

    /** 任務時間制限（分、0=無制限） */
    public int timeLimitMinutes = 60;

    /** 出発・帰投基地ID */
    public String baseId = "";

    /** 旋回半径（CAP/CAS用、blocks） */
    public double orbitRadius = 300.0;

    /** 巡航高度（Y） */
    public double cruiseAlt = 80.0;

    /** ストライクパス回数 */
    public int strikePasses = 2;

    /** フェリー目的地基地ID（FERRY専用） */
    public String ferryDestBase = "";

    public MissionOrder() {}

    /** 時間制限（tick換算） */
    public int timeLimitTicks() {
        return timeLimitMinutes * 60 * 20;
    }

    public boolean hasType(MissionType type) {
        return missionTypes.contains(type);
    }

    public boolean isExclusive() {
        for (MissionType t : missionTypes) {
            if (t.isExclusive()) return true;
        }
        return false;
    }
}
