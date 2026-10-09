package io.github.liquiHg.hexencrypt.actions;

import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.OperationResult;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota;
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import io.github.liquiHg.hexencrypt.network.ModNetwork;
import io.github.liquiHg.hexencrypt.util.HexTimbreRegistry;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 设置玩家当前音色。
 *
 * 栈输入（栈顶）：
 *   - DoubleIota：音色索引
 *       0  = default（Hex 原版画线音）
 *       1  = bell
 *       2  = chime
 *       3  = flute
 *       4  = guitar
 *       5  = harp
 *       6  = xylophone
 *       7  = iron_xylophone
 *       8  = cow_bell
 *       9  = didgeridoo
 *       10 = pling
 *       11 = bass
 *       12 = snare
 *       13 = hat
 *       14 = bit
 *       15 = banjo
 *
 * 落笔和画线的音效都会用这个音色，音高规则不受影响。
 * 只影响客户端播放，服务端状态通过 SyncTimbrePacket 通知客户端。
 */
public class OpSetTimbre implements Action {

    private static final String NBT_KEY = "hexencrypt:timbre";

    @Override
    public @NotNull OperationResult operate(@NotNull CastingEnvironment env,
                                            @NotNull CastingImage image,
                                            @NotNull SpellContinuation continuation) {
        var stack = image.getStack();
        if (stack.isEmpty()) throw new MishapNotEnoughArgs(1, 0);

        Iota top = stack.remove(stack.size() - 1);
        if (!(top instanceof DoubleIota di)) {
            stack.add(top);
            throw MishapInvalidIota.ofType(top, 0, "double");
        }

        int idx = (int) di.getDouble();
        String timbre = HexTimbreRegistry.byIndex(idx);
        if (timbre == null) {
            stack.add(top);
            throw new MishapCastFailed("音色索引越界: " + idx
                    + "（可用范围 0 ~ " + HexTimbreRegistry.maxIndex() + "）");
        }

        if (!(env.getCastingEntity() instanceof ServerPlayer player)) {
            stack.add(top);
            throw new MishapCastFailed("施法者必须是一名服务端玩家");
        }

        // 服务端持久化
        player.getPersistentData().putString(NBT_KEY, timbre);
        // 同步到客户端
        ModNetwork.sendTimbre(player, timbre);

        return new OperationResult(image, List.of(), continuation, HexEvalSounds.NORMAL_EXECUTE);
    }
}