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
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds; // 新增导入
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class OpDouble implements Action {

    @Override
    public @NotNull OperationResult operate(@NotNull CastingEnvironment env, @NotNull CastingImage image, @NotNull SpellContinuation continuation) {
        // 1. 获取当前的栈
        var stack = image.getStack();

        // 2. 检查栈是否为空
        if (stack.isEmpty()) {
            throw new MishapNotEnoughArgs(1, 0);
        }

        // 3. 弹出栈顶元素
        Iota iota = stack.remove(stack.size() - 1);

        // 4. 检查类型是否为数字（DoubleIota）
        if (!(iota instanceof DoubleIota doubleIota)) {
            // 如果不是数字，把元素放回去，并抛出 Mishap（法术失误）
            stack.add(iota);
            // 【修正1】使用 ofType 静态方法，只需传入类型名称 "double"
            throw MishapInvalidIota.ofType(iota, 0, "double");
        }

        // 5. 执行逻辑：乘以2
        double result = doubleIota.getDouble() * 2.0;

        // 6. 将结果压回栈中
        stack.add(new DoubleIota(result));

        // 7. 【修正2】返回 OperationResult，第四个参数传入 HexEvalSounds.NORMAL
        return new OperationResult(image, List.of(), continuation, HexEvalSounds.NORMAL_EXECUTE);
    }
}