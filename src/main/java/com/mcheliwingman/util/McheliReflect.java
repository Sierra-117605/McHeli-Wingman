package com.mcheliwingman.util;

import com.mcheliwingman.McHeliWingman;
import net.minecraft.entity.Entity;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Reflection utilities for McHeli CE internals.
 *
 * NOTE: These reflection calls target McHeli CE internal fields and methods.
 * They may break if McHeli CE is updated. Version tested: 1.1.4
 */
public class McheliReflect {

    // Cache for resolved fields to avoid repeated lookups
    private static final Map<String, Field>  fieldCache   = new HashMap<>();
    /** 存在しないと確定したフィールドのキーセット（毎tick WARN が出るのを防ぐ）。 */
    private static final java.util.Set<String> notFoundKeys = new java.util.HashSet<>();

    private static final String CLASS_AIRCRAFT = "mcheli.aircraft.MCH_EntityAircraft";

    /**
     * Returns the MCH_EntityAircraft class, or null if not found.
     */
    public static Class<?> getAircraftClass() {
        try {
            return Class.forName(CLASS_AIRCRAFT);
        } catch (ClassNotFoundException e) {
            McHeliWingman.logger.error("McHeli CE class not found: {}", CLASS_AIRCRAFT);
            return null;
        }
    }

    /**
     * Returns true if the entity is a McHeli aircraft.
     */
    public static boolean isAircraft(Entity entity) {
        if (entity == null) return false;
        Class<?> cls = getAircraftClass();
        return cls != null && cls.isInstance(entity);
    }

    /**
     * Returns true if the entity is a McHeli UAV.
     * Calls MCH_EntityAircraft#isUAV() via reflection.
     */
    public static boolean isUAV(Entity entity) {
        if (!isAircraft(entity)) return false;
        try {
            java.lang.reflect.Method m = entity.getClass().getMethod("isUAV");
            return (boolean) m.invoke(entity);
        } catch (Exception e) {
            McHeliWingman.logger.warn("isUAV() call failed on {}: {}", entity, e.getMessage());
            return false;
        }
    }

    /**
     * Gets a field from the class or any superclass, with caching.
     * Fields confirmed missing are recorded in notFoundKeys to suppress repeat WARNs.
     */
    public static Field getField(Class<?> cls, String name) {
        String key = cls.getName() + "#" + name;
        if (notFoundKeys.contains(key)) return null;
        if (fieldCache.containsKey(key)) return fieldCache.get(key);
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                fieldCache.put(key, f);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        McHeliWingman.logger.warn("Field not found: {} (suppressing further warnings)", key);
        notFoundKeys.add(key);
        return null;
    }

    /**
     * Gets the value of a field on an object.
     * Returns null if the field is not found or access fails.
     */
    public static Object getFieldValue(Object obj, String fieldName) {
        if (obj == null) return null;
        Field f = getField(obj.getClass(), fieldName);
        if (f == null) return null;
        try {
            return f.get(obj);
        } catch (IllegalAccessException e) {
            McHeliWingman.logger.warn("Cannot read field {}: {}", fieldName, e.getMessage());
            return null;
        }
    }

    private static final String CLASS_UAV_STATION = "mcheli.uav.MCH_EntityUavStation";

    /**
     * Returns the MCH_EntityUavStation class, or null if not found.
     */
    public static Class<?> getUavStationClass() {
        try {
            return Class.forName(CLASS_UAV_STATION);
        } catch (ClassNotFoundException e) {
            McHeliWingman.logger.error("McHeli CE class not found: {}", CLASS_UAV_STATION);
            return null;
        }
    }

    /**
     * Returns the MCH_EntityUavStation currently assigned to this aircraft,
     * or null if none / not applicable.
     * Calls MCH_EntityAircraft#getUavStation() via reflection.
     */
    public static Object getUavStation(Entity aircraft) {
        if (!isAircraft(aircraft)) return null;
        try {
            java.lang.reflect.Method m = aircraft.getClass().getMethod("getUavStation");
            return m.invoke(aircraft);
        } catch (Exception e) {
            McHeliWingman.logger.warn("getUavStation() call failed on {}: {}", aircraft, e.getMessage());
            return null;
        }
    }

    /**
     * Returns the Entity currently riding (controlling) the given UAV station,
     * or null if no rider.  The station object must be an MCH_EntityUavStation.
     * Calls MCH_EntityUavStation#getRiddenByEntity() via reflection.
     */
    public static Entity getStationRider(Object station) {
        if (station == null) return null;
        try {
            java.lang.reflect.Method m = station.getClass().getMethod("getRiddenByEntity");
            Object result = m.invoke(station);
            return (result instanceof Entity) ? (Entity) result : null;
        } catch (Exception e) {
            McHeliWingman.logger.warn("getRiddenByEntity() call failed: {}", e.getMessage());
            return null;
        }
    }

    // ─── スロットル ──────────────────────────────────────────────────────────

    /** 現在のスロットル値 (0.0〜1.0) を返す（取得失敗時は -1）。 */
    public static double getCurrentThrottle(Entity aircraft) {
        if (!isAircraft(aircraft)) return -1;
        try {
            java.lang.reflect.Method m = aircraft.getClass().getMethod("getCurrentThrottle");
            Object v = m.invoke(aircraft);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Exception ignored) {}
        for (String name : new String[]{"currentThrottle", "throttle", "throttleLevel"}) {
            Object v = getFieldValue(aircraft, name);
            if (v instanceof Number) return ((Number) v).doubleValue();
        }
        return -1;
    }

    // ─── 燃料 ────────────────────────────────────────────────────────────────

    /** 現在の燃料残量を返す（取得失敗時は -1）。 */
    public static double getFuel(Entity aircraft) {
        if (!isAircraft(aircraft)) return -1;
        for (String name : new String[]{"currentFuel", "fuel", "fuelAmount"}) {
            Object v = getFieldValue(aircraft, name);
            if (v instanceof Number) return ((Number) v).doubleValue();
        }
        // フォールバック: acInfo の maxFuel をチェック
        return -1;
    }

    /** 最大燃料を返す（取得失敗時は -1）。 */
    public static double getMaxFuel(Entity aircraft) {
        if (!isAircraft(aircraft)) return -1;
        for (String name : new String[]{"maxFuel", "fuelMax", "fuelCapacity"}) {
            Object v = getFieldValue(aircraft, name);
            if (v instanceof Number) return ((Number) v).doubleValue();
        }
        // acInfo から取得を試みる
        try {
            java.lang.reflect.Method getAcInfo = aircraft.getClass().getMethod("getAcInfo");
            Object acInfo = getAcInfo.invoke(aircraft);
            if (acInfo != null) {
                Object v = getFieldValue(acInfo, "maxFuel");
                if (v instanceof Number) return ((Number) v).doubleValue();
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * 燃料が無限設定かどうかを返す。
     * McHeli では isInfiniteFuel() メソッドまたは fuelInfinite フィールドで判定。
     */
    public static boolean hasInfiniteFuel(Entity aircraft) {
        if (!isAircraft(aircraft)) return true; // 取得不能は無限扱い
        try {
            java.lang.reflect.Method m = aircraft.getClass().getMethod("isInfiniteFuel");
            Object result = m.invoke(aircraft);
            if (result instanceof Boolean) return (Boolean) result;
        } catch (Exception ignored) {}
        for (String name : new String[]{"infiniteFuel", "fuelInfinite", "isInfiniteFuel"}) {
            Object v = getFieldValue(aircraft, name);
            if (v instanceof Boolean) return (Boolean) v;
        }
        // maxFuel が 0 以下なら無限扱い
        double max = getMaxFuel(aircraft);
        return max <= 0;
    }

    // ─── 武装 ────────────────────────────────────────────────────────────────

    /**
     * 機体に搭載されている武装種別文字列のリストを返す。
     * McHeli CE の weaponList / weaponInfoList を反射で読み取る。
     */
    @SuppressWarnings("unchecked")
    public static java.util.List<String> getWeaponTypes(Entity aircraft) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (!isAircraft(aircraft)) return result;
        try {
            // 武器リストフィールド候補
            for (String listField : new String[]{"weaponList", "weaponInfoList", "weapons"}) {
                Object wList = getFieldValue(aircraft, listField);
                if (wList instanceof java.util.List) {
                    for (Object wi : (java.util.List<?>) wList) {
                        if (wi == null) continue;
                        // 武器情報オブジェクトの "type" フィールドを読む
                        for (String typeField : new String[]{"type", "weaponType", "name"}) {
                            Object t = getFieldValue(wi, typeField);
                            if (t instanceof String && !((String) t).isEmpty()) {
                                result.add(((String) t).toLowerCase());
                                break;
                            }
                        }
                    }
                    if (!result.isEmpty()) return result;
                }
            }
        } catch (Exception e) {
            McHeliWingman.logger.debug("getWeaponTypes failed: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 機体が指定した武装種別を搭載しているか。
     * weaponType が null/empty なら常に true（制限なし）。
     */
    public static boolean hasWeapon(Entity aircraft, String weaponType) {
        if (weaponType == null || weaponType.isEmpty()) return true;
        java.util.List<String> types = getWeaponTypes(aircraft);
        if (types.isEmpty()) return true; // 読み取れなければ制限なし
        for (String t : types) {
            if (t.equalsIgnoreCase(weaponType)) return true;
        }
        return false;
    }

    /**
     * 機体の燃料を満タンにする。
     * currentFuel / fuel / fuelAmount フィールドに maxFuel 値を書き込む。
     * 無限燃料設定の場合は何もしない。
     * float フィールドと double フィールドの両方に対応する。
     */
    public static void fillFuel(Entity aircraft) {
        if (!isAircraft(aircraft)) return;
        if (hasInfiniteFuel(aircraft)) return;
        double max = getMaxFuel(aircraft);
        if (max <= 0) return;
        for (String name : new String[]{"currentFuel", "fuel", "fuelAmount"}) {
            // まず現在値を読んでフィールドの存在・型を確認する
            Object current = getFieldValue(aircraft, name);
            if (!(current instanceof Number)) continue;
            Field f = getField(aircraft.getClass(), name);
            if (f == null) continue;
            try {
                Class<?> type = f.getType();
                if (type == float.class || type == Float.class) {
                    f.setFloat(aircraft, (float) max);
                } else if (type == int.class || type == Integer.class) {
                    f.setInt(aircraft, (int) max);
                } else if (type == long.class || type == Long.class) {
                    f.setLong(aircraft, (long) max);
                } else {
                    f.setDouble(aircraft, max);
                }
                McHeliWingman.logger.info("fillFuel: {} {} -> {} (type={}, ac={})",
                    name, ((Number) current).doubleValue(), max,
                    type.getSimpleName(), aircraft.getClass().getSimpleName());
                return;
            } catch (IllegalAccessException | IllegalArgumentException e) {
                McHeliWingman.logger.warn("fillFuel: cannot write {}: {}", name, e.getMessage());
            }
        }
        McHeliWingman.logger.warn("fillFuel: no writable fuel field found on {}",
            aircraft.getClass().getSimpleName());
    }

    /**
     * Sets the value of a field on an object.
     * Returns true on success.
     */
    public static boolean setFieldValue(Object obj, String fieldName, Object value) {
        if (obj == null) return false;
        Field f = getField(obj.getClass(), fieldName);
        if (f == null) return false;
        try {
            f.set(obj, value);
            return true;
        } catch (IllegalAccessException e) {
            McHeliWingman.logger.warn("Cannot write field {}: {}", fieldName, e.getMessage());
            return false;
        }
    }

    // ─── 機種判定 ─────────────────────────────────────────────────────────────

    /**
     * 機体がヘリコプターかどうかを返す。
     * McHeli CE の getKindName() 戻り値が "heli"/"helicopter"/"rotor"/"chopper" を含む場合にヘリ判定。
     * （MCH_EntityHeli の getKindName() は "helicopter" を返す可能性があるため contains で判定）
     */
    public static boolean isHelicopter(Entity aircraft) {
        if (!isAircraft(aircraft)) return false;
        try {
            java.lang.reflect.Method m = aircraft.getClass().getMethod("getKindName");
            Object result = m.invoke(aircraft);
            if (result instanceof String) {
                String kind = ((String) result).toLowerCase();
                return kind.contains("heli") || kind.contains("rotor") || kind.contains("chopper");
            }
        } catch (Exception e) {
            McHeliWingman.logger.debug("getKindName() failed on {}: {}", aircraft, e.getMessage());
        }
        // フォールバック: クラス名に "heli" が含まれるか
        return aircraft.getClass().getName().toLowerCase().contains("heli");
    }

    /**
     * 機体が VTOL 能力を持つかどうかを返す（ヘリコプター除く固定翼機判定）。
     * McHeli CE の基底クラス MCH_EntityAircraft が getVtolMode() を public 定義しているため、
     * getter の存在だけで判定すると全固定翼機が VTOL 扱いになってしまう。
     * → getter (getVtolMode) と VTOL 制御メソッド (swithVtolMode / switchVtolMode) の
     *   両方が存在する場合のみ真の VTOL 機と判定する。
     */
    public static boolean isVtol(Entity aircraft) {
        if (!isAircraft(aircraft)) return false;
        if (isHelicopter(aircraft)) return false;  // ヘリコプターは VTOL 扱いしない
        // getter がなければ確実に非 VTOL
        try {
            aircraft.getClass().getMethod("getVtolMode");
        } catch (NoSuchMethodException e) {
            return false;
        } catch (Exception e) {
            McHeliWingman.logger.debug("isVtol getter check failed on {}: {}", aircraft, e.getMessage());
            return false;
        }
        // VTOL 制御メソッド (swithVtolMode / switchVtolMode) の存在も確認。
        // 基底クラス由来の getter のみを持つ非 VTOL 機を除外するため。
        for (String name : new String[]{"swithVtolMode", "switchVtolMode"}) {
            try { aircraft.getClass().getMethod(name, boolean.class); return true; }
            catch (NoSuchMethodException ignored) {}
            try { aircraft.getClass().getMethod(name); return true; }
            catch (NoSuchMethodException ignored) {}
        }
        return false;
    }

    /**
     * 機体がヘリパッドを使用できるか（ヘリコプターまたは VTOL 機）。
     */
    public static boolean canUseHelipad(Entity aircraft) {
        return isHelicopter(aircraft) || isVtol(aircraft);
    }

    // ─── VSTOL ノズル制御 ─────────────────────────────────────────────────────

    /**
     * VTOL ノズルの回転角度を強制設定する。
     *   0°  = 固定翼モード（前進推力のみ）
     *   45° = VSTOL モード（前進推力 + 揚力の合成）
     *   90° = VTOL モード（垂直推力のみ）
     *
     * McHeli は毎 tick ノズルを目標角へ自動回転させるが、Phase.END で毎 tick 上書きすることで
     * 任意の固定角に保持できる。VSTOL 離陸中（TAKEOFF_ROLL）に 45° を維持し、
     * TAKEOFF_ROLL を抜けた後は上書きをやめて McHeli に 0° へ戻させる。
     *
     * McHeli CE の MCH_EntityPlane#partNozzle (MCH_Parts) の rotation フィールドを操作する。
     * partNozzle が存在しない機種（ヘリ・通常固定翼）では何もしない。
     */
    public static void setNozzleRotation(Entity aircraft, float degrees) {
        if (!isAircraft(aircraft)) return;
        Object partNozzle = getFieldValue(aircraft, "partNozzle");
        if (partNozzle == null) return;  // VTOL ノズルを持たない機種はスキップ
        Field rotField = getField(partNozzle.getClass(), "rotation");
        if (rotField == null) return;
        try {
            rotField.setFloat(partNozzle, degrees);
        } catch (Exception e) {
            McHeliWingman.logger.debug("[VSTOL] setNozzleRotation({}) failed: {}", degrees, e.getMessage());
        }
    }
}
