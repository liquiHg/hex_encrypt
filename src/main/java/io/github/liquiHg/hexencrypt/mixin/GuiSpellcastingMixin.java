package io.github.liquiHg.hexencrypt.mixin;

import at.petrak.hexcasting.api.casting.math.HexAngle;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.client.gui.GuiSpellcasting;
import io.github.liquiHg.hexencrypt.HexEncrypt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = GuiSpellcasting.class, remap = false)
public class GuiSpellcastingMixin {

    /** 本次 drawMove 计算出的转角。 */
    @Unique
    private HexAngle hexencrypt$lastTurn = HexAngle.FORWARD;

    /** 本次 drawMove 计算出的绝对方向（仅用于日志）。 */
    @Unique
    private HexDir hexencrypt$lastDir = HexDir.EAST;

    @Unique
    private static int hexencrypt$logCounter = 0;

    // ==================== 画线音效（drawMove） ====================

    @Redirect(
            method = "drawMove",
            at = @At(
                    value = "INVOKE",
                    target = "Lat/petrak/hexcasting/api/casting/math/HexPattern;tryAppendDir(Lat/petrak/hexcasting/api/casting/math/HexDir;)Z"
            ),
            remap = false,
            require = 0
    )
    private boolean hexencrypt$captureTryAppendDir(HexPattern pattern, HexDir dir) {
        // 调用前先取"上一步方向"。
        // pattern 为空时 finalDir() == startDir，此时 dir - startDir = FORWARD，正好是"直走"。
        HexDir prevDir = pattern.finalDir();
        this.hexencrypt$lastTurn = dir.minus(prevDir);
        this.hexencrypt$lastDir = dir;
        return pattern.tryAppendDir(dir);
    }

    @ModifyArg(
            method = "drawMove",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/sounds/SimpleSoundInstance;<init>(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFLnet/minecraft/util/RandomSource;DDD)V"
            ),
            index = 3,
            remap = false,
            require = 0
    )
    private float hexencrypt$tuneLinePitch(float originalPitch) {
        float newPitch = switch (this.hexencrypt$lastTurn) {
            case FORWARD     -> 1.00F;   // 直走：主音
            case RIGHT       -> 1.25F;   // 右拐 60°：大三度
            case RIGHT_BACK  -> 1.50F;   // 右拐 120°：五度
            case BACK        -> 1.90F;   // 掉头（Hex 会拦，通常到不了）
            case LEFT_BACK   -> 0.67F;   // 左拐 120°：下行五度
            case LEFT        -> 0.80F;   // 左拐 60°：下行小三度
        };
        if ((hexencrypt$logCounter++ & 3) == 0) {
            HexEncrypt.LOGGER.info(
                    "[Hex加密/音高] turn={} (dir={}) pitch: {} -> {}",
                    this.hexencrypt$lastTurn, this.hexencrypt$lastDir,
                    originalPitch, newPitch);
        }
        return newPitch;
    }

    // ==================== 落笔音效（drawStart） ====================

    @ModifyArg(
            method = "drawStart",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/sounds/SimpleSoundInstance;<init>(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFLnet/minecraft/util/RandomSource;DDD)V"
            ),
            index = 3,
            remap = false,
            require = 0
    )
    private float hexencrypt$tuneStartPitch(float originalPitch) {
        final float START_PITCH = 3.0F;
        HexEncrypt.LOGGER.info(
                "[Hex加密/音高] start pitch: {} -> {}",
                originalPitch, START_PITCH);
        return START_PITCH;
    }
}