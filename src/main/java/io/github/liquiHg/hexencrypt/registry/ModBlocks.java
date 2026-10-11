package io.github.liquiHg.hexencrypt.registry;

import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.github.liquiHg.hexencrypt.block.SpellInscriptionTableBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, HexEncrypt.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, HexEncrypt.MODID);

    /** 泛型改成 Block（原来是 SpellInscriptionTableBlock），避免子类编译状态影响注册表本身。 */
    public static final RegistryObject<Block> SPELL_INSCRIPTION_TABLE =
            BLOCKS.register("spell_analysis_table",
                    () -> new SpellInscriptionTableBlock(
                            BlockBehaviour.Properties.copy(Blocks.DEEPSLATE_BRICKS)
                                    .strength(3.5f)
                                    .noOcclusion()));

    public static final RegistryObject<Item> SPELL_INSCRIPTION_TABLE_ITEM =
            ITEMS.register("spell_analysis_table",
                    () -> new BlockItem(SPELL_INSCRIPTION_TABLE.get(), new Item.Properties()));
}