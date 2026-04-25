package com.mcheliwingman.block;

import com.mcheliwingman.registry.MarkerRegistry;
import com.mcheliwingman.registry.TaxiRouteRegistry;
import com.mcheliwingman.mission.TaxiRoute;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

public class WingmanMarkerBlock extends Block {

    public WingmanMarkerBlock() {
        super(Material.IRON);
        setRegistryName("mcheliwingman", "wingman_marker");
        setTranslationKey("wingman_marker");
        setHardness(2.0f);
        setResistance(10.0f);
    }

    @Override
    public boolean hasTileEntity(IBlockState state) { return true; }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new WingmanMarkerTileEntity();
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state,
                                    EntityPlayer player, EnumHand hand,
                                    EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) return true;
        WingmanMarkerTileEntity te = getTe(world, pos);
        if (te == null || !(player instanceof net.minecraft.entity.player.EntityPlayerMP)) return true;
        net.minecraft.entity.player.EntityPlayerMP mp = (net.minecraft.entity.player.EntityPlayerMP) player;

        if (te.getMarkerType() == MarkerType.BASE) {
            // BASE マーカー: 基地コンフィグGUI（タキシールート + ミッションプランナー）
            openBaseGui(world, pos, te, mp);
        } else {
            // その他のマーカー: 通常のマーカー設定GUI
            com.mcheliwingman.network.WingmanNetwork.sendToPlayer(
                new com.mcheliwingman.network.PacketOpenMarkerGui(
                    pos, te.getMarkerType(), te.getMarkerId(), te.getBaseId()),
                mp);
        }
        return true;
    }

    // ─── BASE GUI ─────────────────────────────────────────────────────────────

    private static void openBaseGui(World world, BlockPos pos, WingmanMarkerTileEntity te,
                                    net.minecraft.entity.player.EntityPlayerMP player) {
        String baseId = te.getMarkerId(); // BASE マーカーの ID が基地 ID
        com.mcheliwingman.network.PacketOpenBaseGui pkt =
            new com.mcheliwingman.network.PacketOpenBaseGui(pos, baseId);

        // タキシールートを収集
        for (TaxiRoute r : TaxiRouteRegistry.getForBase(world, baseId)) {
            com.mcheliwingman.network.PacketOpenBaseGui.RouteDto dto =
                new com.mcheliwingman.network.PacketOpenBaseGui.RouteDto();
            dto.routeId     = r.routeId;
            dto.parkingId   = r.parkingId;
            dto.runwayId    = r.runwayId;
            dto.waypointIds.addAll(r.waypointIds);
            pkt.routes.add(dto);
        }

        // 子マーカーを収集（PARKING / WAYPOINT / RUNWAY_A / RUNWAY_B）
        for (MarkerRegistry.MarkerInfo m : MarkerRegistry.findChildren(world, baseId)) {
            com.mcheliwingman.network.PacketOpenBaseGui.MarkerDto dto =
                new com.mcheliwingman.network.PacketOpenBaseGui.MarkerDto();
            dto.id = m.id;
            dto.x  = m.pos.getX();
            dto.y  = m.pos.getY();
            dto.z  = m.pos.getZ();
            if (m.type == MarkerType.PARKING)   pkt.parkingMarkers.add(dto);
            else if (m.type == MarkerType.WAYPOINT) pkt.waypointMarkers.add(dto);
            else if (m.type == MarkerType.RUNWAY_A) pkt.runwayAId = m.id;
            else if (m.type == MarkerType.RUNWAY_B) pkt.runwayBId = m.id;
        }

        com.mcheliwingman.network.WingmanNetwork.sendToPlayer(pkt, player);
    }

    @Override
    public void onBlockAdded(World world, BlockPos pos, IBlockState state) {
        if (!world.isRemote) {
            WingmanMarkerTileEntity te = getTe(world, pos);
            if (te != null) {
                if (te.getMarkerId().isEmpty()) {
                    te.setMarkerId(autoId(world, te.getMarkerType()));
                    te.markDirty();
                }
                MarkerRegistry.register(world, pos, te);
                player_hint(world, pos, te);
            }
        }
    }

    public static String autoId(World world, MarkerType type) {
        String prefix;
        switch (type) {
            case PARKING:  prefix = "p";  break;
            case RUNWAY_A: prefix = "ra"; break;
            case RUNWAY_B: prefix = "rb"; break;
            default:       prefix = "wp"; break;
        }
        int n = MarkerRegistry.findByType(world, type).size() + 1;
        return prefix + "_" + n;
    }

    /** 設置したプレイヤーにIDを通知する（サーバー側でプレイヤーが近くにいれば）。 */
    private static void player_hint(World world, BlockPos pos, WingmanMarkerTileEntity te) {
        for (net.minecraft.entity.player.EntityPlayer p : world.playerEntities) {
            if (p.getDistanceSq(pos.getX(), pos.getY(), pos.getZ()) < 16 * 16) {
                p.sendMessage(new TextComponentString(
                    "§7Marker placed: " + te.getMarkerType().displayName()
                    + " §7id=§e" + te.getMarkerId()
                    + " §7(Shift+click or §f/wingman marker type§7 to change type)"));
                break;
            }
        }
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        if (!world.isRemote) MarkerRegistry.unregister(world, pos);
        super.breakBlock(world, pos, state);
    }

    private WingmanMarkerTileEntity getTe(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof WingmanMarkerTileEntity ? (WingmanMarkerTileEntity) te : null;
    }
}
