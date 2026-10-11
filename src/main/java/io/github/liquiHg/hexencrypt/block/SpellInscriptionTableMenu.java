package io.github.liquiHg.hexencrypt.block;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.math.HexAngle;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.api.item.IotaHolderItem;
import at.petrak.hexcasting.common.items.storage.ItemScroll;
import at.petrak.hexcasting.common.lib.HexItems;
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes;
import io.github.liquiHg.hexencrypt.api.ComparisonResult;
import io.github.liquiHg.hexencrypt.registry.ModBlocks;
import io.github.liquiHg.hexencrypt.registry.ModMenuTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

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
     * 尝试从当前方块输入中解析出对应的 ISS 法术。
     *
     * 输入：
     *   - 中央槽：一个含 HexPattern 的物品（如卷轴）
     *   - 至少 3 个象限的栈：分别是官能团段（school / target / cast）
     *
     * 匹配逻辑：
     *   按象限编号顺序（0→1→2→...）依次在中央槽图案中查找每个栈图案出现的位置；
     *   若所有栈图案都能按顺序找到且不重叠 → 匹配成功。
     *
     * 成功输出：
     *   - 聊天消息（后缀长度 + 各字符计数 + 后缀原文）
     *   - 一张"缺后缀"的大型卷轴
     */
    public boolean srvTryParse(Player player) {
        if (blockEntity == null) {
            player.sendSystemMessage(Component.literal("§c[调试] blockEntity == null"));
            return false;
        }

        // ★ 若你已按修改清单改用 runComparison，加在这里：
        var result = blockEntity.runComparison(null, player);
        player.sendSystemMessage(Component.literal("§c[调试] runComparison 返回=" + result));
        if (!(result instanceof ComparisonResult.Matched m)) {
            player.sendSystemMessage(Component.literal("§c[调试] 未匹配：" + result));
            return false;
        }

        // 1) 从中央槽提取 HexPattern
        HexPattern fullPattern = extractPatternFromCentralSlot();
        if (fullPattern == null) return false;

        // 2) 收集所有有栈的象限（按索引升序）
        List<Integer> stackQuads = new ArrayList<>();
        for (int q = 0; q < SpellInscriptionTableBlockEntity.QUADRANT_COUNT; q++) {
            if (blockEntity.getQuadrantStackIota(q) instanceof PatternIota) {
                stackQuads.add(q);
            }
        }
        if (stackQuads.size() < 3) return false;

        // 3) 依次在 fullSig 中查找每段
        String fullSig = fullPattern.anglesSignature();
        int cursor = 0;
        List<Integer> segmentEnds = new ArrayList<>();

        for (int q : stackQuads) {
            Iota iota = blockEntity.getQuadrantStackIota(q);
            if (!(iota instanceof PatternIota pi)) continue;
            String sub = pi.getPattern().anglesSignature();
            if (sub.isEmpty()) continue;

            int pos = fullSig.indexOf(sub, cursor);
            if (pos < 0) {
                player.sendSystemMessage(Component.literal(
                        "§c[法术解析] 匹配失败：象限 " + (q + 1)
                                + " 的图案 §f" + sub + "§c 未在中央图案的后续位置找到"));
                return false;
            }
            cursor = pos + sub.length();
            segmentEnds.add(cursor);
        }

        // 4) 后缀统计
        String suffix = fullSig.substring(cursor);
        int suffixLen = suffix.length();

        int cQ = 0, cW = 0, cE = 0, cA = 0, cD = 0;
        for (int i = 0; i < suffix.length(); i++) {
            switch (suffix.charAt(i)) {
                case 'q' -> cQ++;
                case 'w' -> cW++;
                case 'e' -> cE++;
                case 'a' -> cA++;
                case 'd' -> cD++;
            }
        }

        // 5) 反馈
        player.sendSystemMessage(Component.literal("§6===== 法术解析 ====="));
        player.sendSystemMessage(Component.literal("§a✓ 匹配成功"));
        player.sendSystemMessage(Component.literal("§e  识别官能团段数: §f" + segmentEnds.size()));
        player.sendSystemMessage(Component.literal("§e  后缀长度: §f" + suffixLen));
        player.sendSystemMessage(Component.literal(
                "§e  后缀字符分布: §fq=" + cQ + " w=" + cW + " e=" + cE
                        + " a=" + cA + " d=" + cD));
        //player.sendSystemMessage(Component.literal("§e  后缀原文: §f" + suffix));

        // 6) 给出"缺后缀"的卷轴
        ItemStack scroll = createTruncatedScroll(fullPattern, cursor);
        if (!player.getInventory().add(scroll)) {
            player.drop(scroll, false);
        }
        player.sendSystemMessage(Component.literal("§b→ 已发放缺后缀卷轴"));

        return true;
    }

    /** 从中央槽物品提取 HexPattern；不是卷轴或没有图案时返回 null。 */
    @Nullable
    private HexPattern extractPatternFromCentralSlot() {
        ItemStack stack = container.getItem(0);
        if (stack.isEmpty()) return null;

        if (!(stack.getItem() instanceof IotaHolderItem holder)) return null;

        CompoundTag iotaTag = holder.readIotaTag(stack);
        if (iotaTag == null) return null;

        // iotaTag 格式：{ type: "hexcasting:pattern", data: { start_dir, angles } }
        String type = iotaTag.getString(HexIotaTypes.KEY_TYPE);
        if (!"hexcasting:pattern".equals(type)) return null;

        CompoundTag data = iotaTag.getCompound(HexIotaTypes.KEY_DATA);
        try {
            return HexPattern.fromNBT(data);
        } catch (Exception e) {
            return null;
        }
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