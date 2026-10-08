package io.github.liquiHg.hexencrypt.mixin;

import io.github.liquiHg.hexencrypt.util.HexCastTargetOverride;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.capabilities.magic.TargetEntityCastData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Utils.class, remap = false)
public class UtilsMixin {

    /**
     * 6 参数重载：
     *   preCastTargetHelper(Level, LivingEntity, MagicData, AbstractSpell, int, float)Z
     */
    @Inject(
            method = "preCastTargetHelper(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lio/redspace/ironsspellbooks/api/magic/MagicData;Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;IF)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 1
    )
    private static void hexencrypt$overrideTarget6(
            Level level, LivingEntity caster, MagicData playerMagicData, AbstractSpell spell,
            int range, float aimAssist, CallbackInfoReturnable<Boolean> cir) {
        hexencrypt$tryOverride(caster, playerMagicData, cir);
    }

    /**
     * 7 参数重载：
     *   preCastTargetHelper(Level, LivingEntity, MagicData, AbstractSpell, int, float, boolean)Z
     */
    @Inject(
            method = "preCastTargetHelper(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lio/redspace/ironsspellbooks/api/magic/MagicData;Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;IFZ)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 1
    )
    private static void hexencrypt$overrideTarget7(
            Level level, LivingEntity caster, MagicData playerMagicData, AbstractSpell spell,
            int range, float aimAssist, boolean targetSelf, CallbackInfoReturnable<Boolean> cir) {
        hexencrypt$tryOverride(caster, playerMagicData, cir);
    }

    private static void hexencrypt$tryOverride(LivingEntity caster, MagicData playerMagicData,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (!(caster instanceof ServerPlayer player)) return;
        if (!HexCastTargetOverride.has(player)) return;

        LivingEntity target = HexCastTargetOverride.get(player);
        if (target != null && target.isAlive()) {
            playerMagicData.setAdditionalCastData(new TargetEntityCastData(target));
            // 用完立刻清除，避免之后所有施法都命中同一目标
            HexCastTargetOverride.clear(player);
            cir.setReturnValue(true);
        } else {
            HexCastTargetOverride.clear(player);
        }
    }
}