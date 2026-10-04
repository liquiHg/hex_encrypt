package io.github.liquiHg.hexencrypt;

import io.github.liquiHg.hexencrypt.pigment.IssPigmentItem;
import io.github.liquiHg.hexencrypt.pigment.IssSchool;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, HexEncrypt.MODID);

    /** 9 种染色剂的 RegistryObject，按学派索引。 */
    public static final Map<IssSchool, RegistryObject<Item>> PIGMENTS =
            new EnumMap<>(IssSchool.class);

    static {
        for (IssSchool school : IssSchool.values()) {
            RegistryObject<Item> reg = ITEMS.register(
                    school.id + "_pigment",
                    () -> new IssPigmentItem(school,
                            new Item.Properties().stacksTo(1))
            );
            PIGMENTS.put(school, reg);
        }
    }
}