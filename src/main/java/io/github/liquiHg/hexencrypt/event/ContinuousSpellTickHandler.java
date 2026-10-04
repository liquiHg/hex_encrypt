package io.github.liquiHg.hexencrypt.event;

import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.config.ServerConfigs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = HexEncrypt.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ContinuousSpellTickHandler {

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        var server = event.getServer();
        if (server == null) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            MagicData magicData = MagicData.getPlayerMagicData(player);

            if (magicData.isCasting() && "hex_encrypt".equals(magicData.getCastingEquipmentSlot())) {
                String spellId = magicData.getCastingSpellId();
                AbstractSpell spell = SpellRegistry.getSpell(spellId);
                int level = magicData.getCastingSpellLevel();

                HexEncrypt.LOGGER.info("[Hex持续法术] 玩家: {}, 法术: {}, 剩余Tick: {}, 当前法力值: {}",
                        player.getName().getString(), spellId, magicData.getCastDurationRemaining(), magicData.getMana());

                if (spell != null && spell != SpellRegistry.none()) {
                    // 【重要】不要在这里调用 magicData.handleCastDuration()！
                    // ISS 自身的施法系统已经在每个 Tick 自动扣减持续时间。
                    // 手动再扣一次会导致持续时间以两倍速度消耗。

                    // 持续消耗法力值（基于法术原本的持续时间计算每 Tick 消耗，避免玩家自定义时长导致消耗过低）
                    if (!player.isCreative() || ServerConfigs.CREATIVE_MANA_COST.get()) {
                        int baseDuration = spell.getCastTime(level);
                        if (baseDuration > 0) {
                            float manaCostPerTick = (float) spell.getManaCost(level) / baseDuration;
                            float newMana = Math.max(magicData.getMana() - manaCostPerTick, 0.0F);
                            magicData.setMana(newMana);
                        }
                    }

                    // 执行法术自身的 Tick 逻辑
                    spell.onServerCastTick(player.level(), level, player, magicData);

                    // 检查是否中断（时间耗尽或法力值不足）
                    if (magicData.getCastDurationRemaining() <= 0 || magicData.getMana() <= 0) {
                        HexEncrypt.LOGGER.info("[Hex持续法术] 结束施法: {}, 原因: 时间耗尽或法力值不足", spellId);

                        // 强制清理投射物实体
                        magicData.resetAdditionalCastData();

                        // 通知法术施法完成（触发冷却，重置状态）
                        spell.onServerCastComplete(player.level(), level, player, magicData, true);
                    }
                } else {
                    magicData.resetCastingState();
                }
            }
        }
    }
}