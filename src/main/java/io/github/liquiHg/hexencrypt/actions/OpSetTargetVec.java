package io.github.liquiHg.hexencrypt.actions;

import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.OperationResult;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.Vec3Iota;
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota;
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import io.github.liquiHg.hexencrypt.util.HexCastTargetOverride;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 从栈上弹出一个 vec3，在它附近搜索最近的生物，设为下一次施法的强制目标。
 *
 * 栈输入：
 *   1. 目标坐标 (Vec3) —— 栈顶
 *
 * 后续施法时，只要玩家还没清除这个 override，ISS 的 preCastTargetHelper
 * 就会被 Mixin 拦截，直接使用这个目标，不再依赖玩家视线。
 */
public class OpSetTargetVec implements Action {

    /** 在指定坐标周围多大的半径内找生物（方块）。 */
    private static final double SEARCH_RADIUS = 3.0;

    @Override
    public @NotNull OperationResult operate(@NotNull CastingEnvironment env,
                                            @NotNull CastingImage image,
                                            @NotNull SpellContinuation continuation) {
        var stack = image.getStack();

        if (stack.isEmpty()) {
            throw new MishapNotEnoughArgs(1, 0);
        }

        Iota top = stack.remove(stack.size() - 1);
        if (!(top instanceof Vec3Iota vecIota)) {
            stack.add(top);
            throw MishapInvalidIota.ofType(top, 0, "vec");
        }

        Vec3 targetPos = vecIota.getVec3();

        if (!(env.getCastingEntity() instanceof ServerPlayer player)) {
            stack.add(top);
            throw new MishapCastFailed("施法者必须是一名服务端玩家");
        }

        if (!(player.level() instanceof ServerLevel level)) {
            stack.add(top);
            throw new MishapCastFailed("施法必须在服务端世界进行");
        }

        // 在目标点附近找最近的 LivingEntity
        AABB box = new AABB(targetPos, targetPos).inflate(SEARCH_RADIUS);
        LivingEntity best = null;
        double bestDistSqr = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
            double d = e.position().distanceToSqr(targetPos);
            if (d < bestDistSqr) {
                bestDistSqr = d;
                best = e;
            }
        }

        if (best == null) {
            stack.add(top);
            throw new MishapCastFailed("指定坐标附近没有可瞄准的生物");
        }

        HexCastTargetOverride.set(player, best);
        return new OperationResult(image, List.of(), continuation, HexEvalSounds.NORMAL_EXECUTE);
    }
}