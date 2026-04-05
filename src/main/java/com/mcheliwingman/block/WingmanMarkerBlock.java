package com.mcheliwingman.block;

import com.mcheliwingman.registry.MarkerRegistry;
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
        if (te == null) return true;

        if (player.isSneaking()) {
            // Shift+右クリック: モード切り替え
            MarkerType next = te.getMarkerType().next();
            te.setMarkerType(next);
            // レジストリ更新
            MarkerRegistry.register(world, pos, te);
            player.sendMessage(new TextComponentString(
                "§7Marker mode: " + next.displayName()
                + " §7id=§f" + (te.getMarkerId().isEmpty() ? "(none)" : te.getMarkerId())));
        } else {
            // 右クリック: 現在の情報表示
            player.sendMessage(new TextComponentString(
                "§7Wingman Marker — " + te.getMarkerType().displayName()
                + " §7id=§f" + (te.getMarkerId().isEmpty() ? "(none — use /wingman marker id <id>)" : te.getMarkerId())
                + " §7pos=§f" + pos.getX() + "," + pos.getY() + "," + pos.getZ()));
        }
        return true;
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

    private static String autoId(World world, MarkerType type) {
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
