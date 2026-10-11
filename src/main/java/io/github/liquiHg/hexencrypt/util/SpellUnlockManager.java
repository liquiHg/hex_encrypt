package io.github.liquiHg.hexencrypt.util;

import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.github.liquiHg.hexencrypt.client.ClientSpellCache;
import io.github.liquiHg.hexencrypt.network.ModNetwork;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.item.Scroll;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import java.util.*;

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

        return isSpellUnlockedClientSide(spell);
    }

    /** 判断玩家是否已解锁指定法术（服务端权威，客户端走缓存）。 */
    public static boolean isSpellUnlocked(Player player, AbstractSpell spell) {
        if (player == null || spell == null) return false;

        if (player.level().isClientSide()) {
            return isSpellUnlockedClientSide(spell);
        }
        return getUnlockedSpells(player).contains(spell.getSpellName());
    }

    /**
     * 仅通过客户端缓存判断法术是否解锁。
     * 不需要 Player 引用，服务端调用时返回 false（不会误伤服务端逻辑）。
     */
    public static boolean isSpellUnlockedClientSide(AbstractSpell spell) {
        if (spell == null) return false;
        Boolean result = DistExecutor.unsafeCallWhenOn(Dist.CLIENT,
                () -> () -> ClientSpellCache.isUnlocked(spell.getSpellName()));
        return result != null && result;
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

    // ==================== 环境判定 ====================

    /**
     * 判断 CastingEnvironment 是否来自"玩家主动打开 Hex 网格绘制"。
     *
     * Hex Casting 的 CastingEnvironment 有若干实现：
     *   - StaffCastEnv    —— 手持法杖主动绘制        ← 允许解锁
     *   - CyipherCastEnv  —— 手持画符主动绘制        ← 允许解锁
     *   - TrinketCastEnv  —— 饰品/造物被动触发       ← 禁止解锁
     *   - CircleCastEnv   —— 法术环自动施法          ← 禁止解锁
     *   - VillagerCastEnv —— NPC 施法                ← 禁止解锁
     *
     * 只允许主动绘制解锁，避免玩家摆一个 Hex 造物就能批量解锁，
     * 失去探索 / 亲手施法解锁的设计目的。
     *
     * 通过类名字符串匹配实现，避免依赖 Hex Casting 不同版本中
     * CastingEnvironment 子类的具体包路径。
     * 若你的 Hex Casting 版本中类名规则不同（例如没有 "Cyipher"），
     * 请换成对应关键字，或改用 instanceof 精确判定。
     */
    public static boolean isPlayerInitiatedCasting(Object env) {
        if (env == null) return false;
        String name = env.getClass().getSimpleName();
        return name.contains("Staff") || name.contains("Cyipher");
    }

    // ==================== 施法解锁入口 ====================

    /**
     * 玩家成功施展某 ISS 法术时调用。
     * 由 OpCastIronsSpell / OpCastIronsSpellWithTarget / OpCastContinuousIronsSpell
     * 在 spell.cast(...) 成功之后调用。
     *
     * 行为：
     *   - 服务端：若未解锁则写入持久化数据，并触发网络同步；
     *   - 客户端：直接返回（客户端不持有权威数据）。
     *
     * @return true 表示本次调用实际解锁了该法术（用于日志/成就判定）
     */
    public static boolean tryUnlockByCasting(Player player, AbstractSpell spell) {
        if (player == null || spell == null) return false;
        if (player.level().isClientSide()) return false;
        if (!spell.isEnabled()) return false;

        String spellName = spell.getSpellName();
        if (spellName == null || spellName.isEmpty() || "none".equals(spellName)) {
            return false;
        }

        Set<String> current = new HashSet<>(getUnlockedSpells(player));
        if (current.contains(spellName)) return false;

        current.add(spellName);
        setUnlockedSpells(player, current);

        if (player instanceof ServerPlayer sp) {
            ModNetwork.sendUnlockedSpells(sp, current);
            sendUnlockFeedback(sp, spell);              // ← 新增
        }

        HexEncrypt.LOGGER.info("[Hex加密] 玩家 {} 通过施法解锁了 ISS 法术：{}",
                player.getName().getString(), spellName);
        return true;
    }

    // ==================== 解锁反馈：提示 + 粒子 ====================

    /**
     * 玩家首次解锁某个 ISS 法术时：
     *   1. 发一条紫色提示到 ActionBar；
     *   2. 在玩家周围生成一圈附魔台粒子（带随机抖动）。
     *
     * 只在"真正新解锁"时调用，不会重复播放。
     */
    private static void sendUnlockFeedback(ServerPlayer player, AbstractSpell spell) {
        Component spellLabel = Component.translatable(
                "spell.irons_spellbooks." + spell.getSpellName());
        Component msg = Component.translatable(
                        "hexencrypt.unlock.success", spellLabel)
                .withStyle(ChatFormatting.LIGHT_PURPLE);
        player.displayClientMessage(msg, true);

        // ---------- 2. 粒子 ----------
        ServerLevel level = player.serverLevel();
        RandomSource rand = player.getRandom();   // ← 换成 RandomSource

        // 2.1 主环：环绕玩家一周，角度 / 半径 / 高度都带抖动，避免看起来像量角器
        final int ringCount = 48;
        final double baseRadius = 0.85;
        for (int i = 0; i < ringCount; i++) {
            double angle  = (i / (double) ringCount) * Math.PI * 2.0
                    + (rand.nextDouble() - 0.5) * 0.40;                  // 角度抖动 ±0.2rad
            double radius = baseRadius
                    + (rand.nextDouble() - 0.5) * 0.35;                  // 半径抖动 ±0.175
            double x = player.getX() + Math.cos(angle) * radius;
            double z = player.getZ() + Math.sin(angle) * radius;
            double y = player.getY() + 0.25 + rand.nextDouble() * 1.55;        // 高度 0.25~1.8

            level.sendParticles(
                    ParticleTypes.ENCHANT,
                    x, y, z,
                    1,            // count
                    0.0, 0.0, 0.0, // 无额外扩散
                    0.35);        // speed
        }

        // 2.2 副环：脚边一圈稀疏粒子，让视觉效果更"落地"
        final int footCount = 16;
        for (int i = 0; i < footCount; i++) {
            double angle  = rand.nextDouble() * Math.PI * 2.0;
            double radius = 1.15 + rand.nextDouble() * 0.30;
            double x = player.getX() + Math.cos(angle) * radius;
            double z = player.getZ() + Math.sin(angle) * radius;
            double y = player.getY() + 0.05;

            level.sendParticles(
                    ParticleTypes.ENCHANT,
                    x, y, z,
                    1,
                    0.0, 0.0, 0.0,
                    0.30);
        }
    }

    /**
     * 便捷：直接通过法术 ID 解锁（Op 里一般拿得到 ResourceLocation）。
     */
    public static boolean tryUnlockBySpellId(Player player, ResourceLocation spellId) {
        if (spellId == null) return false;
        AbstractSpell spell = SpellRegistry.getSpell(spellId);
        return tryUnlockByCasting(player, spell);
    }
}