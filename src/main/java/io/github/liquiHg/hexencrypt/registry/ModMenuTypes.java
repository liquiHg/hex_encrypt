package io.github.liquiHg.hexencrypt.registry;

import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.github.liquiHg.hexencrypt.block.SpellInscriptionTableMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, HexEncrypt.MODID);

    public static final RegistryObject<MenuType<SpellInscriptionTableMenu>>
            SPELL_INSCRIPTION_TABLE = MENUS.register(
            "spell_inscription_table",
            () -> IForgeMenuType.create((windowId, inv, data) ->
                    new SpellInscriptionTableMenu(windowId, inv)));
}