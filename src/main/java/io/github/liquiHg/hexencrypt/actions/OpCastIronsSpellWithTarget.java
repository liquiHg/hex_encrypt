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
import io.redspace.ironsspellbooks.capabilities.magic.TargetEntityCastData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 带目标实体的 ISS 法术操作符。
 * 用于需要显式指定目标生物的法术（例如 SacrificeSpell）。
 *
 * 它和 OpCastIronsSpell 的核心区别：
 *   OpCastIronsSpell 走「玩家用准星瞄」的流程，会调用 checkPreCastConditions 做射线检测；
 *   本操作符直接从 Hex 栈上取目标实体，跳过射线检测，直接把 TargetEntityCastData 塞进 MagicData。
 *   这样玩家可以用「实体 iota」精确指定远处/不受视线限制的目标。
 *
 * 栈输入顺序（玩家绘制顺序，后进先出）：
 *   1. 法术等级 (Double) - 栈底，最先画
 *   2. 目标实体 (Entity) - 中间画
 *   3. 施法实体 (Entity) - 栈顶，最后画
 */
public class OpCastIronsSpellWithTarget implements Action {

    private final ResourceLocation spellId;

    public OpCastIronsSpellWithTarget(ResourceLocation spellId) {
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
            HexEncrypt.LOGGER.error("[Hex加密] 带目标施法发生严重异常 spell={}", this.spellId, t);
            throw new MishapCastFailed("施法内部错误: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : ""));
        }
    }

    private OperationResult doOperate(CastingEnvironment env,
                                      CastingImage image,
                                      SpellContinuation continuation) {
        var stack = image.getStack();

        // 1. 需要 3 个元素：level, target, caster
        if (stack.size() < 3) {
            throw new MishapNotEnoughArgs(3, stack.size());
        }

        // 2. 弹出施法者（栈顶）
        Iota iotaCaster = stack.remove(stack.size() - 1);
        if (!(iotaCaster instanceof EntityIota casterIota)) {
            stack.add(iotaCaster);
            throw MishapInvalidIota.ofType(iotaCaster, 0, "entity");
        }

        // 3. 弹出目标（栈顶 - 1）
        Iota iotaTarget = stack.remove(stack.size() - 1);
        if (!(iotaTarget instanceof EntityIota targetIota)) {
            stack.add(iotaTarget);
            stack.add(iotaCaster);
            throw MishapInvalidIota.ofType(iotaTarget, 1, "entity");
        }

        // 4. 弹出法术等级（栈顶 - 2）
        Iota iotaLevel = stack.remove(stack.size() - 1);
        if (!(iotaLevel instanceof DoubleIota doubleIota)) {
            stack.add(iotaLevel);
            stack.add(iotaTarget);
            stack.add(iotaCaster);
            throw MishapInvalidIota.ofType(iotaLevel, 2, "double");
        }

        int level = (int) doubleIota.getDouble();
        var casterEntity = casterIota.getEntity();
        var targetEntity = targetIota.getEntity();

        // 5. 施法者必须是服务端玩家
        if (!(casterEntity instanceof ServerPlayer serverPlayer)) {
            stack.add(iotaLevel);
            stack.add(iotaTarget);
            stack.add(iotaCaster);
            throw new MishapCastFailed("施法者必须是一名服务端玩家");
        }

        // 6. 目标必须是生物（大多数 TargetEntityCastData 的使用方要求 LivingEntity）
        if (!(targetEntity instanceof LivingEntity livingTarget)) {
            stack.add(iotaLevel);
            stack.add(iotaTarget);
            stack.add(iotaCaster);
            throw new MishapCastFailed("目标必须是一个生物实体");
        }

        // 7. 获取法术实例
        AbstractSpell spell = SpellRegistry.getSpell(this.spellId);
        if (spell == null || spell == SpellRegistry.none()) {
            stack.add(iotaLevel);
            stack.add(iotaTarget);
            stack.add(iotaCaster);
            throw new MishapCastFailed("未找到对应的铁魔法法术: " + this.spellId);
        }

        MagicData magicData = MagicData.getPlayerMagicData(serverPlayer);
        // 提前初始化 syncedSpellData，避免 initiateCast 内部 NPE
        magicData.getSyncedData();

        // 8. 检查能否施法（魔法值、冷却、学习状态等）
        CastResult castResult = spell.canBeCastedBy(level, CastSource.SPELLBOOK, magicData, serverPlayer);
        if (!castResult.isSuccess()) {
            stack.add(iotaLevel);
            stack.add(iotaTarget);
            stack.add(iotaCaster);
            String errorMsg = castResult.message != null ? castResult.message.getString() : "未知原因";
            throw new MishapCastFailed("无法施法: " + errorMsg);
        }

        // 9. 进入施法状态
        int castDuration = spell.getCastTime(level);
        magicData.initiateCast(spell, level, castDuration, CastSource.SPELLBOOK, "hex_encrypt");
        magicData.setPlayerCastingItem(serverPlayer.getMainHandItem());

        // 10. 设置目标实体数据。
        //     这一步替代了普通流程中 checkPreCastConditions 的射线检测部分。
        //     必须放在 initiateCast 之后，避免内部状态被初始化时覆盖。
        magicData.setAdditionalCastData(new TargetEntityCastData(livingTarget));

        try {
            spell.onServerPreCast(env.getWorld(), level, serverPlayer, magicData);
            spell.castSpell(env.getWorld(), level, serverPlayer, CastSource.SPELLBOOK, false);

            // 施法成功：仅当玩家主动打开 Hex 网格绘制时解锁 ISS 法术
            if (SpellUnlockManager.isPlayerInitiatedCasting(env)) {
                SpellUnlockManager.tryUnlockBySpellId(serverPlayer, this.spellId);
            }
        } catch (Throwable t) {
            HexEncrypt.LOGGER.error("[Hex加密] spell.castSpell 抛出异常 spell={} level={}",
                    this.spellId, level, t);
            magicData.resetAdditionalCastData();
            stack.add(iotaLevel);
            stack.add(iotaTarget);
            stack.add(iotaCaster);
            throw new MishapCastFailed("施法内部错误: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : ""));
        } finally {
            magicData.resetCastingState();
            magicData.resetAdditionalCastData();
        }

        return new OperationResult(image, List.of(), continuation, HexEvalSounds.SPELL);
    }
}