package com.mcheliwingman.handler;

/**
 * P0: UAV flight range override.
 *
 * The actual range patch is applied at the bytecode level by
 * WingmanTransformer (CoreMod), which replaces the hardcoded
 * rangeSq constants in MCH_EntityAircraft#updateServerUavStation().
 *
 * This class is kept as a placeholder for future event-based
 * range logic (e.g., per-config soft limit enforcement).
 */
public class RangeOverrideHandler {
    // Intentionally empty -- see WingmanTransformer for the actual patch.
}
