package io.github.liquiHg.hexencrypt.mixin;

import io.github.liquiHg.hexencrypt.Config;
import io.github.liquiHg.hexencrypt.util.SpellUnlockManager;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractSpell.class)
public class AbstractSpellMixin {

    /** 禁止未解锁法术施法。 */
    @Inject(method = "attemptInitiateCast", at = @At("HEAD"), cancellable = true, remap = false)
    private void hexencrypt$blockUnlearnedCast(
            ItemStack stack, int spellLevel, Level level, Player player,
            CastSource source, boolean triggerCooldown, String castingEquipmentSlot,
            CallbackInfoReturnable<Boolean> cir) {

        if (!Config.enableEncryption) return;
        if (player == null) return;

        AbstractSpell spell = (AbstractSpell) (Object) this;
        if (!SpellUnlockManager.isSpellUnlocked(player, spell)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * 让未解锁的法术无法被"抄写"。
     * ISS 的 ScrollForgeScreen 会通过 canBeCraftedBy 决定卡片是否可点，
     * 返回 false 即可让未解锁法术在法术抄写台上变灰、无法选定。
     */
    @Inject(method = "canBeCraftedBy", at = @At("HEAD"), cancellable = true, remap = false)
    private void hexencrypt$blockUnlearnedCrafting(
            Player player, CallbackInfoReturnable<Boolean> cir) {

        if (!Config.enableEncryption) return;
        if (player == null) return;
        // 只在客户端影响 UI 状态，避免影响服务端逻辑
        if (!player.level().isClientSide()) return;

        AbstractSpell spell = (AbstractSpell) (Object) this;
        if (!SpellUnlockManager.isSpellUnlocked(player, spell)) {
            cir.setReturnValue(false);
        }
    }

    /** 混淆 UI 里显示的法术名。 */
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true, remap = false)
    private void hexencrypt$obfuscateDisplayName(
            Player player, CallbackInfoReturnable<Component> cir) {

        if (!Config.enableEncryption) return;
        if (player == null) return;

        AbstractSpell spell = (AbstractSpell) (Object) this;
        if (SpellUnlockManager.isSpellUnlocked(player, spell)) return;

        Component original = cir.getReturnValue();
        if (original == null) return;

        cir.setReturnValue(Component.literal(original.getString())
                .withStyle(original.getStyle().withObfuscated(true)));
    }
}