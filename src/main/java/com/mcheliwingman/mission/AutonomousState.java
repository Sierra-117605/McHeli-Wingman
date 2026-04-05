package com.mcheliwingman.mission;

/** 自律飛行の状態機械ステート。 */
public enum AutonomousState {
    NONE,           // 自律モードでない（通常follow/idle）
    TAXI_OUT,       // 駐機場 → 滑走路端 地上滑走中
    TAKEOFF_ROLL,   // 滑走路上 加速中
    CLIMB,          // 上昇中（目標高度まで）
    ENROUTE,        // 巡航中（FLY_TOノード実行中）
    ATTACK,         // ATTACKノード実行中
    LOITER,         // LOITERノード実行中
    DESCEND,        // 降下中（着陸アプローチ）
    APPROACH,       // 滑走路アライン中
    LANDING,        // 着陸滑走中
    TAXI_IN,        // 滑走路 → 駐機場 地上滑走中
    PARKED          // 駐機中
}
