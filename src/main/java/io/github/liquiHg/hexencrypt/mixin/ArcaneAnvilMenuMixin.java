package io.github.liquiHg.hexencrypt.mixin;

import com.mojang.logging.LogUtils;
import io.github.liquiHg.hexencrypt.Config;
import io.github.liquiHg.hexencrypt.util.SpellUnlockManager;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.gui.arcane_anvil.ArcaneAnvilMenu;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ArcaneAnvilMenu.class)
public abstract class ArcaneAnvilMenuMixin {

    @Unique
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 在 createResult 结束时检查输入槽。如果含有未解锁法术，把结果槽清空，
     * 玩家点击时就拿不到任何东西。
     */
    @Inject(method = "createResult", at = @At("TAIL"))
    private void hexencrypt$blockLockedResult(CallbackInfo ci) {
        if (!Config.enableEncryption) return;

        // 关键修复：必须先转 Object，再转目标类型
        ArcaneAnvilMenu self = (ArcaneAnvilMenu) (Object) this;
        Player player = ((ItemCombinerMenuAccessor) (Object) this).hexencrypt$getPlayer();
        if (player == null) return;

        ItemStack base = self.getSlot(0).getItem();
        ItemStack modifier = self.getSlot(1).getItem();

        boolean baseLocked = hexencrypt$hasLockedSpell(player, base);
        boolean modifierLocked = hexencrypt$hasLockedSpell(player, modifier);

        if (baseLocked || modifierLocked) {
            LOGGER.info("[HexEncrypt] Clearing result slot: baseLocked={}, modifierLocked={}",
                    baseLocked, modifierLocked);
            self.getSlot(2).set(ItemStack.EMPTY);
        }
    }

    @Unique
    private static boolean hexencrypt$hasLockedSpell(Player player, ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) return false;

        List<AbstractSpell> spells = SpellUnlockManager.getSpellsFromItem(stack);
        for (AbstractSpell spell : spells) {
            if (!SpellUnlockManager.isSpellUnlocked(player, spell)) {
                LOGGER.info("[HexEncrypt] Locked spell detected: {}", spell.getSpellName());
                return true;
            }
        }
        return false;
    }
}