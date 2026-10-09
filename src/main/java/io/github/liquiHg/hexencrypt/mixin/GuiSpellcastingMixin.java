package io.github.liquiHg.hexencrypt.mixin;

import at.petrak.hexcasting.api.casting.math.HexAngle;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.client.gui.GuiSpellcasting;
import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.github.liquiHg.hexencrypt.util.HexTimbreClientState;
import io.github.liquiHg.hexencrypt.util.HexTimbreRegistry;
import net.minecraft.sounds.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GuiSpellcasting.class, remap = false)
public class GuiSpellcastingMixin {

    /** 本次 drawMove 计算出的转角。 */
    @Unique private HexAngle hexencrypt$lastTurn = HexAngle.FORWARD;
    /** 本次 drawMove 计算出的绝对方向（仅日志）。 */
    @Unique private HexDir hexencrypt$lastDir = HexDir.EAST;
    /** 本次 drawMove 是否是回退。默认 true，走到 tryAppendDir 再翻成 false。 */
    @Unique private boolean hexencrypt$isBacktrack = false;
    @Unique private static int hexencrypt$logCounter = 0;

    // ==================== drawMove ====================

    @Inject(method = "drawMove", at = @At("HEAD"), remap = false, require = 0)
    private void hexencrypt$onDrawMoveHead(double mx, double my, CallbackInfoReturnable<Boolean> cir) {
        // 保守假设：本次是回退。若后面走到 tryAppendDir，会被翻案为 false。
        this.hexencrypt$isBacktrack = true;
    }

    @Redirect(
            method = "drawMove",
            at = @At(
                    value = "INVOKE",
                    target = "Lat/petrak/hexcasting/api/casting/math/HexPattern;tryAppendDir(Lat/petrak/hexcasting/api/casting/math/HexDir;)Z"
            ),
            remap = false, require = 0
    )
    private boolean hexencrypt$captureTryAppendDir(HexPattern pattern, HexDir dir) {
        HexDir prevDir = pattern.finalDir();
        this.hexencrypt$lastTurn = dir.minus(prevDir);
        this.hexencrypt$lastDir = dir;
        this.hexencrypt$isBacktrack = false;   // 走到这里，确定是正常追加
        return pattern.tryAppendDir(dir);
    }

    /** 替换 SoundEvent（index = 0）。 */
    @ModifyArg(
            method = "drawMove",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/sounds/SimpleSoundInstance;<init>(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFLnet/minecraft/util/RandomSource;DDD)V"
            ),
            index = 0, remap = false, require = 0
    )
    private SoundEvent hexencrypt$tuneLineSound(SoundEvent original) {
        return HexTimbreRegistry.get(HexTimbreClientState.get());
    }

    /** 替换 pitch（index = 3）。 */
    @ModifyArg(
            method = "drawMove",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/sounds/SimpleSoundInstance;<init>(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFLnet/minecraft/util/RandomSource;DDD)V"
            ),
            index = 3, remap = false, require = 0
    )
    private float hexencrypt$tuneLinePitch(float originalPitch) {
        float newPitch;
        if (this.hexencrypt$isBacktrack) {
            // 回退：给一个明显低沉、独立于所有画线档位的音
            newPitch = 0.40F;
        } else {
            newPitch = switch (this.hexencrypt$lastTurn) {
                case FORWARD     -> 1.00F;
                case RIGHT       -> 1.25F;
                case RIGHT_BACK  -> 1.50F;
                case BACK        -> 1.90F;
                case LEFT_BACK   -> 0.67F;
                case LEFT        -> 0.80F;
            };
        }
        if ((hexencrypt$logCounter++ & 3) == 0) {
            HexEncrypt.LOGGER.info("[Hex加密/音高] {} turn={} (dir={}) pitch: {} -> {}",
                    this.hexencrypt$isBacktrack ? "BACK" : "FWD",
                    this.hexencrypt$lastTurn, this.hexencrypt$lastDir,
                    originalPitch, newPitch);
        }
        return newPitch;
    }

    // ==================== drawStart ====================

    @ModifyArg(
            method = "drawStart",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/sounds/SimpleSoundInstance;<init>(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFLnet/minecraft/util/RandomSource;DDD)V"
            ),
            index = 0, remap = false, require = 0
    )
    private SoundEvent hexencrypt$tuneStartSound(SoundEvent original) {
        return HexTimbreRegistry.get(HexTimbreClientState.get());
    }

    @ModifyArg(
            method = "drawStart",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/sounds/SimpleSoundInstance;<init>(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFLnet/minecraft/util/RandomSource;DDD)V"
            ),
            index = 3, remap = false, require = 0
    )
    private float hexencrypt$tuneStartPitch(float originalPitch) {
        final float START_PITCH = 3.0F;
        HexEncrypt.LOGGER.info("[Hex加密/音高] start pitch: {} -> {}", originalPitch, START_PITCH);
        return START_PITCH;
    }
}