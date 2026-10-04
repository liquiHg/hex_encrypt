package io.github.liquiHg.hexencrypt.actions;

import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.common.lib.HexRegistries;
import io.github.liquiHg.hexencrypt.HexEncrypt;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModActions {
    public static final DeferredRegister<ActionRegistryEntry> ACTIONS =
            DeferredRegister.create(HexRegistries.ACTION.location(), HexEncrypt.MODID);

    // 1. INSTANT - 火焰弹
    public static final RegistryObject<ActionRegistryEntry> ISS_FIREBOLT = ACTIONS.register("iss_firebolt",
            () -> new ActionRegistryEntry(
                    HexPattern.fromAngles("aqawq", HexDir.NORTH_EAST),
                    new OpCastIronsSpell(new ResourceLocation("irons_spellbooks", "firebolt"))
            )
    );

    // 2. LONG - 火球术
    public static final RegistryObject<ActionRegistryEntry> ISS_FIREBALL = ACTIONS.register("iss_fireball",
            () -> new ActionRegistryEntry(
                    HexPattern.fromAngles("aqawa", HexDir.NORTH_EAST),
                    new OpCastIronsSpell(new ResourceLocation("irons_spellbooks", "fireball"))
            )
    );

    // 3. CONTINUOUS - 火焰吐息 (这里必须用 OpCastContinuousIronsSpell)
    public static final RegistryObject<ActionRegistryEntry> ISS_FIRE_BREATH = ACTIONS.register("iss_fire_breath",
            () -> new ActionRegistryEntry(
                    HexPattern.fromAngles("aqawe", HexDir.NORTH_EAST),
                    new OpCastContinuousIronsSpell(new ResourceLocation("irons_spellbooks", "fire_breath"))
            )
    );

    // 4. 多段施法 - 邪术冲击波
    public static final RegistryObject<ActionRegistryEntry> ISS_ELDRITCH_BLAST = ACTIONS.register("iss_eldritch_blast",
            () -> new ActionRegistryEntry(
                    HexPattern.fromAngles("aqawd", HexDir.NORTH_EAST),
                    new OpCastIronsSpell(new ResourceLocation("irons_spellbooks", "eldritch_blast"))
            )
    );
}