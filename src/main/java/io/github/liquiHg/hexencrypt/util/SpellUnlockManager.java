package io.github.liquiHg.hexencrypt.util;

import io.github.liquiHg.hexencrypt.client.ClientSpellCache;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.item.Scroll;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SpellUnlockManager {

    // ISS 3.15.2 中卷轴/法术书/注魔武器共用的 NBT 结构：
    // tag -> "irons_spellbooks:spell_container" -> "data" -> ListTag[CompoundTag]
    //   每个 CompoundTag 含 "id"（法术 ResourceLocation）、"level"、"index"、"locked" 等
    private static final String SPELL_CONTAINER_KEY = "irons_spellbooks:spell_container";
    private static final String SPELL_DATA_KEY = "data";
    private static final String SPELL_ID_FIELD = "id";

    // 玩家持久化数据中存储解锁集合的 key
    private static final String UNLOCKED_SPELLS_KEY = "hexencrypt:unlocked_spells";

    // ==================== 判断物品类型 ====================

    public static boolean isScroll(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof Scroll;
    }

    // ==================== 解析物品中的法术 ====================

    /**
     * 读取物品（卷轴/法术书/注魔武器等）中所有已包含的法术。
     * 返回的列表可能为空（不是法术容器或没有法术）。
     */
    public static List<AbstractSpell> getSpellsFromItem(ItemStack stack) {
        List<AbstractSpell> result = new ArrayList<>();
        if (stack == null || stack.isEmpty() || !stack.hasTag()) return result;

        CompoundTag tag = stack.getTag();
        if (tag == null) return result;

        if (!tag.contains(SPELL_CONTAINER_KEY, 10)) return result; // 10 = CompoundTag
        CompoundTag container = tag.getCompound(SPELL_CONTAINER_KEY);
        if (!container.contains(SPELL_DATA_KEY, 9)) return result; // 9 = ListTag
        ListTag dataList = container.getList(SPELL_DATA_KEY, 10);  // 10 = CompoundTag

        for (int i = 0; i < dataList.size(); i++) {
            CompoundTag entry = dataList.getCompound(i);
            String idStr = entry.getString(SPELL_ID_FIELD);
            if (idStr.isEmpty()) continue;

            ResourceLocation spellId = ResourceLocation.tryParse(idStr);
            if (spellId == null) continue;

            AbstractSpell spell = SpellRegistry.getSpell(spellId);
            if (spell != null && !"none".equals(spell.getSpellName())) {
                result.add(spell);
            }
        }
        return result;
    }

    /** 便捷方法：读取卷轴中的第一个法术。 */
    public static AbstractSpell getSpellFromScroll(ItemStack stack) {
        if (!isScroll(stack)) return null;
        List<AbstractSpell> spells = getSpellsFromItem(stack);
        return spells.isEmpty() ? null : spells.get(0);
    }

    // ==================== 解锁状态查询 ====================

    /** 客户端判断物品（卷轴）是否已解锁，用于物品名称混淆。 */
    public static boolean isSpellUnlocked(ItemStack stack) {
        AbstractSpell spell = getSpellFromScroll(stack);
        if (spell == null) return true; // 不是有效卷轴，视为已解锁，不处理

        Boolean result = DistExecutor.unsafeCallWhenOn(Dist.CLIENT,
                () -> () -> ClientSpellCache.isUnlocked(spell.getSpellName()));
        return result != null && result;
    }

    /** 判断玩家是否已解锁指定法术（服务端权威，客户端走缓存）。 */
    public static boolean isSpellUnlocked(Player player, AbstractSpell spell) {
        if (player == null || spell == null) return false;

        if (player.level().isClientSide()) {
            Boolean result = DistExecutor.unsafeCallWhenOn(Dist.CLIENT,
                    () -> () -> ClientSpellCache.isUnlocked(spell.getSpellName()));
            return result != null && result;
        }
        return getUnlockedSpells(player).contains(spell.getSpellName());
    }

    // ==================== 修改解锁状态（仅服务端） ====================

    /** 解锁指定法术。使用 Set 整体重建，避免 ListTag 原地修改问题。 */
    public static boolean unlockSpell(Player player, AbstractSpell spell) {
        if (player == null || spell == null || player.level().isClientSide()) return false;

        Set<String> current = new HashSet<>(getUnlockedSpells(player));
        if (!current.add(spell.getSpellName())) {
            return false; // 已存在
        }
        setUnlockedSpells(player, current);
        return true;
    }

    /** 锁定指定法术。使用 Set 整体重建，避免 ListTag 原地修改问题。 */
    public static boolean lockSpell(Player player, AbstractSpell spell) {
        if (player == null || spell == null || player.level().isClientSide()) return false;

        Set<String> current = new HashSet<>(getUnlockedSpells(player));
        if (!current.remove(spell.getSpellName())) {
            return false; // 本来就没解锁
        }
        setUnlockedSpells(player, current);
        return true;
    }

    // ==================== 持久化读写 ====================

    /** 获取玩家已解锁的所有法术名称集合（仅服务端）。 */
    public static Set<String> getUnlockedSpells(Player player) {
        if (player == null || player.level().isClientSide()) {
            return Collections.emptySet();
        }
        CompoundTag data = player.getPersistentData();
        if (!data.contains(UNLOCKED_SPELLS_KEY, 9)) { // 9 = ListTag
            return Collections.emptySet();
        }
        ListTag list = data.getList(UNLOCKED_SPELLS_KEY, 8); // 8 = StringTag
        Set<String> result = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String s = list.getString(i);
            if (!s.isEmpty()) result.add(s);
        }
        return result;
    }

    /** 将解锁集合整体写回玩家持久化数据（仅服务端）。 */
    public static void setUnlockedSpells(Player player, Set<String> spells) {
        if (player == null || player.level().isClientSide()) return;
        CompoundTag data = player.getPersistentData();
        ListTag list = new ListTag();
        for (String s : spells) {
            if (s != null && !s.isEmpty()) {
                list.add(StringTag.valueOf(s));
            }
        }
        // 即使为空也显式写入空列表，确保旧数据被覆盖而不是残留
        data.put(UNLOCKED_SPELLS_KEY, list);
    }
}