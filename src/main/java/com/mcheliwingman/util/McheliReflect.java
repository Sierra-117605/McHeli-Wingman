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
    private static final Map<String, Field> fieldCache = new HashMap<>();

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
     */
    public static Field getField(Class<?> cls, String name) {
        String key = cls.getName() + "#" + name;
        return fieldCache.computeIfAbsent(key, k -> {
            Class<?> c = cls;
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            McHeliWingman.logger.warn("Field not found: {}", key);
            return null;
        });
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
}
