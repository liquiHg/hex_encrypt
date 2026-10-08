package io.github.liquiHg.hexencrypt.actions;

import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.OperationResult;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import io.github.liquiHg.hexencrypt.util.HexCastTargetOverride;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 清除当前玩家的强制准星目标，恢复默认的视线射线检测。
 * 无栈输入。
 */
public class OpClearTarget implements Action {

    @Override
    public @NotNull OperationResult operate(@NotNull CastingEnvironment env,
                                            @NotNull CastingImage image,
                                            @NotNull SpellContinuation continuation) {
        if (env.getCastingEntity() instanceof ServerPlayer player) {
            HexCastTargetOverride.clear(player);
        }
        return new OperationResult(image, List.of(), continuation, HexEvalSounds.NORMAL_EXECUTE);
    }
}