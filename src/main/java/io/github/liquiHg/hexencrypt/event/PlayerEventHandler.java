package io.github.liquiHg.hexencrypt.event;

import io.github.liquiHg.hexencrypt.Config;
import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.github.liquiHg.hexencrypt.network.ModNetwork;
import io.github.liquiHg.hexencrypt.util.SpellUnlockManager;
import io.redspace.ironsspellbooks.api.events.InscribeSpellEvent;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Set;

@Mod.EventBusSubscriber(modid = HexEncrypt.MODID)
public class PlayerEventHandler {

    /** 死亡重生时把解锁数据从旧实体复制到新实体。 */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) return;

        Player oldPlayer = event.getOriginal();
        Player newPlayer = event.getEntity();

        Set<String> unlocked = SpellUnlockManager.getUnlockedSpells(oldPlayer);
        SpellUnlockManager.setUnlockedSpells(newPlayer, unlocked);
    }

    /** 玩家登录时把解锁数据同步到客户端缓存。 */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        if (player instanceof ServerPlayer serverPlayer) {
            Set<String> unlocked = SpellUnlockManager.getUnlockedSpells(serverPlayer);
            ModNetwork.sendUnlockedSpells(serverPlayer, unlocked);
        }
    }

    /**
     * 阻止玩家向法术书刻录未解锁的法术。
     * InscribeSpellEvent 继承 PlayerEvent，注册在 Forge 总线上，@SubscribeEvent 即可监听到。
     */
    @SubscribeEvent
    public static void onInscribeSpell(InscribeSpellEvent event) {
        if (!Config.enableEncryption) return;

        Player player = event.getEntity();
        SpellData data = event.getSpellData();
        if (player == null || data == null || data.getSpell() == null) return;

        if (!SpellUnlockManager.isSpellUnlocked(player, data.getSpell())) {
            event.setCanceled(true);
        }
    }
}