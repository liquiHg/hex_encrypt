package io.github.liquiHg.hexencrypt;

import com.mojang.logging.LogUtils;
import io.github.liquiHg.hexencrypt.integration.ISSHexConfig;
import io.github.liquiHg.hexencrypt.network.ModNetwork;
import io.github.liquiHg.hexencrypt.registry.ModBlockEntities;
import io.github.liquiHg.hexencrypt.registry.ModBlocks;
import io.github.liquiHg.hexencrypt.registry.ModMenuTypes;
import io.github.liquiHg.hexencrypt.util.HexCastTargetOverride;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
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
    public static final Logger LOGGER = LogUtils.getLogger();

    public HexEncrypt() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // 新增：ISS 学派映射配置
        ModLoadingContext.get().registerConfig(
                ModConfig.Type.COMMON,
                ISSHexConfig.SPEC,
                "hexencrypt-iss-schools.toml"
        );

        ModItems.ITEMS.register(modEventBus);
        // 移除 ModActions.ACTIONS.register(modEventBus);

        ModBlocks.BLOCKS.register(FMLJavaModLoadingContext.get().getModEventBus());
        ModBlocks.ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
        ModBlockEntities.BLOCK_ENTITIES.register(FMLJavaModLoadingContext.get().getModEventBus());
        ModMenuTypes.MENUS.register(FMLJavaModLoadingContext.get().getModEventBus());

    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(ModNetwork::register);
        LOGGER.info("Hex Encryption common setup");
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            HexCastTargetOverride.clear(sp);
        }
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            String saved = sp.getPersistentData().getString("hexencrypt:timbre");
            if (saved == null || saved.isEmpty()) saved = "default";
            ModNetwork.sendTimbre(sp, saved);
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Hex Encryption server starting");
    }
}