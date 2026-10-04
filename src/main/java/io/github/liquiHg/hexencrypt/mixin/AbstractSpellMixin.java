package io.github.liquiHg.hexencrypt.mixin;

import io.github.liquiHg.hexencrypt.Config;
import io.github.liquiHg.hexencrypt.util.SpellUnlockManager;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractSpell.class)
public class AbstractSpellMixin {

    /** 施法物品被拒绝时的冷却时间（tick）。100 tick = 5 秒。 */
    @Unique
    private static final int HEXENCRYPT_LOCKED_CAST_COOLDOWN = 100;

    /** 未解锁法术在 GUI 上显示的锁图标。 */
    @Unique
    private static final ResourceLocation HEXENCRYPT_LOCKED_ICON =
            new ResourceLocation("hexencrypt", "textures/gui/locked_spell_.png");

    /**
     * 禁止未解锁法术施法，并给施法物品添加 5 秒冷却 + 发送提示。
     */
    @Inject(method = "attemptInitiateCast", at = @At("HEAD"), cancellable = true, remap = false)
    private void hexencrypt$blockUnlearnedCast(
            ItemStack stack, int spellLevel, Level level, Player player,
            CastSource source, boolean triggerCooldown, String castingEquipmentSlot,
            CallbackInfoReturnable<Boolean> cir) {

        if (!Config.enableEncryption || player == null) return;

        AbstractSpell spell = (AbstractSpell) (Object) this;
        if (SpellUnlockManager.isSpellUnlocked(player, spell)) return;

        // 客户端只需返回 false 阻止本地动画；提示和冷却交给服务端统一处理
        if (level != null && !level.isClientSide()) {
            // 1. 提示（actionbar 显示，避免聊天栏刷屏）
            player.displayClientMessage(
                    Component.translatable("hexencrypt.message.spell_locked"),
                    true
            );

            // 2. 给施法物品添加 5 秒冷却
            //    服务端调用后，Forge 会自动同步到客户端，冷却动画正常显示
            if (stack != null && !stack.isEmpty()) {
                player.getCooldowns().addCooldown(
                        stack.getItem(),
                        HEXENCRYPT_LOCKED_CAST_COOLDOWN
                );
            }
        }

        cir.setReturnValue(false);
    }

    /**
     * 未解锁时替换法术图标为一把锁。
     * getSpellIconResource 是 AbstractSpell 中 final 定义的方法，Mixin 可正常注入。
     * 该方法会被所有 UI（法术轮盘、法术栏、JEI、tooltip 等）调用，
     * 因此替换这里即可统一覆盖所有显示位置。
     */
    @Inject(method = "getSpellIconResource", at = @At("HEAD"), cancellable = true, remap = false)
    private void hexencrypt$replaceIconIfLocked(CallbackInfoReturnable<ResourceLocation> cir) {
        if (!Config.enableEncryption) return;

        // 只在客户端处理，避免服务端触碰 Minecraft 客户端单例
        if (!FMLEnvironment.dist.isClient()) return;

        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        AbstractSpell spell = (AbstractSpell) (Object) this;
        if (!SpellUnlockManager.isSpellUnlocked(player, spell)) {
            cir.setReturnValue(HEXENCRYPT_LOCKED_ICON);
        }
    }

    /** 卷轴撰写台：未解锁则无法抄写，卡片显示"必须先学习此法术"。 */
    @Inject(method = "canBeCraftedBy", at = @At("HEAD"), cancellable = true, remap = false)
    private void hexencrypt$blockUnlearnedCrafting(
            Player player, CallbackInfoReturnable<Boolean> cir) {

        if (!Config.enableEncryption || player == null) return;
        if (!player.level().isClientSide()) return;

        AbstractSpell spell = (AbstractSpell) (Object) this;
        if (!SpellUnlockManager.isSpellUnlocked(player, spell)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * 基础混淆状态：让 ISS 内置的混淆机制生效。
     * 返回 true 时：
     *   - createSpellDescriptionTooltip 不再显示描述文本
     *   - getStyleFor 使用 OBFUSCATED_STYLE
     */
    @Inject(method = "obfuscateStats", at = @At("HEAD"), cancellable = true, remap = false)
    private void hexencrypt$forceObfuscateStats(
            Player player, CallbackInfoReturnable<Boolean> cir) {

        if (!Config.enableEncryption || player == null) return;

        AbstractSpell spell = (AbstractSpell) (Object) this;
        if (!SpellUnlockManager.isSpellUnlocked(player, spell)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * 双保险：直接强制 getDisplayName 返回带 obfuscated 样式的组件。
     * 不依赖 obfuscateStats，避免某些 UI 绕过混淆逻辑。
     */
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true, remap = false)
    private void hexencrypt$obfuscateDisplayName(
            Player player, CallbackInfoReturnable<MutableComponent> cir) {

        if (!Config.enableEncryption || player == null) return;

        AbstractSpell spell = (AbstractSpell) (Object) this;
        if (!SpellUnlockManager.isSpellUnlocked(player, spell)) {
            MutableComponent original = cir.getReturnValue();
            if (original == null) return;
            cir.setReturnValue(original.withStyle(original.getStyle().withObfuscated(true)));
        }
    }
}