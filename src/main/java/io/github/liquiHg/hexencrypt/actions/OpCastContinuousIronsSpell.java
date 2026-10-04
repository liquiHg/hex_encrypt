package io.github.liquiHg.hexencrypt.actions;

import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.OperationResult;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.casting.iota.EntityIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota;
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastResult;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 处理 CONTINUOUS 类型法术的操作符，允许通过 Hex 栈自定义最大持续时间。
 *
 * 栈输入顺序（玩家绘制顺序）：
 * 1. 法术等级 (Double) - 最先画，在栈底
 * 2. 持续时间 (Double, 单位: Tick) - 中间画
 * 3. 施法实体 (Entity) - 最后画，在栈顶
 */
public class OpCastContinuousIronsSpell implements Action {
    private final ResourceLocation spellId;

    // 持续时间限制：1 tick 到 1 小时 (72000 ticks)
    private static final int MIN_DURATION = 1;
    private static final int MAX_DURATION = 72000;

    public OpCastContinuousIronsSpell(ResourceLocation spellId) {
        this.spellId = spellId;
    }

    @Override
    public @NotNull OperationResult operate(@NotNull CastingEnvironment env, @NotNull CastingImage image, @NotNull SpellContinuation continuation) {
        var stack = image.getStack();

        // 1. 检查栈中是否有 3 个元素
        if (stack.size() < 3) {
            throw new MishapNotEnoughArgs(3, stack.size());
        }

        // 2. 弹出施法实体（栈顶，最后画）
        Iota iotaEntity = stack.remove(stack.size() - 1);
        if (!(iotaEntity instanceof EntityIota entityIota)) {
            stack.add(iotaEntity);
            throw MishapInvalidIota.ofType(iotaEntity, 0, "entity");
        }

        // 3. 弹出持续时间（中间画）
        Iota iotaDuration = stack.remove(stack.size() - 1);
        if (!(iotaDuration instanceof DoubleIota doubleDuration)) {
            stack.add(iotaDuration);
            stack.add(iotaEntity);
            throw MishapInvalidIota.ofType(iotaDuration, 1, "double");
        }
        int customDuration = (int) doubleDuration.getDouble();

        // 4. 弹出法术等级（栈底，最先画）
        Iota iotaLevel = stack.remove(stack.size() - 1);
        if (!(iotaLevel instanceof DoubleIota doubleIota)) {
            stack.add(iotaLevel);
            stack.add(iotaDuration);
            stack.add(iotaEntity);
            throw MishapInvalidIota.ofType(iotaLevel, 2, "double");
        }
        int level = (int) doubleIota.getDouble();
        var casterEntity = entityIota.getEntity();

        // 5. 校验自定义持续时间是否合法
        if (customDuration < MIN_DURATION || customDuration > MAX_DURATION) {
            stack.add(iotaLevel);
            stack.add(iotaDuration);
            stack.add(iotaEntity);
            throw new MishapCastFailed("持续时间必须在 " + MIN_DURATION + " 到 " + MAX_DURATION + " tick 之间");
        }

        // 6. 校验施法者是否为服务端玩家
        if (!(casterEntity instanceof ServerPlayer serverPlayer)) {
            stack.add(iotaLevel);
            stack.add(iotaDuration);
            stack.add(iotaEntity);
            throw new MishapCastFailed("施法者必须是一名服务端玩家");
        }

        // 7. 获取铁魔法法术实例
        AbstractSpell spell = SpellRegistry.getSpell(this.spellId);
        if (spell == null || spell == SpellRegistry.none()) {
            stack.add(iotaLevel);
            stack.add(iotaDuration);
            stack.add(iotaEntity);
            throw new MishapCastFailed("未找到对应的铁魔法法术: " + spellId);
        }

        MagicData magicData = MagicData.getPlayerMagicData(serverPlayer);
        magicData.getSyncedData(); // 提前初始化防止空指针

        // ============ 8. 再次施法，触发中断 ============
        if (magicData.isCasting() && magicData.getCastingSpellId().equals(this.spellId.toString())) {
            // 【强化清理】手动强制清理附加数据（投射物实体）
            magicData.resetAdditionalCastData();

            // 调用 onServerCastComplete 会重置状态、触发冷却
            spell.onServerCastComplete(env.getWorld(), level, serverPlayer, magicData, true);

            return new OperationResult(image, List.of(), continuation, HexEvalSounds.NORMAL_EXECUTE);
        }

        // ============ 9. 第一次施法，开启状态 ============
        CastResult castResult = spell.canBeCastedBy(level, CastSource.SPELLBOOK, magicData, serverPlayer);
        if (!castResult.isSuccess()) {
            stack.add(iotaLevel);
            stack.add(iotaDuration);
            stack.add(iotaEntity);
            String errorMsg = castResult.message != null ? castResult.message.getString() : "未知原因";
            throw new MishapCastFailed("无法施法: " + errorMsg);
        }

        // 开启施法状态，使用玩家自定义的持续时间
        magicData.initiateCast(spell, level, customDuration, CastSource.SPELLBOOK, "hex_encrypt");
        magicData.setPlayerCastingItem(serverPlayer.getMainHandItem());

        // 播放前置效果
        spell.onServerPreCast(env.getWorld(), level, serverPlayer, magicData);

        // 执行真正的施法逻辑（生成投射物）
        spell.castSpell(env.getWorld(), level, serverPlayer, CastSource.SPELLBOOK, false);

        return new OperationResult(image, List.of(), continuation, HexEvalSounds.SPELL);
    }
}