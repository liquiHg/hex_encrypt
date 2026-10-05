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
            // Mishap 是 Hex 的正常错误机制（红字提示），直接透传
            throw m;
        } catch (Throwable t) {
            // 捕获所有其他异常，防止它传播到网络层导致玩家掉线/崩服
            HexEncrypt.LOGGER.error("[Hex加密] 施法发生严重异常 spell={}", this.spellId, t);
            throw new MishapCastFailed("施法内部错误: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : ""));
        }
    }

    private OperationResult doOperate(CastingEnvironment env,
                                      CastingImage image,
                                      SpellContinuation continuation) {
        var stack = image.getStack();

        // 1. 检查栈中是否有 2 个元素
        if (stack.size() < 2) {
            throw new MishapNotEnoughArgs(2, stack.size());
        }

        // 2. 弹出施法实体（栈顶）
        Iota iotaEntity = stack.remove(stack.size() - 1);
        if (!(iotaEntity instanceof EntityIota entityIota)) {
            stack.add(iotaEntity);
            throw MishapInvalidIota.ofType(iotaEntity, 0, "entity");
        }

        // 3. 弹出法术等级（栈顶）
        Iota iotaLevel = stack.remove(stack.size() - 1);
        if (!(iotaLevel instanceof DoubleIota doubleIota)) {
            stack.add(iotaLevel);
            stack.add(iotaEntity);
            throw MishapInvalidIota.ofType(iotaLevel, 1, "double");
        }

        int level = (int) doubleIota.getDouble();
        var casterEntity = entityIota.getEntity();

        // 4. 校验施法者
        if (!(casterEntity instanceof ServerPlayer serverPlayer)) {
            stack.add(iotaLevel);
            stack.add(iotaEntity);
            throw new MishapCastFailed("施法者必须是一名服务端玩家");
        }

        // 5. 获取法术
        AbstractSpell spell = SpellRegistry.getSpell(this.spellId);
        if (spell == null || spell == SpellRegistry.none()) {
            stack.add(iotaLevel);
            stack.add(iotaEntity);
            throw new MishapCastFailed("未找到对应的铁魔法法术: " + this.spellId);
        }

        MagicData magicData = MagicData.getPlayerMagicData(serverPlayer);
        // 提前初始化 syncedSpellData，避免 initiateCast 内部 NPE
        magicData.getSyncedData();

        // 6. 检查能否施法（魔法值、冷却、学习状态等）
        CastResult castResult = spell.canBeCastedBy(level, CastSource.SPELLBOOK, magicData, serverPlayer);
        if (!castResult.isSuccess()) {
            stack.add(iotaLevel);
            stack.add(iotaEntity);
            String errorMsg = castResult.message != null ? castResult.message.getString() : "未知原因";
            throw new MishapCastFailed("无法施法: " + errorMsg);
        }

        // 7. 模拟 ISS 施法状态：让某些法术内部的 isCasting() 检查通过
        //    （例如 FangStrikeSpell 生成的 ExtendedEvokerFang 可能依赖此状态）
        int castDuration = spell.getCastTime(level);
        magicData.initiateCast(spell, level, castDuration, CastSource.SPELLBOOK, "hex_encrypt");
        magicData.setPlayerCastingItem(serverPlayer.getMainHandItem());

        // 8. 执行施法，无论成功还是抛异常，最后一定清理施法状态
        try {
            // 前置效果（音效等）
            spell.onServerPreCast(env.getWorld(), level, serverPlayer, magicData);

            // 执行法术实际逻辑
            spell.castSpell(env.getWorld(), level, serverPlayer, CastSource.SPELLBOOK, false);
        } catch (Throwable t) {
            // 把异常写入日志，方便排查
            HexEncrypt.LOGGER.error("[Hex加密] spell.castSpell 抛出异常 spell={} level={}",
                    this.spellId, level, t);

            // 把弹栈的元素放回去，让 Hex 的 Mishap 处理流程能正常回滚
            stack.add(iotaLevel);
            stack.add(iotaEntity);

            throw new MishapCastFailed("施法内部错误: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : ""));
        } finally {
            // 无论成功与否，都清理施法状态
            // 避免玩家卡在"吟唱中"导致后续无法施放其他法术
            magicData.resetCastingState();
        }

        // 9. 成功，返回结果（法术不入栈）
        return new OperationResult(image, List.of(), continuation, HexEvalSounds.SPELL);
    }
}