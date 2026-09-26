package io.github.liquiHg.hexencrypt;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = HexEncrypt.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue ENABLE_ENCRYPTION = BUILDER
            .comment("Whether to enable encryption for Iron's Spells scrolls.")
            .define("enableEncryption", true);

    public static final ForgeConfigSpec.IntValue UNLOCK_COST = BUILDER
            .comment("Amount of media consumed per spell unlock. 0 = no cost.")
            .defineInRange("unlockCost", 1000, 0, Integer.MAX_VALUE);

    public static final ForgeConfigSpec.BooleanValue DEBUG_MODE = BUILDER
            .comment("Enable debug logging.")
            .define("debugMode", false);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static boolean enableEncryption;
    public static int unlockCost;
    public static boolean debugMode;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        enableEncryption = ENABLE_ENCRYPTION.get();
        unlockCost = UNLOCK_COST.get();
        debugMode = DEBUG_MODE.get();
    }
}