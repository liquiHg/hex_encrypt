package io.github.liquiHg.hexencrypt.mixin;

import io.github.liquiHg.hexencrypt.Config;
import io.github.liquiHg.hexencrypt.util.SpellUnlockManager;
import io.redspace.ironsspellbooks.api.magic.SpellSelectionManager;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpellSelectionManager.class)
public class SpellSelectionManagerMixin {

    /**
     * 拦截客户端"切换当前法术"的动作。未解锁则直接取消。
     * 这样无论从轮盘、法术栏、快捷键还是其他 UI 切换，都会被拦下。
     */
    @Inject(method = "makeSelection", at = @At("HEAD"), cancellable = true, remap = false)
    private void hexencrypt$blockLockedSelection(int index, CallbackInfo ci) {
        if (!Config.enableEncryption) return;

        SpellSelectionManager self = (SpellSelectionManager) (Object) this;
        if (index < 0 || index >= self.getSpellCount()) return;

        SpellData data = self.getSpellData(index);
        if (data == null || data == SpellData.EMPTY) return;

        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        if (!SpellUnlockManager.isSpellUnlocked(player, data.getSpell())) {
            ci.cancel();
        }
    }
}