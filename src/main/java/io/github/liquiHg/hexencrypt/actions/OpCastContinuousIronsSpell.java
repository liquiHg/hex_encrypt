package io.github.liquiHg.hexencrypt.actions;

import at.petrak.hexcasting.api.casting.castables.Action;
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment;
import at.petrak.hexcasting.api.casting.eval.OperationResult;
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage;
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation;
import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.casting.iota.EntityIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.mishaps.Mishap;
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota;
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs;
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds;
import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.github.liquiHg.hexencrypt.util.SpellUnlockManager;
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
 *   1. 法术等级 (Double) - 最先画，在栈底
 *   2. 持续时间 (Double, 单位: Tick) - 中间画
 *   3. 施法实体 (Entity) - 最后画，在栈顶
 *
 * 关键：本操作符会在 initiateCast 之前调用 spell.checkPreCastConditions，
 *       让法术自己设置附加施法数据（如 TelekinesisData、TargetedTargetAreaCastData 等）。
 *       不调用这一步，TelekinesisSpell 的 onServerCastTick 就永远拿不到目标。
 */
public class OpCastContinuousIronsSpell implements Action {

    private final ResourceLocation spellId;

    private static final int MIN_DURATION = 1;
    private static final int MAX_DURATION = 72000;

    public OpCastContinuousIronsSpell(ResourceLocation spellId) {
        this.spellId = spellId;
    }

    @Override
    public @NotNull OperationResult operate(@NotNull CastingEnvironment env,
                                            @NotNull CastingImage image,
                                            @NotNull SpellContinuation continuation) {
        try {
            return doOperate(env, image, continuation);
        } catch (Mishap m) {
            throw m;
        } catch (Throwable t) {
            HexEncrypt.LOGGER.error("[Hex加密] 持续施法发生严重异常 spell={}", this.spellId, t);
            throw new MishapCastFailed("施法内部错误: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : ""));
        }
    }

    private OperationResult doOperate(CastingEnvironment env,
                                      CastingImage image,
                                      SpellContinuation continuation) {
        var stack = image.getStack();

        // 1. 栈深度检查
        if (stack.size() < 3) {
            throw new MishapNotEnoughArgs(3, stack.size());
        }

        // 2. 弹施法者（栈顶）
        Iota iotaEntity = stack.remove(stack.size() - 1);
        if (!(iotaEntity instanceof EntityIota entityIota)) {
            stack.add(iotaEntity);
            throw MishapInvalidIota.ofType(iotaEntity, 0, "entity");
        }

        // 3. 弹持续时间（中间）
        Iota iotaDuration = stack.remove(stack.size() - 1);
        if (!(iotaDuration instanceof DoubleIota doubleDuration)) {
            stack.add(iotaDuration);
            stack.add(iotaEntity);
            throw MishapInvalidIota.ofType(iotaDuration, 1, "double");
        }
        int customDuration = (int) doubleDuration.getDouble();

        // 4. 弹法术等级（栈底）
        Iota iotaLevel = stack.remove(stack.size() - 1);
        if (!(iotaLevel instanceof DoubleIota doubleIota)) {
            stack.add(iotaLevel);
            stack.add(iotaDuration);
            stack.add(iotaEntity);
            throw MishapInvalidIota.ofType(iotaLevel, 2, "double");
        }
        int level = (int) doubleIota.getDouble();
        var casterEntity = entityIota.getEntity();

        // 5. 持续时间合法性
        if (customDuration < MIN_DURATION || customDuration > MAX_DURATION) {
            stack.add(iotaLevel);
            stack.add(iotaDuration);
            stack.add(iotaEntity);
            throw new MishapCastFailed("持续时间必须在 " + MIN_DURATION + " 到 " + MAX_DURATION + " tick 之间");
        }

        // 6. 施法者校验
        if (!(casterEntity instanceof ServerPlayer serverPlayer)) {
            stack.add(iotaLevel);
            stack.add(iotaDuration);
            stack.add(iotaEntity);
            throw new MishapCastFailed("施法者必须是一名服务端玩家");
        }

        // 7. 法术实例
        AbstractSpell spell = SpellRegistry.getSpell(this.spellId);
        if (spell == null || spell == SpellRegistry.none()) {
            stack.add(iotaLevel);
            stack.add(iotaDuration);
            stack.add(iotaEntity);
            throw new MishapCastFailed("未找到对应的铁魔法法术: " + this.spellId);
        }

        MagicData magicData = MagicData.getPlayerMagicData(serverPlayer);
        magicData.getSyncedData();

        // ============ 8. 中断分支：如果正在施放同一个法术，触发中断 ============
        if (magicData.isCasting() && magicData.getCastingSpellId().equals(this.spellId.toString())) {
            magicData.resetAdditionalCastData();
            try {
                spell.onServerCastComplete(env.getWorld(), level, serverPlayer, magicData, true);
            } catch (Throwable t) {
                HexEncrypt.LOGGER.warn("[Hex加密] onServerCastComplete 抛出异常 spell={}",
                        this.spellId, t);
            }
            return new OperationResult(image, List.of(), continuation, HexEvalSounds.NORMAL_EXECUTE);
        }

        // ============ 9. 首次施法 ============

        // 9.1 冷却 / 魔法值 / 学习状态
        CastResult castResult = spell.canBeCastedBy(level, CastSource.SPELLBOOK, magicData, serverPlayer);
        if (!castResult.isSuccess()) {
            stack.add(iotaLevel);
            stack.add(iotaDuration);
            stack.add(iotaEntity);
            String errorMsg = castResult.message != null ? castResult.message.getString() : "未知原因";
            throw new MishapCastFailed("无法施法: " + errorMsg);
        }

        // 9.2 前置条件（含目标射线检测）
        //     这一步是恢复 Telekinesis / 其他需要目标的持续法术能正常工作的关键。
        //     checkPreCastConditions 内部会：
        //       - 做 raycast 找目标
        //       - 把目标写入 additionalCastData（例如 TelekinesisData、TargetedTargetAreaCastData）
        //     如果返回 false，法术自己已经通过 ActionBar 提示玩家，这里不再重复发消息。
        try {
            if (!spell.checkPreCastConditions(env.getWorld(), level, serverPlayer, magicData)) {
                stack.add(iotaLevel);
                stack.add(iotaDuration);
                stack.add(iotaEntity);
                throw new MishapCastFailed("施法前置条件未满足（可能是未瞄准有效目标）");
            }
        } catch (Mishap m) {
            throw m;
        } catch (Throwable t) {
            stack.add(iotaLevel);
            stack.add(iotaDuration);
            stack.add(iotaEntity);
            HexEncrypt.LOGGER.error("[Hex加密] checkPreCastConditions 抛出异常 spell={}",
                    this.spellId, t);
            throw new MishapCastFailed("前置条件检查异常: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : ""));
        }

        // 9.3 进入施法状态。这里用玩家自定义持续时间，替换 ISS 默认的 getCastTime。
        magicData.initiateCast(spell, level, customDuration, CastSource.SPELLBOOK, "hex_encrypt");
        magicData.setPlayerCastingItem(serverPlayer.getMainHandItem());

        // 9.4 前置效果 + 施法
        // 9.4 前置效果 + 施法
        try {
            spell.onServerPreCast(env.getWorld(), level, serverPlayer, magicData);
            spell.castSpell(env.getWorld(), level, serverPlayer, CastSource.SPELLBOOK, false);

            // 首次施法成功：仅当玩家主动打开 Hex 网格绘制时解锁 ISS 法术
            // （后续每 tick 都会再次进入本方法，但 tryUnlockBySpellId 内部
            //   有幂等判重，不会重复写盘 / 刷屏）
            if (SpellUnlockManager.isPlayerInitiatedCasting(env)) {
                SpellUnlockManager.tryUnlockBySpellId(serverPlayer, this.spellId);
            }
        } catch (Throwable t) {
            HexEncrypt.LOGGER.error("[Hex加密] spell.castSpell 抛出异常 spell={} level={}",
                    this.spellId, level, t);
            stack.add(iotaLevel);
            stack.add(iotaDuration);
            stack.add(iotaEntity);
            try {
                magicData.resetCastingState();
                magicData.resetAdditionalCastData();
            } catch (Throwable ignored) {
            }
            throw new MishapCastFailed("施法内部错误: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : ""));
        }

        return new OperationResult(image, List.of(), continuation, HexEvalSounds.SPELL);
    }
}