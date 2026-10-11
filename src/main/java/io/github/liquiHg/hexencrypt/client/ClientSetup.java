package io.github.liquiHg.hexencrypt.client;

import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.github.liquiHg.hexencrypt.client.render.SpellInscriptionTableRenderer;
import io.github.liquiHg.hexencrypt.client.screen.SpellInscriptionTableScreen;
import io.github.liquiHg.hexencrypt.registry.ModBlockEntities;
import io.github.liquiHg.hexencrypt.registry.ModMenuTypes;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(
        modid = HexEncrypt.MODID,
        bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT
)
public class ClientSetup {

    /**
     * 1.20.1 / Forge 47.x：MenuScreen 通过 MenuScreens.register 注册。
     * RegisterMenuScreensEvent 是 1.20.2+ 才有的类，1.20.1 用不了。
     *
     * FMLClientSetupEvent 已经跑在客户端物理侧，可以直接调用客户端类，
     * 不需要再包 DistExecutor。
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(
                    ModMenuTypes.SPELL_INSCRIPTION_TABLE.get(),
                    SpellInscriptionTableScreen::new
            );
            // 之后有其他 GUI 一并在这里 add
        });
    }

    /** BER 注册沿用原方案，1.20.1 直接可用。 */
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                ModBlockEntities.SPELL_INSCRIPTION_TABLE.get(),
                SpellInscriptionTableRenderer::new
        );
    }
}