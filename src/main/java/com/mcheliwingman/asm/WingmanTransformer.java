package com.mcheliwingman.asm;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.InsnNode;

/**
 * Bytecode transformer for McHeli internals.
 *
 * Patches applied:
 *
 * 1. MCH_EntityAircraft#updateUAV()
 *    - Hardcoded rangeSq = 15129.0 (~123 blocks) kills the UAV when it's far
 *      from the UAV station. Replace with Double.MAX_VALUE to disable the limit.
 *
 * 2. MCH_EntityUavStation#searchLastControlAircraft()
 *    - After a world save/reload, the station searches within 120 blocks for
 *      the last controlled aircraft. If the aircraft is further away it can't
 *      be found and the station stays broken.
 *    - Replace 120.0 with 30000.0 (effectively unlimited for normal worlds).
 *
 * NOTE: Targets McHeli 1.1.4. May break on McHeli updates.
 */
public class WingmanTransformer implements IClassTransformer {

    private static final String TARGET_AIRCRAFT      = "mcheli.aircraft.MCH_EntityAircraft";
    private static final String TARGET_STATION       = "mcheli.uav.MCH_EntityUavStation";
    private static final String TARGET_TRACKER_ENTRY = "net.minecraft.entity.EntityTrackerEntry";

    // MCH_EntityAircraft: rangeSq constants
    private static final double AIRCRAFT_RANGE_DEFAULT = 15129.0; // ~123 blocks squared
    private static final double AIRCRAFT_RANGE_SMALL   = 2500.0;  // ~50 blocks squared

    // MCH_EntityUavStation: searchLastControlAircraft AABB expand
    private static final double STATION_SEARCH_RANGE = 120.0;
    private static final double STATION_SEARCH_RANGE_NEW = 30000.0; // ~30000 block radius

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (transformedName.equals(TARGET_AIRCRAFT)) {
            System.out.println("[McHeliWingman] Patching " + TARGET_AIRCRAFT + " ...");
            return patchAircraft(basicClass);
        }
        if (transformedName.equals(TARGET_STATION)) {
            System.out.println("[McHeliWingman] Patching " + TARGET_STATION + " ...");
            byte[] patched = patchStation(basicClass);
            patched = patchStationDisconnect(patched);
            return patched;
        }
        if (transformedName.equals(TARGET_TRACKER_ENTRY)) {
            System.out.println("[McHeliWingman] Patching " + TARGET_TRACKER_ENTRY + " ...");
            return patchTrackerEntry(basicClass);
        }
        return basicClass;
    }

    // -------------------------------------------------------------------------
    // MCH_EntityAircraft: replace rangeSq constants with MAX_VALUE
    // -------------------------------------------------------------------------

    private byte[] patchAircraft(byte[] classData) {
        ClassReader cr = new ClassReader(classData);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        int patchCount = 0;

        for (MethodNode method : cn.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof LdcInsnNode)) continue;
                LdcInsnNode ldc = (LdcInsnNode) insn;
                if (!(ldc.cst instanceof Double)) continue;

                double val = (Double) ldc.cst;
                if (val == AIRCRAFT_RANGE_DEFAULT || val == AIRCRAFT_RANGE_SMALL) {
                    method.instructions.set(insn, new LdcInsnNode(Double.MAX_VALUE));
                    System.out.println("[McHeliWingman]   Replaced rangeSq " + val
                            + " -> MAX_VALUE in method: " + method.name + method.desc);
                    patchCount++;
                }
            }
        }

        if (patchCount == 0) {
            System.err.println("[McHeliWingman] WARNING: No range constants found in "
                    + TARGET_AIRCRAFT + ". McHeli version mismatch?");
        } else {
            System.out.println("[McHeliWingman] Aircraft patch complete. "
                    + patchCount + " constant(s) replaced.");
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    // -------------------------------------------------------------------------
    // MCH_EntityUavStation: replace 120.0 search radius in searchLastControlAircraft
    // -------------------------------------------------------------------------

    private byte[] patchStation(byte[] classData) {
        ClassReader cr = new ClassReader(classData);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        int patchCount = 0;

        for (MethodNode method : cn.methods) {
            if (!method.name.equals("searchLastControlAircraft")) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof LdcInsnNode)) continue;
                LdcInsnNode ldc = (LdcInsnNode) insn;
                if (!(ldc.cst instanceof Double)) continue;

                double val = (Double) ldc.cst;
                if (val == STATION_SEARCH_RANGE) {
                    method.instructions.set(insn, new LdcInsnNode(STATION_SEARCH_RANGE_NEW));
                    System.out.println("[McHeliWingman]   Replaced search range "
                            + val + " -> " + STATION_SEARCH_RANGE_NEW
                            + " in method: " + method.name + method.desc);
                    patchCount++;
                }
            }
        }

        if (patchCount == 0) {
            System.err.println("[McHeliWingman] WARNING: No 120.0 search range found in "
                    + TARGET_STATION + "#searchLastControlAircraft. McHeli version mismatch?");
        } else {
            System.out.println("[McHeliWingman] Station patch complete. "
                    + patchCount + " constant(s) replaced.");
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    // -------------------------------------------------------------------------
    // MCH_EntityUavStation: prevent client from clearing controlAircraft when
    // the aircraft entity is marked dead due to chunk unloading.
    //
    // func_70071_h_() contains (around offset 194-199):
    //   ifeq label202              // skip if NOT dead
    //   aload_0                    // ← inject isRemote guard here
    //   aconst_null
    //   invokevirtual setControlAircract
    //
    // We insert before the aload_0:
    //   aload_0
    //   getfield field_70170_p     // this.world
    //   getfield field_72995_K     // world.isRemote
    //   ifne label202              // if client, skip the null-set
    //
    // Effect: the server still clears controlAircraft when the aircraft truly
    // dies; the client keeps its reference alive so MCH_ClientHeliTickHandler
    // does not snap the camera back to the player.
    // -------------------------------------------------------------------------

    private static final String STATION_CLASS_INTERNAL = "mcheli/uav/MCH_EntityUavStation";
    private static final String WORLD_CLASS_INTERNAL    = "net/minecraft/world/World";
    private static final String FIELD_WORLD             = "field_70170_p"; // Entity.world
    private static final String FIELD_IS_REMOTE         = "field_72995_K"; // World.isRemote

    private byte[] patchStationDisconnect(byte[] classData) {
        ClassReader cr = new ClassReader(classData);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        int patchCount = 0;

        for (MethodNode method : cn.methods) {
            if (!method.name.equals("func_70071_h_")) continue;

            AbstractInsnNode[] arr = method.instructions.toArray();
            for (AbstractInsnNode insn : arr) {
                // Target: INVOKEVIRTUAL setControlAircract
                if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                MethodInsnNode min = (MethodInsnNode) insn;
                if (!min.name.equals("setControlAircract")) continue;

                // Walk backwards over meta-nodes to find real instructions.
                AbstractInsnNode aconstNull = prevReal(insn);
                if (aconstNull == null || aconstNull.getOpcode() != Opcodes.ACONST_NULL) continue;

                AbstractInsnNode aload0 = prevReal(aconstNull);
                if (aload0 == null || aload0.getOpcode() != Opcodes.ALOAD) continue;
                if (((VarInsnNode) aload0).var != 0) continue;

                // The IFEQ before aload_0 must exist and carry the skip target.
                AbstractInsnNode ifeqNode = prevReal(aload0);
                if (ifeqNode == null || ifeqNode.getOpcode() != Opcodes.IFEQ) continue;
                LabelNode skipLabel = ((JumpInsnNode) ifeqNode).label;

                // Build injection: if (this.world.isRemote) goto skipLabel
                InsnList inject = new InsnList();
                inject.add(new VarInsnNode(Opcodes.ALOAD, 0));
                inject.add(new FieldInsnNode(Opcodes.GETFIELD,
                        STATION_CLASS_INTERNAL, FIELD_WORLD,
                        "Lnet/minecraft/world/World;"));
                inject.add(new FieldInsnNode(Opcodes.GETFIELD,
                        WORLD_CLASS_INTERNAL, FIELD_IS_REMOTE, "Z"));
                inject.add(new JumpInsnNode(Opcodes.IFNE, skipLabel));

                method.instructions.insertBefore(aload0, inject);
                patchCount++;
                System.out.println("[McHeliWingman]   Injected isRemote guard before "
                        + "setControlAircract(null) in " + method.name + method.desc);
                break; // one injection per method is sufficient
            }
            break; // only one func_70071_h_ exists
        }

        if (patchCount == 0) {
            System.err.println("[McHeliWingman] WARNING: Could not find "
                    + "setControlAircract(null) pattern in func_70071_h_. "
                    + "McHeli version mismatch?");
        } else {
            System.out.println("[McHeliWingman] Station disconnect patch applied.");
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    // -------------------------------------------------------------------------
    // EntityTrackerEntry: make isVisibleTo always return true for UAV aircraft
    //
    // func_180233_c(EntityPlayerMP)Z computes:
    //   int i = Math.min(field_73130_b, field_187262_f);
    //   return dist_x <= i && dist_z <= i && ...;
    //
    // We inject at the very start:
    //   if (WingmanUavRegistry.isUavControlled(this.field_73132_a)) return true;
    //
    // This prevents SPacketDestroyEntities from ever being sent for actively
    // controlled UAV aircraft, regardless of distance.
    // -------------------------------------------------------------------------

    private byte[] patchTrackerEntry(byte[] classData) {
        ClassReader cr = new ClassReader(classData);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        int patchCount = 0;

        for (MethodNode mn : cn.methods) {
            // Find the "isVisibleTo" method by content, not just name.
            // SRG name is func_180233_c in 14.23.5.2847 but may differ in other builds.
            // Strategy: find any boolean method with an EntityPlayerMP parameter that
            // reads field_73130_b (trackingRange) — that is the distance check method.
            if (!mn.desc.endsWith(")Z")) continue; // must return boolean

            // isVisibleTo calls Math.min(int,int) to combine the two range limits.
            // This is the only boolean method in EntityTrackerEntry that does so.
            // Using Math.min as a fingerprint avoids relying on obfuscated field names.
            boolean hasMathMin = false;
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode min = (MethodInsnNode) insn;
                    if ("java/lang/Math".equals(min.owner) && "min".equals(min.name)) {
                        hasMathMin = true;
                        break;
                    }
                }
            }
            if (!hasMathMin) continue;

            System.out.println("[McHeliWingman]   Found isVisibleTo method: "
                    + mn.name + mn.desc);

            // Inject: if (WingmanUavRegistry.isUavControlled(this.field_73132_a)) return true;
            InsnList inject = new InsnList();
            // Load this
            inject.add(new VarInsnNode(Opcodes.ALOAD, 0));
            // Get this.field_73132_a  (Entity trackedEntity)
            inject.add(new FieldInsnNode(Opcodes.GETFIELD,
                    "net/minecraft/entity/EntityTrackerEntry",
                    "field_73132_a",
                    "Lnet/minecraft/entity/Entity;"));
            // Call WingmanUavRegistry.isUavControlled(Entity)Z
            inject.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "com/mcheliwingman/util/WingmanUavRegistry",
                    "isUavControlled",
                    "(Lnet/minecraft/entity/Entity;)Z",
                    false));
            // If false (not UAV controlled), skip to original code
            LabelNode skip = new LabelNode();
            inject.add(new JumpInsnNode(Opcodes.IFEQ, skip));
            // Return true
            inject.add(new InsnNode(Opcodes.ICONST_1));
            inject.add(new InsnNode(Opcodes.IRETURN));
            inject.add(skip);

            mn.instructions.insert(inject);
            patchCount++;
            System.out.println("[McHeliWingman]   Injected isUavControlled guard into "
                    + "EntityTrackerEntry.func_180233_c");
            break;
        }

        if (patchCount == 0) {
            System.err.println("[McHeliWingman] WARNING: Could not find "
                    + "EntityTrackerEntry.func_180233_c. Version mismatch?");
        } else {
            System.out.println("[McHeliWingman] EntityTrackerEntry patch applied.");
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Returns the previous non-meta instruction (skips LabelNode, LineNumberNode,
     * FrameNode — these carry no runtime opcode).
     */
    private static AbstractInsnNode prevReal(AbstractInsnNode node) {
        AbstractInsnNode prev = node.getPrevious();
        while (prev != null
                && (prev instanceof LabelNode
                    || prev instanceof LineNumberNode
                    || prev instanceof FrameNode)) {
            prev = prev.getPrevious();
        }
        return prev;
    }
}
