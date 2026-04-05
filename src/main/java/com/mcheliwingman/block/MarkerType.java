package com.mcheliwingman.block;

/** WingmanMarkerBlock のモード。NBTに文字列で保存。 */
public enum MarkerType {
    PARKING,    // 駐機場
    RUNWAY_A,   // 滑走路端A（離陸起点 / 着陸終点）
    RUNWAY_B,   // 滑走路端B（離陸終点 / 着陸起点）
    WAYPOINT;   // 空中巡航ウェイポイント（XZ座標のみ使用）

    public MarkerType next() {
        MarkerType[] v = values();
        return v[(ordinal() + 1) % v.length];
    }

    public String displayName() {
        switch (this) {
            case PARKING:   return "§e[Parking]";
            case RUNWAY_A:  return "§a[Runway-A]";
            case RUNWAY_B:  return "§b[Runway-B]";
            case WAYPOINT:  return "§d[Waypoint]";
            default:        return name();
        }
    }
}
