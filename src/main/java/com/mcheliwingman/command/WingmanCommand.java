package com.mcheliwingman.command;

import com.mcheliwingman.McHeliWingman;
import com.mcheliwingman.config.WingmanConfig;
import com.mcheliwingman.util.McheliReflect;
import com.mcheliwingman.wingman.WingmanEntry;
import com.mcheliwingman.wingman.WingmanRegistry;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * /wingman <subcommand>
 *
 *   follow [uuid]             — assign nearest aircraft (or UUID) as wingman
 *   stop                      — remove all wingmen from your aircraft
 *   status                    — list all active wingmen
 *   dist <side> <alt> <rear>  — set formation distances at runtime
 *   maxwings <n>              — set max wingmen per aircraft
 *   engage [uuid]             — set MANUAL attack on UUID (or player's mount lock-target)
 *   auto                      — set AUTO attack (nearest hostile)
 *   hold                      — stop attacking, return to formation
 *   spawnuav [type]           — spawn UAV; omit type to list
 */
public class WingmanCommand extends CommandBase {

    private static final double SEARCH_RANGE_SQ = 512.0 * 512.0;

    @Override public String getName()                     { return "wingman"; }
    @Override public int    getRequiredPermissionLevel()  { return 0; }
    @Override public String getUsage(ICommandSender s)    {
        return "/wingman <follow|stop|status|dist|maxwings|engage|auto|hold|weapon|spawnuav> [args]";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) {
            sender.sendMessage(new TextComponentString("§cPlayer-only command."));
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        if (args.length == 0) { player.sendMessage(new TextComponentString("§7Usage: " + getUsage(sender))); return; }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "follow":   executeFollow(player, args);    break;
            case "stop":     executeStop(player);             break;
            case "status":   executeStatus(player);           break;
            case "dist":     executeDist(player, args);       break;
            case "maxwings": executeMaxWings(player, args);   break;
            case "engage":   executeEngage(player, args);     break;
            case "auto":     executeAuto(player);             break;
            case "hold":     executeHold(player);             break;
            case "weapon":   executeWeapon(player, args);     break;
            case "spawnuav": executeSpawnUav(player, args);   break;
            default: player.sendMessage(new TextComponentString("§7Usage: " + getUsage(sender)));
        }
    }

    // =========================================================================
    // follow [uuid]
    // =========================================================================

    private void executeFollow(EntityPlayerMP player, String[] args) {
        Entity leader = player.getRidingEntity();
        if (!McheliReflect.isAircraft(leader)) {
            player.sendMessage(new TextComponentString("§cYou must be inside a McHeli aircraft."));
            return;
        }
        int slot = WingmanRegistry.countForLeader(leader);
        if (slot >= WingmanConfig.maxWingmen) {
            player.sendMessage(new TextComponentString("§cMaximum " + WingmanConfig.maxWingmen + " wingmen per aircraft."));
            return;
        }
        Entity wingman;
        if (args.length >= 2) {
            UUID uid = parseUUID(player, args[1]);
            if (uid == null) return;
            wingman = ((WorldServer) player.world).getEntityFromUuid(uid);
            if (wingman == null) { player.sendMessage(new TextComponentString("§cEntity not found: " + args[1])); return; }
        } else {
            wingman = findNearestAircraft(player, leader);
            if (wingman == null) { player.sendMessage(new TextComponentString("§cNo unassigned aircraft found nearby.")); return; }
        }
        if (!McheliReflect.isAircraft(wingman)) { player.sendMessage(new TextComponentString("§cNot a McHeli aircraft.")); return; }
        if (wingman == leader) { player.sendMessage(new TextComponentString("§cCannot follow your own aircraft.")); return; }
        // プレイヤーが乗っている機体（直接またはヒットボックス経由）を除外
        if (wingman.isRidingOrBeingRiddenBy(player)) { player.sendMessage(new TextComponentString("§cCannot assign your own aircraft as wingman.")); return; }

        WingmanRegistry.put(wingman.getUniqueID(), new WingmanEntry(leader, slot));
        player.sendMessage(new TextComponentString("§aWingman assigned (slot " + slot + "): " + shortId(wingman)));
    }

    // =========================================================================
    // stop
    // =========================================================================

    private void executeStop(EntityPlayerMP player) {
        Entity leader = player.getRidingEntity();
        if (!McheliReflect.isAircraft(leader)) { player.sendMessage(new TextComponentString("§cNot inside a McHeli aircraft.")); return; }
        int before = WingmanRegistry.countForLeader(leader);
        WingmanRegistry.removeForLeader(leader);
        player.sendMessage(new TextComponentString("§aStopped " + before + " wingman(s)."));
    }

    // =========================================================================
    // status
    // =========================================================================

    private void executeStatus(EntityPlayerMP player) {
        Map<UUID, WingmanEntry> all = WingmanRegistry.snapshot();
        if (all.isEmpty()) { player.sendMessage(new TextComponentString("§7No active wingmen.")); return; }
        player.sendMessage(new TextComponentString(
                "§e=== Wingman Status (side=" + WingmanConfig.formationSideDist
                + " alt=" + WingmanConfig.formationAltOffset
                + " rear=" + WingmanConfig.formationRearDist + ") ==="));
        for (Map.Entry<UUID, WingmanEntry> e : all.entrySet()) {
            WingmanEntry entry = e.getValue();
            String leader = entry.leader != null ? entry.leader.getClass().getSimpleName() : "none";
            String atk = entry.attackMode != WingmanEntry.ATK_NONE
                    ? " atk=" + entry.attackMode + (entry.manualTargetId != null ? ":" + entry.manualTargetId.toString().substring(0, 8) : "")
                    : "";
            player.sendMessage(new TextComponentString(
                    "§7[" + e.getKey().toString().substring(0, 8) + "...] §f"
                    + "slot=" + entry.formationSlot + " state=" + entry.state
                    + " leader=" + leader + atk));
        }
    }

    // =========================================================================
    // dist <side> <alt> <rear>
    // =========================================================================

    private void executeDist(EntityPlayerMP player, String[] args) {
        if (args.length < 4) {
            player.sendMessage(new TextComponentString(
                    "§7Usage: /wingman dist <side> <altitude> <rear>  (current: side="
                    + WingmanConfig.formationSideDist + " alt=" + WingmanConfig.formationAltOffset
                    + " rear=" + WingmanConfig.formationRearDist + ")"));
            return;
        }
        try {
            double side = Double.parseDouble(args[1]);
            double alt  = Double.parseDouble(args[2]);
            double rear = Double.parseDouble(args[3]);
            if (side < 0 || rear < 0) { player.sendMessage(new TextComponentString("§cSide and rear must be non-negative.")); return; }
            WingmanConfig.formationSideDist = side;
            WingmanConfig.formationAltOffset = alt;
            WingmanConfig.formationRearDist = rear;
            player.sendMessage(new TextComponentString("§aFormation: side=" + side + " alt=" + alt + " rear=" + rear));
        } catch (NumberFormatException ex) {
            player.sendMessage(new TextComponentString("§cInvalid number."));
        }
    }

    // =========================================================================
    // maxwings <n>
    // =========================================================================

    private void executeMaxWings(EntityPlayerMP player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(new TextComponentString("§7Current max: " + WingmanConfig.maxWingmen));
            return;
        }
        try {
            int n = Integer.parseInt(args[1]);
            if (n < 1 || n > 64) { player.sendMessage(new TextComponentString("§cValue must be 1–64.")); return; }
            WingmanConfig.maxWingmen = n;
            player.sendMessage(new TextComponentString("§aMax wingmen set to " + n));
        } catch (NumberFormatException ex) {
            player.sendMessage(new TextComponentString("§cInvalid number."));
        }
    }

    // =========================================================================
    // engage [uuid]  — MANUAL attack
    // =========================================================================

    private void executeEngage(EntityPlayerMP player, String[] args) {
        Entity leader = player.getRidingEntity();
        List<Map.Entry<UUID, WingmanEntry>> wingmen = WingmanRegistry.snapshotForLeader(leader);
        if (wingmen.isEmpty()) { player.sendMessage(new TextComponentString("§cNo wingmen assigned to your aircraft.")); return; }

        UUID targetId = null;
        if (args.length >= 2) {
            targetId = parseUUID(player, args[1]);
            if (targetId == null) return;
        }

        for (Map.Entry<UUID, WingmanEntry> e : wingmen) {
            e.getValue().attackMode = WingmanEntry.ATK_MANUAL;
            e.getValue().manualTargetId = targetId;
        }
        String tStr = targetId != null ? targetId.toString().substring(0, 8) + "..." : "(player lock)";
        player.sendMessage(new TextComponentString("§aEngaging: " + wingmen.size() + " wingman(s) → target=" + tStr));
    }

    // =========================================================================
    // auto  — AUTO attack
    // =========================================================================

    private void executeAuto(EntityPlayerMP player) {
        Entity leader = player.getRidingEntity();
        List<Map.Entry<UUID, WingmanEntry>> wingmen = WingmanRegistry.snapshotForLeader(leader);
        if (wingmen.isEmpty()) { player.sendMessage(new TextComponentString("§cNo wingmen assigned.")); return; }
        for (Map.Entry<UUID, WingmanEntry> e : wingmen) {
            e.getValue().attackMode = WingmanEntry.ATK_AUTO;
            e.getValue().manualTargetId = null;
        }
        player.sendMessage(new TextComponentString("§aAuto-attack enabled for " + wingmen.size() + " wingman(s)."));
    }

    // =========================================================================
    // weapon [type|clear]  — 使用する武器種を指定 (McHeli weaponInfo.type)
    // =========================================================================

    private void executeWeapon(EntityPlayerMP player, String[] args) {
        Entity leader = player.getRidingEntity();
        List<Map.Entry<UUID, WingmanEntry>> wingmen = WingmanRegistry.snapshotForLeader(leader);
        if (wingmen.isEmpty()) { player.sendMessage(new TextComponentString("§cNo wingmen assigned.")); return; }

        if (args.length < 2) {
            String cur = wingmen.get(0).getValue().weaponType;
            player.sendMessage(new TextComponentString(
                "§7Current weapon type: " + (cur != null ? "§e" + cur : "§7(any — first available)")
                + "\n§7Usage: /wingman weapon <type>  or  /wingman weapon clear"));
            return;
        }

        String type = args[1].equalsIgnoreCase("clear") ? null : args[1];
        for (Map.Entry<UUID, WingmanEntry> e : wingmen) {
            e.getValue().weaponType = type;
        }
        if (type == null) {
            player.sendMessage(new TextComponentString("§aWeapon type cleared (any weapon)."));
        } else {
            player.sendMessage(new TextComponentString("§aWeapon type set to §e" + type + "§a for " + wingmen.size() + " wingman(s)."));
        }
    }

    // =========================================================================
    // hold  — stop attacking
    // =========================================================================

    private void executeHold(EntityPlayerMP player) {
        Entity leader = player.getRidingEntity();
        List<Map.Entry<UUID, WingmanEntry>> wingmen = WingmanRegistry.snapshotForLeader(leader);
        if (wingmen.isEmpty()) { player.sendMessage(new TextComponentString("§cNo wingmen assigned.")); return; }
        for (Map.Entry<UUID, WingmanEntry> e : wingmen) {
            e.getValue().attackMode = WingmanEntry.ATK_NONE;
            e.getValue().manualTargetId = null;
        }
        player.sendMessage(new TextComponentString("§aHold — attack stopped for " + wingmen.size() + " wingman(s)."));
    }

    // =========================================================================
    // spawnuav [type]
    // =========================================================================

    private void executeSpawnUav(EntityPlayerMP player, String[] args) {
        if (args.length < 2) { listUavTypes(player); return; }
        spawnUav(player, args[1]);
    }

    private void listUavTypes(EntityPlayerMP player) {
        try {
            List<String> planes = new ArrayList<>(), helis = new ArrayList<>();
            collectUavNames("plane", planes);
            collectUavNames("heli",  helis);
            if (planes.isEmpty() && helis.isEmpty()) {
                player.sendMessage(new TextComponentString("§7No UAV types found.")); return;
            }
            player.sendMessage(new TextComponentString("§eAvailable UAV types (/wingman spawnuav <type>):"));
            for (String n : planes) player.sendMessage(new TextComponentString("§7  [plane] " + n));
            for (String n : helis)  player.sendMessage(new TextComponentString("§7  [heli]  " + n));
        } catch (Exception ex) {
            player.sendMessage(new TextComponentString("§cFailed to list UAV types: " + ex.getMessage()));
        }
    }

    private void collectUavNames(String reg, List<String> out) throws Exception {
        Class<?> cr = Class.forName("mcheli.__helper.info.ContentRegistries");
        Object registry = cr.getMethod(reg).invoke(null);
        List<?> values = (List<?>) registry.getClass().getMethod("values").invoke(registry);
        for (Object info : values) {
            Field isUAV   = findField(info.getClass(), "isUAV");
            Field nameFld = findField(info.getClass(), "name");
            if (isUAV == null || nameFld == null) continue;
            if (!(boolean) isUAV.get(info)) continue;
            out.add((String) nameFld.get(info));
        }
    }

    @SuppressWarnings("unchecked")
    private void spawnUav(EntityPlayerMP player, String typeName) {
        WorldServer ws = (WorldServer) player.world;

        // Spawn 8 blocks in front of the player at eye height
        double yawRad = Math.toRadians(player.rotationYaw);
        double spawnX = player.posX - Math.sin(yawRad) * 8;
        double spawnY = player.posY + 1;
        double spawnZ = player.posZ + Math.cos(yawRad) * 8;

        String[] entityClasses = {
            "mcheli.plane.MCP_EntityPlane",
            "mcheli.helicopter.MCH_EntityHeli"
        };

        for (String className : entityClasses) {
            try {
                Class<?> cls = Class.forName(className);
                Constructor<?> ctor = cls.getConstructor(net.minecraft.world.World.class);
                Entity entity = (Entity) ctor.newInstance(ws);

                // changeType sets acInfo but NOT the ID_TYPE DataParameter
                Method changeType = cls.getMethod("changeType", String.class);
                changeType.invoke(entity, typeName);

                // Check acInfo was actually set (type was found)
                Method getAcInfo = cls.getMethod("getAcInfo");
                Object acInfo = getAcInfo.invoke(entity);
                if (acInfo == null) {
                    // Type not found in this class — try next
                    continue;
                }

                // Set ID_TYPE DataParameter so writeSpawnData sends the correct type name
                // and the client calls changeType(typeName) in readSpawnData → model loads
                try {
                    Field idTypeField = findField(cls, "ID_TYPE");
                    if (idTypeField != null) {
                        DataParameter<String> idType = (DataParameter<String>) idTypeField.get(null);
                        entity.getDataManager().set(idType, typeName);
                    }
                } catch (Exception e) {
                    McHeliWingman.logger.warn("[Wingman] Could not set ID_TYPE: {}", e.getMessage());
                }

                // Set TEXTURE_NAME DataParameter using the first texture in acInfo.textureNameList
                try {
                    Field texListField = findField(acInfo.getClass(), "textureNameList");
                    if (texListField != null) {
                        @SuppressWarnings("unchecked")
                        List<String> texList = (List<String>) texListField.get(acInfo);
                        if (texList != null && !texList.isEmpty()) {
                            Method setTex = cls.getMethod("setTextureName", String.class);
                            setTex.invoke(entity, texList.get(0));
                        }
                    }
                } catch (Exception e) {
                    McHeliWingman.logger.warn("[Wingman] Could not set texture: {}", e.getMessage());
                }

                entity.setLocationAndAngles(spawnX, spawnY, spawnZ, player.rotationYaw, 0);
                ws.spawnEntity(entity);

                Method getKindName = cls.getMethod("getKindName");
                String kindName = (String) getKindName.invoke(entity);
                player.sendMessage(new TextComponentString(
                        "§aSpawned: " + kindName + " (" + entity.getUniqueID().toString().substring(0, 8) + "...)"));
                return;
            } catch (Exception ignored) {}
        }
        player.sendMessage(new TextComponentString(
                "§cCould not spawn \"" + typeName + "\". Use /wingman spawnuav for valid types."));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Entity findNearestAircraft(EntityPlayerMP player, Entity leader) {
        double bestSq = SEARCH_RANGE_SQ;
        Entity best = null;
        for (Entity e : new ArrayList<>(player.world.loadedEntityList)) {
            if (e == leader || e == player) continue;
            if (!McheliReflect.isAircraft(e)) continue;
            if (WingmanRegistry.get(e.getUniqueID()) != null) continue;
            double dSq = leader.getDistanceSq(e);
            if (dSq < bestSq) { bestSq = dSq; best = e; }
        }
        return best;
    }

    private UUID parseUUID(EntityPlayerMP player, String s) {
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException ex) {
            player.sendMessage(new TextComponentString("§cInvalid UUID: " + s));
            return null;
        }
    }

    private static String shortId(Entity e) {
        return e.getUniqueID().toString().substring(0, 8) + "...";
    }

    private static Field findField(Class<?> cls, String name) {
        while (cls != null) {
            try { Field f = cls.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { cls = cls.getSuperclass(); }
        }
        return null;
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, BlockPos pos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args,
                "follow", "stop", "status", "dist", "maxwings", "engage", "auto", "hold", "weapon", "spawnuav");
        if (args.length == 2 && args[0].equalsIgnoreCase("weapon"))
            return getListOfStringsMatchingLastWord(args,
                    // エイリアス（便利名）
                    "gun", "cannon", "missile", "rocket", "bomb", "torpedo",
                    // McHeli実際の型名（Readme_Weapon.txtの Type= 値）
                    "machinegun1", "machinegun2", "cas",
                    "asmissile", "aamissile", "atmissile", "tvmissile",
                    "mkrocket", "targetingpod",
                    "clear");
        return Collections.emptyList();
    }
}
