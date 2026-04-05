package com.mcheliwingman.wingman;

import net.minecraft.entity.Entity;

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

    public WingmanEntry(Entity leader, int slot) {
        this.state         = WingmanState.FOLLOWING;
        this.leader        = leader;
        this.formationSlot = slot;
    }
}
