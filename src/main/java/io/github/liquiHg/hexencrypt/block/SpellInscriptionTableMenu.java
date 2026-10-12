package io.github.liquiHg.hexencrypt.block;

import at.petrak.hexcasting.api.casting.iota.GarbageIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.math.HexAngle;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.api.item.IotaHolderItem;
import at.petrak.hexcasting.common.items.storage.ItemScroll;
import at.petrak.hexcasting.common.lib.HexItems;
import at.petrak.hexcasting.common.lib.HexSounds;
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes;
import io.github.liquiHg.hexencrypt.api.ComparisonResult;
import io.github.liquiHg.hexencrypt.integration.ISSHexIntegration;
import io.github.liquiHg.hexencrypt.registry.ModBlocks;
import io.github.liquiHg.hexencrypt.registry.ModMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.block.Block;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class SpellInscriptionTableMenu extends AbstractContainerMenu {

    // === 布局（256×256 采样） ===
    private static final int INVENTORY_TOP     = 174;
    private static final int PLAYER_INV_LEFT   = 47;
    private static final int PLAYER_HOTBAR_TOP = 232;

    private static final int SLOT_LEFT_LOCAL = 120;
    private static final int SLOT_TOP_LOCAL  = 79;

    /** 媒质槽（右侧）。 */
    public static final int MEDIA_SLOT_X = 264;
    public static final int MEDIA_SLOT_Y = 23;

    /** 竖直媒质条。 */
    public static final int MEDIA_BAR_X = 256;
    public static final int MEDIA_BAR_Y = 40;
    public static final int MEDIA_BAR_W = 8;
    public static final int MEDIA_BAR_H = 80;

    // === 消耗 ===
    public static final int COST_DRAW = 1;
    public static final int COST_UNDO = 2;

    /** 匹配分类结果。 */
    private enum MatchKind {
        DEEP,      // 顺序正确的 school + target + cast
        SHALLOW,   // 至少有一段不是官能团
        MISHAP     // 三段都是官能团，但顺序不对
    }

    // ==================== 官能团分类（用于深度匹配判定） ====================

    /** school 段集合：10 个学派 + fallback。 */
    private static final Set<String> SCHOOL_SIGS = Set.of(
            ISSHexIntegration.SCHOOL_ELDRITCH,
            ISSHexIntegration.SCHOOL_NATURE,
            ISSHexIntegration.SCHOOL_LIGHTNING,
            ISSHexIntegration.SCHOOL_ICE,
            ISSHexIntegration.SCHOOL_BLOOD,
            ISSHexIntegration.SCHOOL_EVOCATION,
            ISSHexIntegration.SCHOOL_HOLY,
            ISSHexIntegration.SCHOOL_ENDER,
            ISSHexIntegration.SCHOOL_FIRE,
            ISSHexIntegration.SCHOOL_FALLBACK
    );

    /** target 段集合：3 个目标类型。 */
    private static final Set<String> TARGET_SIGS = Set.of(
            ISSHexIntegration.TARGET_SELF,
            ISSHexIntegration.TARGET_OTHER,
            ISSHexIntegration.TARGET_SUMMON
    );

    /** cast 段集合：4 种施法类型。 */
    private static final Set<String> CAST_SIGS = Set.of(
            ISSHexIntegration.CAST_NONE,
            ISSHexIntegration.CAST_INSTANT,
            ISSHexIntegration.CAST_CONTINUOUS,
            ISSHexIntegration.CAST_LONG
    );

    private final SpellInscriptionTableBlockEntity blockEntity;
    private final Container container;          // 中央槽
    private final Container mediaContainer;     // 媒质槽
    private final ContainerLevelAccess access;

    /** 客户端缓存（ContainerData.set 里被写入；服务端不读）。 */
    private final int[] syncedMedia = { 0 };

    /**
     * 同步媒体值的 ContainerData。
     * 服务端：get(0) 读 blockEntity 实时值 → 每 tick broadcastChanges 时对比并发送。
     * 客户端：收到 ClientboundContainerSetDataPacket 时调用 set(...) → 写入 syncedMedia[0]。
     */
    private final ContainerData mediaData = new ContainerData() {
        @Override
        public int get(int index) {
            if (blockEntity != null) {
                // 服务端实时读；封顶到 int.MAX_VALUE 防止溢出
                return (int) Math.min(Integer.MAX_VALUE, blockEntity.getMedia());
            }
            // 客户端读缓存
            return syncedMedia[0];
        }

        @Override
        public void set(int index, int value) {
            syncedMedia[0] = value;
        }

        @Override
        public int getCount() {
            return 1;
        }
    };

    public SpellInscriptionTableMenu(int id, Inventory playerInv) {
        this(id, playerInv, null);
    }

    public SpellInscriptionTableMenu(int id, Inventory playerInv,
                                     @Nullable SpellInscriptionTableBlockEntity be) {
        super(ModMenuTypes.SPELL_INSCRIPTION_TABLE.get(), id);
        this.blockEntity = be;
        this.access = be != null
                ? ContainerLevelAccess.create(be.getLevel(), be.getBlockPos())
                : ContainerLevelAccess.NULL;

        // ⭐ 注册 ContainerData（必须在 addSlot 之前或之后都行，但要执行一次）
        this.addDataSlots(mediaData);

        // 中央槽
        ItemStack storedInitial = (be != null) ? be.getStoredItem() : ItemStack.EMPTY;
        this.container = new SimpleContainer(1);
        this.container.setItem(0, storedInitial);
        this.addSlot(new Slot(container, 0, SLOT_LEFT_LOCAL, SLOT_TOP_LOCAL) {
            @Override public int getMaxStackSize() { return 1; }
            @Override public void setChanged() {
                super.setChanged();
                if (blockEntity != null) blockEntity.setStoredItem(getItem());
            }
        });

        // 媒质槽
        ItemStack mediaInitial = (be != null) ? be.getMediaItem() : ItemStack.EMPTY;
        this.mediaContainer = new SimpleContainer(1);
        this.mediaContainer.setItem(0, mediaInitial);
        this.addSlot(new Slot(mediaContainer, 0, MEDIA_SLOT_X, MEDIA_SLOT_Y) {
            @Override public int getMaxStackSize() { return 64; }
            @Override public void setChanged() {
                super.setChanged();
                if (blockEntity != null) blockEntity.setMediaItem(getItem());
            }
        });

        // 玩家背包 3×9
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9,
                        PLAYER_INV_LEFT + col * 18, INVENTORY_TOP + row * 18));
            }
        }
        // 快捷栏
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, PLAYER_INV_LEFT + col * 18, PLAYER_HOTBAR_TOP));
        }
    }

    public ItemStack getStoredItem() { return container.getItem(0); }
    public SpellInscriptionTableBlockEntity getBlockEntity() { return blockEntity; }

    /**
     * 服务端返回 BE 实时值，客户端返回已同步缓存值。
     * ContainerData 每 tick 在 AbstractContainerMenu#broadcastChanges 里被 compare 并同步。
     */
    public long getMedia()    { return mediaData.get(0); }

    public long getMaxMedia() { return SpellInscriptionTableBlockEntity.MAX_MEDIA; }

    // ==================== 服务端动作（由网络包调用） ====================

    /**
     * @param quadrant 0..3
     * @param newDir   追加方向
     * @return true 表示成功
     */
    public boolean srvAppendEdge(int quadrant, HexDir newDir) {
        if (blockEntity == null) return false;
        if (quadrant < 0 || quadrant >= SpellInscriptionTableBlockEntity.QUADRANT_COUNT) return false;

        HexPattern existing = blockEntity.getQuadrantPattern(quadrant);
        HexPattern work;

        if (existing == null) {
            // 空象限：newDir 作为 startDir，angles 为空
            work = new HexPattern(newDir, new ArrayList<HexAngle>());
        } else {
            // 克隆后尝试追加
            List<HexAngle> angles = new ArrayList<>(existing.getAngles());
            work = new HexPattern(existing.getStartDir(), angles);
            if (!work.tryAppendDir(newDir)) {
                return false;   // 回绕 / 重叠 / 反转 → 拒绝
            }
        }

        if (!blockEntity.tryConsumeMedia(COST_DRAW)) return false;

        blockEntity.setQuadrantPattern(quadrant, work);
        return true;
    }

    /** 回退最后一条边。 */
    public boolean srvUndoEdge(int quadrant) {
        if (blockEntity == null) return false;
        HexPattern existing = blockEntity.getQuadrantPattern(quadrant);
        if (existing == null || existing.getAngles().isEmpty()) return false;
        if (!blockEntity.tryConsumeMedia(COST_UNDO)) return false;

        List<HexAngle> angles = new ArrayList<>(existing.getAngles());
        angles.remove(angles.size() - 1);
        HexPattern shortened = new HexPattern(existing.getStartDir(), angles);
        blockEntity.setQuadrantPattern(quadrant, shortened);
        return true;
    }

    /**
     * 创建并给予玩家一本包含解析结果的成书。
     */
    private static String pickBookTitle(Player player) {
        if (player instanceof ServerPlayer sp) {
            String lang = sp.getLanguage();      // ★ 用 getLanguage()
            if (lang.startsWith("zh")) return "法术解析结果";
        }
        return "Spell Analysis Result";
    }

    private void giveParseResultBook(Player player, ComparisonResult.Matched m) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag bookTag = new CompoundTag();

        bookTag.putString("title", pickBookTitle(player));
        bookTag.putString("author", player.getName().getString());
        bookTag.putInt("generation", 0);
        bookTag.putBoolean("resolved", true);

        ListTag pages = new ListTag();

        // ===== 第 1 页 =====
        MutableComponent page1 = Component.empty()          // ★ MutableComponent
                .append(Component.translatable("hexencrypt.book.parse.header"))
                .append("\n")
                .append(Component.translatable("hexencrypt.book.parse.matched"));
        pages.add(StringTag.valueOf(Component.Serializer.toJson(page1)));

        // ===== 第 2 页 =====
        MutableComponent page2 = Component.empty();         // ★ MutableComponent
        if (m.issSpellId() != null) {
            page2.append(Component.translatable(
                    "hexencrypt.book.parse.spellId",
                    m.issSpellId().toString()));
            page2.append("\n");
        }
        page2.append(Component.translatable(
                "hexencrypt.book.parse.groupCount",
                m.quadrantOrder().size()));
        pages.add(StringTag.valueOf(Component.Serializer.toJson(page2)));

        // ===== 第 3 页 =====
        int cQ = m.counts().getOrDefault('q', 0);
        int cW = m.counts().getOrDefault('w', 0);
        int cE = m.counts().getOrDefault('e', 0);
        int cA = m.counts().getOrDefault('a', 0);
        int cD = m.counts().getOrDefault('d', 0);

        MutableComponent page3 = Component.empty()          // ★ MutableComponent
                .append(Component.translatable(
                        "hexencrypt.book.parse.suffixLength", m.suffixLength()))
                .append("\n")
                .append(Component.translatable(
                        "hexencrypt.book.parse.suffixCounts",
                        cQ, cW, cE, cA, cD));
        pages.add(StringTag.valueOf(Component.Serializer.toJson(page3)));

        bookTag.put("pages", pages);
        book.setTag(bookTag);

        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
    }

    /**
     * 分类本次匹配。
     *
     * <ul>
     *   <li>3 段全部命中官能团且顺序为 school + target + cast → {@link MatchKind#DEEP}</li>
     *   <li>3 段全部命中官能团但顺序不对 → {@link MatchKind#MISHAP}</li>
     *   <li>有任意一段不是官能团 → {@link MatchKind#SHALLOW}</li>
     * </ul>
     */
    private MatchKind classifyMatch(ComparisonResult.Matched m) {
        List<Integer> order = m.quadrantOrder();
        if (order.size() != 3) return MatchKind.SHALLOW;

        List<String> sigs = new ArrayList<>(3);
        for (int q : order) {
            Iota iota = blockEntity.getQuadrantStackIota(q);
            if (!(iota instanceof PatternIota pi)) return MatchKind.SHALLOW;
            sigs.add(pi.getPattern().anglesSignature());
        }

        boolean allKnown = isKnownSegment(sigs.get(0))
                && isKnownSegment(sigs.get(1))
                && isKnownSegment(sigs.get(2));

        if (!allKnown) return MatchKind.SHALLOW;

        if (SCHOOL_SIGS.contains(sigs.get(0))
                && TARGET_SIGS.contains(sigs.get(1))
                && CAST_SIGS.contains(sigs.get(2))) {
            return MatchKind.DEEP;
        }

        return MatchKind.MISHAP;
    }

    private static boolean isKnownSegment(String sig) {
        return SCHOOL_SIGS.contains(sig)
                || TARGET_SIGS.contains(sig)
                || CAST_SIGS.contains(sig);
    }

    /**
     * 浅度探测的经验奖励分段函数。
     *
     * <pre>
     *   x 范围         经验值
     *   0  – 15        2x + 7
     *   16 – 30        5x - 38
     *   31 及以上      9x - 158
     * </pre>
     *
     * @param x 大型卷轴中的边数（即 {@code m.matchedSegmentEnd()}）
     */
    private static int calcShallowXp(int x) {
        if (x <= 15) return 2 * x + 7;
        if (x <= 30) return 5 * x - 38;
        return 9 * x - 158;
    }

    /**
     * 用和 FunctionalGroupComparator 相同的 indexOf 逻辑重跑一遍，
     * 返回"按顺序成功匹配"的象限列表。
     *
     * <p>中途遇到找不到的象限时停止——它以及它之后的都算未匹配。
     * <p>只有走 {@code matched.add(q)} 的象限才算匹配成功。
     *
     * <p>未匹配时用它决定哪些栈该被垃圾化（保留匹配成功的部分）。
     */
    private List<Integer> findMatchedQuadrants() {
        List<Integer> matched = new ArrayList<>();
        if (blockEntity == null) return matched;

        HexPattern central = blockEntity.asAccess().getCentralPattern();
        if (central == null) return matched;

        String fullSig = central.anglesSignature();
        int cursor = 0;

        for (int q = 0; q < SpellInscriptionTableBlockEntity.QUADRANT_COUNT; q++) {
            Iota iota = blockEntity.getQuadrantStackIota(q);
            if (!(iota instanceof PatternIota pi)) continue;
            String sub = pi.getPattern().anglesSignature();
            if (sub.isEmpty()) continue;

            int pos = fullSig.indexOf(sub, cursor);
            if (pos < 0) break;
            cursor = pos + sub.length();
            matched.add(q);
        }
        return matched;
    }

    public boolean srvTryParse(Player player) {
        if (blockEntity == null) {
            player.sendSystemMessage(Component.literal("§c[法术解析] 方块实体不存在"));
            return false;
        }

        ServerPlayer sp = (player instanceof ServerPlayer s) ? s : null;

        ComparisonResult result = blockEntity.runComparison(null, player);

        if (!(result instanceof ComparisonResult.Matched m)) {
            // 未匹配：保留"顺序找到的"，其他垃圾化
            handleMishap(player, findMatchedQuadrants());
            return false;
        }

// ============ 匹配成功，按"深度/浅度/事故"分流 ============
        MatchKind kind = classifyMatch(m);

// 事故：三段都是官能团但顺序错 → 全部垃圾化
        if (kind == MatchKind.MISHAP) {
            handleMishap(player, List.of());   // 传空列表 = 全部垃圾化
            return false;
        }

        boolean deep = (kind == MatchKind.DEEP);

        // 发书（仅深度）
        if (deep) {
            giveParseResultBook(player, m);
        }

        // 发卷轴（深度 + 浅度）
        ItemStack scroll = createTruncatedScroll(
                m.canonicalPattern(),
                m.matchedSegmentEnd());
        if (!player.getInventory().add(scroll)) {
            player.drop(scroll, false);
        }

        // 音效：深浅各一个
        if (sp != null) {
            if (deep) {
                sp.playNotifySound(
                        SoundEvents.ENCHANTMENT_TABLE_USE,
                        SoundSource.PLAYERS, 1.0f, 1.0f);
            } else {
                sp.playNotifySound(
                        SoundEvents.AMETHYST_BLOCK_CHIME,
                        SoundSource.PLAYERS, 1.0f, 1.2f);
            }
        }

        // 清空所有栈
        for (int q = 0; q < SpellInscriptionTableBlockEntity.QUADRANT_COUNT; q++) {
            blockEntity.clearQuadrantStack(q);
        }

        // 浅度专属反馈：actionbar + 粒子 + 经验
        if (!deep) {
            if (sp != null) {
                sp.displayClientMessage(
                        Component.translatable("hexencrypt.parse.shallow_match_actionbar"),
                        true);
            }

            if (player.level() instanceof ServerLevel sl) {
                sl.sendParticles(
                        ParticleTypes.ENCHANT,
                        player.getX(), player.getY() + 1.5, player.getZ(),
                        16, 0.6, 0.6, 0.6, 0.4);
            }

            int edges = m.matchedSegmentEnd();
            int xp = calcShallowXp(edges);
            if (xp > 0) {
                player.giveExperiencePoints(xp);
            }
        }

        return true;
    }

    /**
     * 未匹配时执行：
     *   1) 将方块中所有非空的栈内 iota 转为垃圾 iota
     *   2) 在方块位置模拟 Hex mishap 效果（粒子 + 音效）
     *   3) 关闭玩家打开的 UI
     *
     * <p>对照 Hex 的 {@code MishapInvalidIota}：
     *   - 事故强调色为 {@link net.minecraft.world.item.DyeColor#GRAY} → 灰色火花
     *   - 栈内无效 iota 被替换为 {@link GarbageIota}
     */
    private void handleMishap(Player player, List<Integer> matchedQuads) {
        // 1) 只垃圾化"未匹配"的非空栈
        for (int q = 0; q < SpellInscriptionTableBlockEntity.QUADRANT_COUNT; q++) {
            Iota iota = blockEntity.getQuadrantStackIota(q);
            if (iota == null) continue;                 // 空栈跳过
            if (matchedQuads.contains(q)) continue;     // 匹配成功的保留
            blockEntity.setQuadrantStackIota(q, new GarbageIota());
        }

        // 2) 粒子 + 音效
        if (blockEntity.getLevel() instanceof ServerLevel sl) {
            spawnMishapEffect(sl, blockEntity.getBlockPos());
        }

        // 3) 关闭 UI
        if (player instanceof ServerPlayer sp) {
            sp.playNotifySound(SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1.0f, 1.0f);
            sp.closeContainer();
        }
    }

    /**
     * 在指定位置模拟 Hex mishap 的视觉与听觉反馈。
     *
     * <p>粒子：灰色 dust 火花（对应 {@code MishapInvalidIota.accentColor} = DyeColor.GRAY）
     * <p>音效：{@link HexSounds#CAST_FAILURE} —— Hex 施法失败的官方音效
     */
    private static void spawnMishapEffect(ServerLevel level, BlockPos pos) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 1.0;
        double z = pos.getZ() + 0.5;

        // 灰色 dust —— 大约 (0.4, 0.4, 0.4)
        Vector3f gray = new Vector3f(0.4f, 0.4f, 0.4f);
        DustParticleOptions dust = new DustParticleOptions(gray, 1.0f);

        // 20~30 个火花，随机散开
        for (int i = 0; i < 25; i++) {
            double dx = (level.random.nextDouble() - 0.5) * 0.8;
            double dy = level.random.nextDouble() * 0.8;
            double dz = (level.random.nextDouble() - 0.5) * 0.8;
            level.sendParticles(dust, x, y, z, 1, dx, dy, dz, 0.05);
        }

        level.playSound(
                null,
                pos,
                HexSounds.CAST_FAILURE,
                SoundSource.BLOCKS,
                1.0f,
                1.0f);
    }

    /** 截取 fullPattern 的前 truncateLen 个角度字符，做成一张大卷轴。 */
    private ItemStack createTruncatedScroll(HexPattern fullPattern, int truncateLen) {
        String sig = fullPattern.anglesSignature();
        if (truncateLen > sig.length()) truncateLen = sig.length();
        String truncated = sig.substring(0, truncateLen);

        HexPattern t = HexPattern.fromAnglesUnchecked(truncated, fullPattern.getStartDir());

        ItemStack scroll = new ItemStack(HexItems.SCROLL_LARGE);
        if (scroll.getItem() instanceof ItemScroll item) {
            item.writeDatum(scroll, new PatternIota(t));
        }
        return scroll;
    }

    /**
     * Shift + 右键：把当前象限正在绘制的图案作为 PatternIota 入栈。
     * 栈已满（容量 1）或无图案时返回 false。
     * 入栈成功时保留 pattern（用户仍可看到），但栈里已经有了一份。
     */
    /** 入栈（考察）。加 player 参数是为了后续自动触发解析。 */
    public boolean srvConsiderQuadrant(Player player, int quadrant) {
        if (blockEntity == null) return false;
        if (quadrant < 0 || quadrant >= SpellInscriptionTableBlockEntity.QUADRANT_COUNT) return false;
        if (blockEntity.getQuadrantStackIota(quadrant) != null) return false;

        HexPattern pattern = blockEntity.getQuadrantPattern(quadrant);
        if (pattern == null) return false;

        Iota iota = new PatternIota(pattern);
        if (!blockEntity.considerAndPush(quadrant, iota)) return false;

        // 入栈后清空正在绘制的图案
        blockEntity.setQuadrantPattern(quadrant, null);

        // ⭐ 若三个及以上象限都有栈，自动尝试解析
        int filled = 0;
        for (int i = 0; i < SpellInscriptionTableBlockEntity.QUADRANT_COUNT; i++) {
            if (blockEntity.getQuadrantStackIota(i) != null) filled++;
        }
        if (filled >= 3) {
            srvTryParse(player);
        }
        return true;
    }

    /**
     * Shift + 左键：消耗玩家背包里一张空白卷轴，清空对应象限的栈。
     */
    public boolean srvClearQuadrantStack(Player player, int quadrant) {
        if (blockEntity == null) return false;
        if (quadrant < 0 || quadrant >= SpellInscriptionTableBlockEntity.QUADRANT_COUNT) return false;

        // 栈本来就是空的，不用清
        if (blockEntity.getQuadrantStackIota(quadrant) == null
                && blockEntity.getQuadrantPattern(quadrant) == null) {
            return false;
        }

        // 找并消耗一个空白卷轴
        boolean found = false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty() && isBlankScroll(s)) {
                s.shrink(1);
                found = true;
                break;
            }
        }
        if (!found) return false;

        blockEntity.clearQuadrantStack(quadrant);
        return true;
    }

    /** 判断"空白卷轴"：是 ItemScroll，且没有存储任何图案。 */
    private boolean isBlankScroll(ItemStack stack) {
        if (!(stack.getItem() instanceof ItemScroll)) return false;
        CompoundTag tag = stack.getTag();
        if (tag == null) return true;
        return !tag.contains(ItemScroll.TAG_PATTERN)
                && !tag.contains(ItemScroll.TAG_OP_ID);
    }

    // ==================== quickMoveStack ====================

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack copy = slot.getItem().copy();

        if (index == 0 || index == 1) {
            if (!moveItemStackTo(slot.getItem(), 2, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (!moveItemStackTo(slot.getItem(), 1, 2, false)
                    && !moveItemStackTo(slot.getItem(), 0, 1, false)) return ItemStack.EMPTY;
        }

        if (slot.getItem().isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, (Block) ModBlocks.SPELL_INSCRIPTION_TABLE.get());
    }
}