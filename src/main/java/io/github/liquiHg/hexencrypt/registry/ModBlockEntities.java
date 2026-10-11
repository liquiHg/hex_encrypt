package io.github.liquiHg.hexencrypt.registry;

import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.github.liquiHg.hexencrypt.block.SpellInscriptionTableBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, HexEncrypt.MODID);

    public static final RegistryObject<BlockEntityType<SpellInscriptionTableBlockEntity>>
            SPELL_INSCRIPTION_TABLE = BLOCK_ENTITIES.register(
            "spell_analysis_table",
            () -> BlockEntityType.Builder.of(
                    SpellInscriptionTableBlockEntity::new,
                    ModBlocks.SPELL_INSCRIPTION_TABLE.get()
            ).build(null));
}