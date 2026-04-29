package com.mcheliwingman;

import com.mcheliwingman.block.WingmanMarkerBlock;
import com.mcheliwingman.block.WingmanMarkerTileEntity;
import com.mcheliwingman.client.WingmanGuiHandler;
import com.mcheliwingman.command.WingmanCommand;
import com.mcheliwingman.config.WingmanConfig;
import com.mcheliwingman.handler.AutonomousFlightHandler;
import com.mcheliwingman.handler.ChunkLoadHandler;
import com.mcheliwingman.handler.RangeOverrideHandler;
import com.mcheliwingman.handler.UavChunkStreamer;
import com.mcheliwingman.client.WingmanKeyHandler;
import com.mcheliwingman.handler.WingmanTickHandler;
import com.mcheliwingman.mission.MissionPlan;
import com.mcheliwingman.network.WingmanNetwork;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraft.item.ItemBlock;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.Mod.Instance;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
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

        // アイテムモデル登録（クライアントのみ）
        if (FMLLaunchHandler.side() == Side.CLIENT) {
            net.minecraftforge.client.model.ModelLoader.setCustomModelResourceLocation(
                net.minecraft.item.Item.getItemFromBlock(MARKER_BLOCK), 0,
                new net.minecraft.client.renderer.block.model.ModelResourceLocation(
                    "mcheliwingman:wingman_marker", "inventory"));
        }
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
        // McHeli クリエイティブタブに追加（init フェーズ: 全 Mod の preInit 完了後）
        tryAddToMcheliTab(MARKER_BLOCK);
        // クライアント専用: キーバインド登録 + HUD ヒント描画
        if (FMLLaunchHandler.side() == Side.CLIENT) {
            WingmanKeyHandler.registerClient();
            MinecraftForge.EVENT_BUS.register(new WingmanKeyHandler());
            MinecraftForge.EVENT_BUS.register(new com.mcheliwingman.handler.ClientAutopilotHandler());
        }
        NetworkRegistry.INSTANCE.registerGuiHandler(instance, new WingmanGuiHandler());
        logger.info("{} initialized", NAME);
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new WingmanCommand());
    }

    /** McHeli のクリエイティブタブを取得してブロックを追加する。失敗しても起動は続行。 */
    private static void tryAddToMcheliTab(net.minecraft.block.Block block) {
        // 1) 登録済み全タブからラベルに "mcheli" を含むものを探す（最も確実）
        for (net.minecraft.creativetab.CreativeTabs tab : net.minecraft.creativetab.CreativeTabs.CREATIVE_TAB_ARRAY) {
            if (tab == null) continue;
            try {
                String label = tab.getTabLabel();
                if (label != null && label.toLowerCase().contains("mcheli")) {
                    block.setCreativeTab(tab);
                    logger.info("[McHeliWingman] Added to McHeli tab '{}'.", label);
                    return;
                }
            } catch (Exception ignored) {}
        }
        // 2) リフレクションで MCH_CreativeTabs クラスの static フィールドを探す
        for (String cls : new String[]{"mcheli.MCH_CreativeTabs", "mcheli.MCH_CreativeTab", "mcheli.McHeli"}) {
            try {
                Class<?> c = Class.forName(cls);
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val instanceof net.minecraft.creativetab.CreativeTabs) {
                        block.setCreativeTab((net.minecraft.creativetab.CreativeTabs) val);
                        logger.info("[McHeliWingman] Added to McHeli tab via {}.{}.", cls, f.getName());
                        return;
                    }
                }
            } catch (Exception ignored) {}
        }
        block.setCreativeTab(net.minecraft.creativetab.CreativeTabs.MISC);
        logger.info("[McHeliWingman] McHeli tab not found, using MISC tab.");
    }
}
