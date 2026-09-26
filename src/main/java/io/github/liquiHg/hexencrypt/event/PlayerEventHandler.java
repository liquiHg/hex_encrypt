package io.github.liquiHg.hexencrypt.event;

import io.github.liquiHg.hexencrypt.network.ModNetwork;
import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.github.liquiHg.hexencrypt.util.SpellUnlockManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Set;

@Mod.EventBusSubscriber(modid = HexEncrypt.MODID)
public class PlayerEventHandler {

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) return;
        Player oldPlayer = event.getOriginal();
        Player newPlayer = event.getEntity();

        Set<String> unlocked = SpellUnlockManager.getUnlockedSpells(oldPlayer);
        SpellUnlockManager.setUnlockedSpells(newPlayer, unlocked);
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        if (player instanceof ServerPlayer serverPlayer) {
            Set<String> unlocked = SpellUnlockManager.getUnlockedSpells(serverPlayer);
            ModNetwork.sendUnlockedSpells(serverPlayer, unlocked);
        }
    }
}