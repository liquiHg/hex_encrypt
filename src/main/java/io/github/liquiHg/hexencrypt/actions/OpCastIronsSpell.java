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
 * 通用 ISS 法术操作符，处理 INSTANT 和 LONG 类型的法术。
 *
 * 栈输入顺序（玩家绘制顺序，后进先出）：
 *   1. 法术等级 (Double) - 栈底，最先画
 *   2. 施法实体 (Entity) - 栈顶，最后画
 *
 * 注意：本操作符会调用 spell.checkPreCastConditions，恢复 ISS 原版施法流程。
 * 需要目标的法术（例如 haste、slow、telekinesis）会在这一步内部做射线检测，
 * 玩家需要对准目标才能成功施放。如果想让 Hex 玩家显式指定目标，请使用
 * OpCastIronsSpellWithTarget（仅适用于"直接读 TargetEntityCastData"的法术）。
 */
public class OpCastIronsSpell implements Action {

    private final ResourceLocation spellId;

    public OpCastIronsSpell(ResourceLocation spellId) {
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
            HexEncrypt.LOGGER.error("[Hex加密] 施法发生严重异常 spell={}", this.spellId, t);
            throw new MishapCastFailed("施法内部错误: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : ""));
        }
    }

    private OperationResult doOperate(CastingEnvironment env,
                                      CastingImage image,
                                      SpellContinuation continuation) {
        var stack = image.getStack();

        if (stack.size() < 2) {
            throw new MishapNotEnoughArgs(2, stack.size());
        }

        Iota iotaEntity = stack.remove(stack.size() - 1);
        if (!(iotaEntity instanceof EntityIota entityIota)) {
            stack.add(iotaEntity);
            throw MishapInvalidIota.ofType(iotaEntity, 0, "entity");
        }

        Iota iotaLevel = stack.remove(stack.size() - 1);
        if (!(iotaLevel instanceof DoubleIota doubleIota)) {
            stack.add(iotaLevel);
            stack.add(iotaEntity);
            throw MishapInvalidIota.ofType(iotaLevel, 1, "double");
        }

        int level = (int) doubleIota.getDouble();
        var casterEntity = entityIota.getEntity();

        if (!(casterEntity instanceof ServerPlayer serverPlayer)) {
            stack.add(iotaLevel);
            stack.add(iotaEntity);
            throw new MishapCastFailed("施法者必须是一名服务端玩家");
        }

        AbstractSpell spell = SpellRegistry.getSpell(this.spellId);
        if (spell == null || spell == SpellRegistry.none()) {
            stack.add(iotaLevel);
            stack.add(iotaEntity);
            throw new MishapCastFailed("未找到对应的铁魔法法术: " + this.spellId);
        }

        MagicData magicData = MagicData.getPlayerMagicData(serverPlayer);
        magicData.getSyncedData();

        // 1. 冷却 / 魔法值 / 学习状态检查
        CastResult castResult = spell.canBeCastedBy(level, CastSource.SPELLBOOK, magicData, serverPlayer);
        if (!castResult.isSuccess()) {
            stack.add(iotaLevel);
            stack.add(iotaEntity);
            String errorMsg = castResult.message != null ? castResult.message.getString() : "未知原因";
            throw new MishapCastFailed("无法施法: " + errorMsg);
        }

        // 2. 前置条件（含目标射线检测）。这一步是恢复 ISS 原版流程的关键。
        //    返回 false 表示玩家没有满足前置条件（例如没瞄准目标），
        //    法术自己会通过 ActionBar 消息给出提示。
        if (!spell.checkPreCastConditions(env.getWorld(), level, serverPlayer, magicData)) {
            stack.add(iotaLevel);
            stack.add(iotaEntity);
            throw new MishapCastFailed("施法前置条件未满足（可能是未瞄准有效目标）");
        }

        // 3. 进入施法状态
        int castDuration = spell.getCastTime(level);
        magicData.initiateCast(spell, level, castDuration, CastSource.SPELLBOOK, "hex_encrypt");
        magicData.setPlayerCastingItem(serverPlayer.getMainHandItem());

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
            stack.add(iotaLevel);
            stack.add(iotaEntity);
            throw new MishapCastFailed("施法内部错误: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : ""));
        } finally {
            magicData.resetCastingState();
        }

        return new OperationResult(image, List.of(), continuation, HexEvalSounds.SPELL);
    }
}