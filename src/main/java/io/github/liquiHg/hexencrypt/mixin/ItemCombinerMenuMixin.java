package io.github.liquiHg.hexencrypt.mixin;

import com.mojang.logging.LogUtils;
import io.github.liquiHg.hexencrypt.Config;
import io.github.liquiHg.hexencrypt.util.SpellUnlockManager;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.gui.arcane_anvil.ArcaneAnvilMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ItemCombinerMenu.class)
public abstract class ItemCombinerMenuMixin {

    @Unique
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 在 slotsChanged 的 TAIL 只负责"排队"，不立即处理。
     * 把真正的拒绝逻辑延迟到当前 tick 结束后执行，
     * 避免与原版 AbstractContainerMenu.doClick 的 shift+左击逻辑冲突。
     */
    @Inject(method = "slotsChanged", at = @At("TAIL"))
    private void hexencrypt$onSlotsChanged(Container container, CallbackInfo ci) {
        if (!Config.enableEncryption) return;

        // 只对 ArcaneAnvilMenu 生效
        if (!((Object) this instanceof ArcaneAnvilMenu)) return;

        ItemCombinerMenu self = (ItemCombinerMenu) (Object) this;
        Player player = ((ItemCombinerMenuAccessor) (Object) this).hexencrypt$getPlayer();
        if (player == null) return;
        if (player.level().isClientSide()) return; // 只在服务端处理

        MinecraftServer server = player.getServer();
        if (server == null) return;

        // 延迟到本 tick 结束后处理
        server.execute(() -> hexencrypt$processRejection(self, player));
    }

    @Unique
    private static void hexencrypt$processRejection(ItemCombinerMenu self, Player player) {
        // 玩家可能已经关闭了这个菜单，或换到了别的菜单
        if (player.containerMenu != self) return;

        for (int i = 0; i < 2; i++) {
            ItemStack stack = self.getSlot(i).getItem();
            if (stack.isEmpty()) continue;
            if (!hexencrypt$hasLockedSpell(player, stack)) continue;

            LOGGER.info("[HexEncrypt] Rejecting locked item from slot {}: {}", i, stack);

            // 1. 复制一份，然后清空槽位
            ItemStack toReturn = stack.copy();
            self.getSlot(i).set(ItemStack.EMPTY);

            // 2. 用原版方法把物品还给玩家
            //    内部会依次尝试：合并到已有堆叠 → 放入空槽 → 掉落到地上
            player.getInventory().placeItemBackInInventory(toReturn);

            // 3. 聊天栏提示
            player.displayClientMessage(
                    Component.translatable("hexencrypt.message.spell_locked"),
                    false
            );

            // 4. 关闭 GUI
            player.closeContainer();

            // 一次只处理一个，处理完就退出
            return;
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