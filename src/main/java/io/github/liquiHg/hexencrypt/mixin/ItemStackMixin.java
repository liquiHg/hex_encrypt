package io.github.liquiHg.hexencrypt.mixin;

import io.github.liquiHg.hexencrypt.Config;
import io.github.liquiHg.hexencrypt.util.SpellUnlockManager;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ItemStack.class)
public class ItemStackMixin {

    /**
     * 混淆卷轴物品的显示名称（Title）。
     * 只处理卷轴（Scroll），因为法术书/注魔武器的标题本身不含法术名。
     */
    @Inject(method = "getHoverName", at = @At("RETURN"), cancellable = true)
    private void hexencrypt$obfuscateScrollName(CallbackInfoReturnable<Component> cir) {
        if (!Config.enableEncryption) return;

        ItemStack stack = (ItemStack) (Object) this;
        if (!SpellUnlockManager.isScroll(stack)) return;
        if (SpellUnlockManager.isSpellUnlocked(stack)) return;

        Component original = cir.getReturnValue();
        Component obfuscated = Component.literal(original.getString())
                .withStyle(original.getStyle().withObfuscated(true));
        cir.setReturnValue(obfuscated);
    }

    /**
     * 混淆 Tooltip 中的法术名称（法术书 / 注魔武器 / 卷轴均适用）。
     * 在 Tooltip 生成完成后（RETURN）逐行匹配未解锁法术的显示名并加上乱码样式。
     */
    @Inject(method = "getTooltipLines", at = @At("RETURN"))
    private void hexencrypt$obfuscateSpellTooltips(
            Player player, TooltipFlag flag,
            CallbackInfoReturnable<List<Component>> cir) {

        if (!Config.enableEncryption) return;
        if (player == null) return; // 没有 Player 上下文（例如创造模式搜索栏）时跳过

        ItemStack stack = (ItemStack) (Object) this;
        List<AbstractSpell> spells = SpellUnlockManager.getSpellsFromItem(stack);
        if (spells.isEmpty()) return;

        List<Component> lines = cir.getReturnValue();
        if (lines == null || lines.isEmpty()) return;

        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            String text = line.getString();

            for (AbstractSpell spell : spells) {
                // 已解锁则不混淆
                if (SpellUnlockManager.isSpellUnlocked(player, spell)) continue;

                // 用玩家本地的显示名匹配（中文/本地化环境下必须用 getDisplayName）
                String displayName = spell.getDisplayName(player).getString();
                if (displayName.isEmpty()) continue;

                if (text.contains(displayName)) {
                    // 保留原有颜色/样式，仅追加 obfuscated
                    Style newStyle = line.getStyle().withObfuscated(true);
                    lines.set(i, Component.literal(text).withStyle(newStyle));
                    break; // 一行只处理一次
                }
            }
        }
    }
}