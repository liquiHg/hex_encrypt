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

public class OpCastIronsSpell implements Action {
    private final ResourceLocation spellId;

    public OpCastIronsSpell(ResourceLocation spellId) {
        this.spellId = spellId;
    }

    @Override
    public @NotNull OperationResult operate(@NotNull CastingEnvironment env, @NotNull CastingImage image, @NotNull SpellContinuation continuation) {
        var stack = image.getStack();

        if (stack.size() < 2) {
            throw new MishapNotEnoughArgs(2, stack.size());
        }

        // 弹出施法实体
        Iota iotaEntity = stack.remove(stack.size() - 1);
        if (!(iotaEntity instanceof EntityIota entityIota)) {
            stack.add(iotaEntity);
            throw MishapInvalidIota.ofType(iotaEntity, 0, "entity");
        }

        // 弹出法术等级
        Iota iotaLevel = stack.remove(stack.size() - 1);
        if (!(iotaLevel instanceof DoubleIota doubleIota)) {
            stack.add(iotaLevel);
            stack.add(iotaEntity);
            throw MishapInvalidIota.ofType(iotaLevel, 1, "double");
        }

        int level = (int) doubleIota.getDouble();
        var casterEntity = entityIota.getEntity();

        // 仅支持 ServerPlayer
        if (!(casterEntity instanceof ServerPlayer serverPlayer)) {
            stack.add(iotaLevel);
            stack.add(iotaEntity);
            throw new MishapCastFailed("施法者必须是一名服务端玩家");
        }

        AbstractSpell spell = SpellRegistry.getSpell(this.spellId);
        if (spell == null || spell == SpellRegistry.none()) {
            stack.add(iotaLevel);
            stack.add(iotaEntity);
            throw new MishapCastFailed("未找到对应的铁魔法法术: " + spellId);
        }

        // 【移除限制】这里不再检查 INSTANT、LONG 或 CONTINUOUS，全部直接尝试施法

        MagicData magicData = MagicData.getPlayerMagicData(serverPlayer);

        // 使用 SPELLBOOK 来源，避免 SCROLL 对多段施法（Recast）的限制
        CastResult castResult = spell.canBeCastedBy(
                level,
                CastSource.SPELLBOOK,
                magicData,
                serverPlayer
        );

        if (!castResult.isSuccess()) {
            stack.add(iotaLevel);
            stack.add(iotaEntity);
            String errorMsg = castResult.message != null ? castResult.message.getString() : "未知原因";
            throw new MishapCastFailed("无法施法: " + errorMsg);
        }

        // 执行施法（triggerCooldown 设为 false，因为我们可能需要在一次 Hex 调用中多次执行）
        spell.castSpell(
                env.getWorld(),
                level,
                serverPlayer,
                CastSource.SPELLBOOK,
                false
        );

        return new OperationResult(image, List.of(), continuation, HexEvalSounds.SPELL);
    }
}