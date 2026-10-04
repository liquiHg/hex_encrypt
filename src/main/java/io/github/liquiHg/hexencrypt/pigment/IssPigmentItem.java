package io.github.liquiHg.hexencrypt.pigment;

import at.petrak.hexcasting.api.item.PigmentItem;
import at.petrak.hexcasting.api.pigment.ColorProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class IssPigmentItem extends Item implements PigmentItem {

    private final int color;
    private final ColorProvider colorProvider;

    public IssPigmentItem(IssSchool school, Properties properties) {
        super(properties);
        this.color = school.color;
        this.colorProvider = new FixedColorProvider(school.color);
    }

    @Override
    public @NotNull ColorProvider provideColor(@NotNull ItemStack stack, @NotNull UUID owner) {
        return this.colorProvider;
    }

    /**
     * 固定颜色的 ColorProvider。
     * 重写了 getRawColor，返回 RGB 三元组（alpha 由父类 getColor 自动加 0xFF）。
     */
    private static class FixedColorProvider extends ColorProvider {
        private final int color;

        FixedColorProvider(int color) {
            this.color = color;
        }

        @Override
        protected int getRawColor(float time, Vec3 position) {
            return this.color & 0xFFFFFF; // 去掉 alpha，只保留 RGB
        }
    }
}