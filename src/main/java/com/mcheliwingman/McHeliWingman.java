package com.mcheliwingman;

import com.mcheliwingman.block.WingmanMarkerBlock;
import com.mcheliwingman.block.WingmanMarkerTileEntity;
import com.mcheliwingman.command.WingmanCommand;
import com.mcheliwingman.config.WingmanConfig;
import com.mcheliwingman.handler.AutonomousFlightHandler;
import com.mcheliwingman.handler.ChunkLoadHandler;
import com.mcheliwingman.handler.RangeOverrideHandler;
import com.mcheliwingman.handler.UavChunkStreamer;
import com.mcheliwingman.handler.WingmanTickHandler;
import com.mcheliwingman.mission.MissionPlan;
import com.mcheliwingman.network.WingmanNetwork;
import net.minecraft.item.ItemBlock;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.Mod.Instance;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.apache.logging.log4j.Logger;

@Mod(
    modid = McHeliWingman.MODID,
    name = McHeliWingman.NAME,
    version = McHeliWingman.VERSION,
    dependencies = "required-after:mcheli"
)
public class McHeliWingman {

    public static final String MODID = "mcheliwingman";
    public static final String NAME = "McHeli Wingman";
    public static final String VERSION = "1.0.0";

    @Instance(MODID)
    public static McHeliWingman instance;

    public static Logger logger;

    public static WingmanMarkerBlock MARKER_BLOCK;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        WingmanConfig.load(event.getSuggestedConfigurationFile());
        MissionPlan.init(event.getSuggestedConfigurationFile().getParentFile());
        logger.info("{} pre-init complete (uavControllerRange={})", NAME, WingmanConfig.uavControllerRange);

        // ブロック登録
        MARKER_BLOCK = new WingmanMarkerBlock();
        ForgeRegistries.BLOCKS.register(MARKER_BLOCK);
        ForgeRegistries.ITEMS.register(
            new ItemBlock(MARKER_BLOCK).setRegistryName(MARKER_BLOCK.getRegistryName()));
        net.minecraftforge.fml.common.registry.GameRegistry.registerTileEntity(
            WingmanMarkerTileEntity.class, "mcheliwingman:wingman_marker_te");

        // McHeli クリエイティブタブに追加（リフレクション）
        tryAddToMcheliTab(MARKER_BLOCK);
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        ChunkLoadHandler chunkLoadHandler = new ChunkLoadHandler();
        ForgeChunkManager.setForcedChunkLoadingCallback(McHeliWingman.instance, chunkLoadHandler);
        MinecraftForge.EVENT_BUS.register(chunkLoadHandler);

        MinecraftForge.EVENT_BUS.register(new RangeOverrideHandler());
        MinecraftForge.EVENT_BUS.register(new UavChunkStreamer());
        MinecraftForge.EVENT_BUS.register(new WingmanTickHandler());
        MinecraftForge.EVENT_BUS.register(new AutonomousFlightHandler());
        WingmanNetwork.register();
        logger.info("{} initialized", NAME);
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new WingmanCommand());
    }

    /** McHeli のクリエイティブタブを取得してブロックを追加する。失敗しても起動は続行。 */
    private static void tryAddToMcheliTab(net.minecraft.block.Block block) {
        try {
            Class<?> cls = Class.forName("mcheli.MCH_CreativeTabs");
            java.lang.reflect.Field tabField = null;
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(null);
                if (val instanceof net.minecraft.creativetab.CreativeTabs) { tabField = f; break; }
            }
            if (tabField != null) {
                net.minecraft.creativetab.CreativeTabs tab =
                    (net.minecraft.creativetab.CreativeTabs) tabField.get(null);
                block.setCreativeTab(tab);
                logger.info("[McHeliWingman] WingmanMarkerBlock added to McHeli creative tab.");
            }
        } catch (Exception e) {
            // McHeliタブが見つからなくてもフォールバックとしてMISCタブに入れる
            block.setCreativeTab(net.minecraft.creativetab.CreativeTabs.MISC);
            logger.info("[McHeliWingman] McHeli tab not found, using MISC tab.");
        }
    }
}
