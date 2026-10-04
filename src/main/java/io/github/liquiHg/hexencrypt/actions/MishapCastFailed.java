package io.github.liquiHg.hexencrypt.actions;

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class MishapCastFailed extends Mishap {
    private final String message;

    public MishapCastFailed(String message) {
        this.message = message;
    }

    @Override
    public FrozenPigment accentColor(CastingEnvironment ctx, Context errorCtx) {
        // 红色错误提示
        return dyeColor(DyeColor.RED);
    }

    @Override
    protected @Nullable Component errorMessage(CastingEnvironment ctx, Context errorCtx) {
        return Component.literal(this.message);
    }

    @Override
    public void execute(CastingEnvironment env, Context errorCtx, List<Iota> stack) {
        // 不需要额外操作，直接抛出即可
    }
}