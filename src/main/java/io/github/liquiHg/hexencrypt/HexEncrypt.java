package io.github.liquiHg.hexencrypt;

import com.mojang.logging.LogUtils;
import io.github.liquiHg.hexencrypt.network.ModNetwork;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(HexEncrypt.MODID)
public class HexEncrypt {
    public static final String MODID = "hexencrypt";
    private static final Logger LOGGER = LogUtils.getLogger();

    public HexEncrypt() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(ModNetwork::register);
        LOGGER.info("Hex Encryption common setup");
        if (Config.debugMode) {
            LOGGER.info("Debug mode is ON");
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Hex Encryption server starting");
    }
}