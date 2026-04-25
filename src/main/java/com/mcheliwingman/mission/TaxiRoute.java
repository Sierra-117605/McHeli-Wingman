package com.mcheliwingman.mission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * タキシールート定義。
 * 駐機スポット → WPリスト（順序付き）→ 滑走路A端 を表す。
 * 帰還時（着陸後）は waypointIdsReversed() を使い逆順で辿る。
 */
public class TaxiRoute {

    public final String routeId;
    public final String baseId;
    public final String parkingId;     // PARKING マーカー ID
    public final String runwayId;      // RUNWAY_A マーカー ID（離陸側）
    public final List<String> waypointIds; // WAYPOINT マーカー ID リスト（出発順）

    public TaxiRoute(String routeId, String baseId, String parkingId,
                     String runwayId, List<String> waypointIds) {
        this.routeId     = routeId;
        this.baseId      = baseId;
        this.parkingId   = parkingId;
        this.runwayId    = runwayId;
        this.waypointIds = Collections.unmodifiableList(new ArrayList<>(waypointIds));
    }

    /** 帰還用WPリスト（逆順） */
    public List<String> waypointIdsReversed() {
        List<String> rev = new ArrayList<>(waypointIds);
        Collections.reverse(rev);
        return rev;
    }

    /** 出発順の全経由点リスト（parking→wp…→runway） */
    public List<String> fullDeparture() {
        List<String> all = new ArrayList<>();
        all.add(parkingId);
        all.addAll(waypointIds);
        all.add(runwayId);
        return all;
    }

    /** 帰還順の全経由点リスト（runway→wp…→parking） */
    public List<String> fullArrival() {
        List<String> all = new ArrayList<>();
        all.add(runwayId);
        all.addAll(waypointIdsReversed());
        all.add(parkingId);
        return all;
    }
}
