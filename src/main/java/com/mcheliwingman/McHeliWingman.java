package com.mcheliwingman;

import com.mcheliwingman.command.WingmanCommand;
import com.mcheliwingman.config.WingmanConfig;
import com.mcheliwingman.handler.ChunkLoadHandler;
import com.mcheliwingman.handler.RangeOverrideHandler;
import com.mcheliwingman.handler.UavChunkStreamer;
import com.mcheliwingman.handler.WingmanTickHandler;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.Mod.Instance;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
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

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        WingmanConfig.load(event.getSuggestedConfigurationFile());
        logger.info("{} pre-init complete (uavControllerRange={})", NAME, WingmanConfig.uavControllerRange);
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        // ChunkLoadHandler implements ForgeChunkManager.LoadingCallback.
        // The callback MUST be registered before any world loads; init() is the
        // correct phase for that (preInit is too early for ForgeChunkManager).
        ChunkLoadHandler chunkLoadHandler = new ChunkLoadHandler();
        ForgeChunkManager.setForcedChunkLoadingCallback(McHeliWingman.instance, chunkLoadHandler);
        MinecraftForge.EVENT_BUS.register(chunkLoadHandler);

        MinecraftForge.EVENT_BUS.register(new RangeOverrideHandler());
        MinecraftForge.EVENT_BUS.register(new UavChunkStreamer());
        MinecraftForge.EVENT_BUS.register(new WingmanTickHandler());
        logger.info("{} initialized", NAME);
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new WingmanCommand());
    }
}
