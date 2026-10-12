package io.github.liquiHg.hexencrypt.block;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.api.item.IotaHolderItem;
import at.petrak.hexcasting.api.item.MediaHolderItem;
import at.petrak.hexcasting.api.misc.MediaConstants;
import at.petrak.hexcasting.common.lib.HexItems;
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.github.liquiHg.hexencrypt.api.ComparisonRequest;
import io.github.liquiHg.hexencrypt.api.ComparisonResult;
import io.github.liquiHg.hexencrypt.api.InscriptionTableAccess;
import io.github.liquiHg.hexencrypt.api.SpellComparators;
import io.github.liquiHg.hexencrypt.registry.ModBlockEntities;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class SpellInscriptionTableBlockEntity extends BlockEntity implements MenuProvider {

    public static final int  QUADRANT_COUNT = 4;
    /** 上限：能装下 1 个 charged_amethyst（100 000）。 */
    public static final long MAX_MEDIA      = MediaConstants.CRYSTAL_UNIT;

    private static final String TAG_MEDIA        = "media";
    private static final String TAG_MEDIA_ITEM   = "media_item";
    private static final String TAG_STORED_ITEM  = "stored_item";
    private static final String TAG_Q_PREFIX     = "quadrant_";
    private static final String TAG_STACK_PREFIX = "stack_";

    private static final String TAG_IOTA_TYPE = "iota_type";
    private static final String TAG_IOTA_DATA = "iota_data";

    private long media = 0;
    private ItemStack mediaItem  = ItemStack.EMPTY;
    private ItemStack storedItem = ItemStack.EMPTY;

    /** 正在绘制的图案（每个象限一个）。 */
    private final HexPattern[] quadrantPatterns = new HexPattern[QUADRANT_COUNT];
    /** 入栈后的 Iota（每个象限一个，容量 1）。 */
    private final Iota[] quadrantStackIota = new Iota[QUADRANT_COUNT];

    /**
     * ⭐ 暂存未解析的 iota NBT。
     * load() 在 BlockEntity 刚创建、还没进世界时就会被调用（level == null），
     * 而 IotaType.deserialize 需要 ServerLevel。所以此时先把 NBT 存起来，
     * 等第一次 tick 拿到 ServerLevel 再解析。
     */
    private final CompoundTag[] pendingStackNbt = new CompoundTag[QUADRANT_COUNT];

    public SpellInscriptionTableBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public SpellInscriptionTableBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.SPELL_INSCRIPTION_TABLE.get(), pos, state);
    }

    // ==================== 客户端同步 ====================

    private void syncToClient() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }
    @Override public void handleUpdateTag(CompoundTag tag) { super.handleUpdateTag(tag); load(tag); }
    @Nullable @Override public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ==================== MenuProvider ====================

    @Override public Component getDisplayName() {
        return Component.translatable("block.hexencrypt.spell_inscription_table");
    }
    @Nullable @Override public AbstractContainerMenu createMenu(int windowId, Inventory inv, Player player) {
        return new SpellInscriptionTableMenu(windowId, inv, this);
    }

    // ==================== 媒质 ====================

    public long getMedia()    { return media; }
    public long getMaxMedia() { return MAX_MEDIA; }
    public float getMediaRatio() { return MAX_MEDIA > 0 ? (float) media / MAX_MEDIA : 0f; }

    public boolean tryConsumeMedia(long amount) {
        if (amount <= 0) return true;
        if (media < amount) return false;
        media -= amount;
        setChanged();
        syncToClient();
        return true;
    }

    /** 每 tick 尝试从媒质槽充能。支持整只媒质物品 + MediaHolderItem（媒质电池）。 */
    public void tickRecharge() {
        if (mediaItem.isEmpty()) return;

        long space = MAX_MEDIA - media;
        if (space <= 0) return;

        long perItem = unitValuePerItem(mediaItem);
        if (perItem > 0) {
            if (space >= perItem) {
                mediaItem.shrink(1);
                if (mediaItem.isEmpty()) mediaItem = ItemStack.EMPTY;
                media += perItem;
                setChanged();
                syncToClient();
            }
            return;
        }

        if (mediaItem.getItem() instanceof MediaHolderItem holder) {
            long available = holder.getMedia(mediaItem);
            if (available <= 0) return;
            long toTake = Math.min(space, available);
            holder.withdrawMedia(mediaItem, toTake, false);
            media += toTake;
            setChanged();
            syncToClient();
        }
    }

    /**
     * ⭐ 每 tick 在服务端尝试解析暂存的 iota NBT。
     * 解决 load() 时 level == null 的问题。
     */
    public void tickResolvePendingIota() {
        if (!(level instanceof ServerLevel sl)) return;

        boolean changed = false;
        for (int i = 0; i < QUADRANT_COUNT; i++) {
            CompoundTag pending = pendingStackNbt[i];
            if (pending == null) continue;
            quadrantStackIota[i] = iotaFromNbt(pending, sl);
            pendingStackNbt[i] = null;
            changed = true;
        }
        if (changed) {
            setChanged();
            // 不需要 syncToClient()：客户端本来就不解析 iota
        }
    }

    private long unitValuePerItem(ItemStack stack) {
        if (stack.is(HexItems.AMETHYST_DUST))    return MediaConstants.DUST_UNIT;
        if (stack.is(HexItems.CHARGED_AMETHYST)) return MediaConstants.CRYSTAL_UNIT;
        if (stack.is(HexItems.QUENCHED_SHARD))   return MediaConstants.QUENCHED_SHARD_UNIT;
        if (stack.is(Items.AMETHYST_SHARD))      return MediaConstants.SHARD_UNIT;
        return 0;
    }

    public ItemStack getMediaItem() { return mediaItem; }

    public void setMediaItem(ItemStack s) {
        this.mediaItem = (s == null) ? ItemStack.EMPTY : s;
        setChanged();
        syncToClient();
    }

    // ==================== 象限图案 ====================

    public HexPattern getQuadrantPattern(int i) {
        if (i < 0 || i >= QUADRANT_COUNT) return null;
        return quadrantPatterns[i];
    }

    public void setQuadrantPattern(int i, HexPattern p) {
        if (i < 0 || i >= QUADRANT_COUNT) return;
        quadrantPatterns[i] = p;
        setChanged();
        syncToClient();
    }

    // ==================== 象限栈（容量 1） ====================

    public Iota getQuadrantStackIota(int i) {
        if (i < 0 || i >= QUADRANT_COUNT) return null;
        return quadrantStackIota[i];
    }

    public void setQuadrantStackIota(int i, Iota iota) {
        if (i < 0 || i >= QUADRANT_COUNT) return;
        quadrantStackIota[i] = iota;
        setChanged();
        syncToClient();
    }

    /** 把 iota 入栈；栈已满（容量 1）返回 false。 */
    public boolean considerAndPush(int quadrant, Iota iota) {
        if (quadrant < 0 || quadrant >= QUADRANT_COUNT) return false;
        if (quadrantStackIota[quadrant] != null) return false;
        quadrantStackIota[quadrant] = iota;
        setChanged();
        syncToClient();
        return true;
    }

    /** 清空栈，同时清空正在绘制的图案。 */
    public void clearQuadrantStack(int quadrant) {
        if (quadrant < 0 || quadrant >= QUADRANT_COUNT) return;
        quadrantStackIota[quadrant] = null;
        quadrantPatterns[quadrant] = null;
        pendingStackNbt[quadrant] = null;
        setChanged();
        syncToClient();
    }

    // ==================== 中央槽 ====================

    public ItemStack getStoredItem() { return storedItem; }

    public void setStoredItem(ItemStack s) {
        this.storedItem = (s == null) ? ItemStack.EMPTY : s;
        setChanged();
        syncToClient();
    }

    // ==================== NBT ====================

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putLong(TAG_MEDIA, media);
        tag.put(TAG_MEDIA_ITEM,  mediaItem.save(new CompoundTag()));
        tag.put(TAG_STORED_ITEM, storedItem.save(new CompoundTag()));

        for (int i = 0; i < QUADRANT_COUNT; i++) {
            if (quadrantPatterns[i] != null) {
                tag.put(TAG_Q_PREFIX + i, quadrantPatterns[i].serializeToNBT());
            }
            // ⭐ 如果还有 pending 没解析，优先用 pending 保存（避免 iota 丢失）
            CompoundTag stackTagToWrite = null;
            if (quadrantStackIota[i] != null) {
                stackTagToWrite = iotaToNbt(quadrantStackIota[i]);
            } else if (pendingStackNbt[i] != null) {
                stackTagToWrite = pendingStackNbt[i];
            }
            if (stackTagToWrite != null) {
                tag.put(TAG_STACK_PREFIX + i, stackTagToWrite);
            }
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        media = tag.getLong(TAG_MEDIA);
        mediaItem  = tag.contains(TAG_MEDIA_ITEM)
                ? ItemStack.of(tag.getCompound(TAG_MEDIA_ITEM)) : ItemStack.EMPTY;
        storedItem = tag.contains(TAG_STORED_ITEM)
                ? ItemStack.of(tag.getCompound(TAG_STORED_ITEM)) : ItemStack.EMPTY;

        // ⭐ 判断当前是否在服务端；level 可能为 null（BE 刚创建时）
        ServerLevel serverLevel = (level instanceof ServerLevel sl) ? sl : null;

        for (int i = 0; i < QUADRANT_COUNT; i++) {
            String pk = TAG_Q_PREFIX + i;
            quadrantPatterns[i] = tag.contains(pk) ? HexPattern.fromNBT(tag.getCompound(pk)) : null;

            String sk = TAG_STACK_PREFIX + i;
            if (tag.contains(sk)) {
                CompoundTag stackTag = tag.getCompound(sk);
                if (serverLevel != null) {
                    // 服务端已就绪 → 直接解析
                    quadrantStackIota[i] = iotaFromNbt(stackTag, serverLevel);
                    pendingStackNbt[i] = null;
                } else {
                    // ⭐ level 尚未加载（BE 刚创建或客户端）→ 暂存，等 tick 里再解析
                    quadrantStackIota[i] = null;
                    pendingStackNbt[i] = stackTag;
                }
            } else {
                quadrantStackIota[i] = null;
                pendingStackNbt[i] = null;
            }
        }
    }

    /**
     * 把任意 Iota 序列化为 NBT。
     * 返回 null 表示该 Iota 类型没有注册。
     */
    private static CompoundTag iotaToNbt(Iota iota) {
        if (iota == null) return null;

        ResourceLocation key = HexIotaTypes.REGISTRY.getKey(iota.getType());
        if (key == null) return null;

        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_IOTA_TYPE, key.toString());
        tag.put(TAG_IOTA_DATA, iota.serialize());
        return tag;
    }

    /**
     * 从 NBT 反序列化为 Iota。
     * ⚠️ 必须传入 ServerLevel。
     */
    private static Iota iotaFromNbt(CompoundTag tag, ServerLevel world) {
        if (tag == null || world == null) return null;

        ResourceLocation rl = ResourceLocation.tryParse(tag.getString(TAG_IOTA_TYPE));
        if (rl == null) return null;

        IotaType<?> type = HexIotaTypes.REGISTRY.get(rl);
        if (type == null) return null;

        try {
            return IotaType.deserialize(tag.getCompound(TAG_IOTA_DATA), world);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 ISS 卷轴中读出 spell id（不构造图案）。
     * 供 getCentralPattern() 和 getCentralSpellId() 共用。
     */
    @Nullable
    private static ResourceLocation extractIssSpellId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return null;
        if (!tag.contains("irons_spellbooks:spell_container", Tag.TAG_COMPOUND)) return null;

        CompoundTag container = tag.getCompound("irons_spellbooks:spell_container");
        ListTag data = container.getList("data", Tag.TAG_COMPOUND);
        if (data.isEmpty()) return null;

        SpellData spellData;
        try {
            spellData = SpellData.CODEC
                    .parse(NbtOps.INSTANCE, data.getCompound(0))
                    .result()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
        if (spellData == null || spellData.getSpell() == SpellRegistry.none()) return null;
        return spellData.getSpell().getSpellResource();
    }

    /**
     * 从 Iron's Spellbooks 的卷轴中提取对应的 Hex 图案。
     *
     * ISS 卷轴的 NBT 结构：
     *   tag["irons_spellbooks:spell_container"]
     *     └─ "data": ListTag
     *          └─ [0]: CompoundTag，包含 SpellData 的序列化数据
     *
     * 我们使用 SpellData.CODEC 进行反序列化，获取 spell id，
     * 再从 Hex 动作注册表里反查它的 prototype（= 注册时用的 HexPattern）。
     */
    @Nullable
    private static HexPattern extractPatternFromIssScroll(ItemStack stack) {
        ResourceLocation spellId = extractIssSpellId(stack);
        if (spellId == null) return null;

        ResourceLocation hexId = new ResourceLocation(HexEncrypt.MODID, "iss_" + spellId.getPath());
        var entry = IXplatAbstractions.INSTANCE.getActionRegistry().get(hexId);
        if (entry == null) {
            HexEncrypt.LOGGER.warn("[解析台] ISS spellId={} → 找不到 hexId={}", spellId, hexId);
            return null;
        }
        return entry.prototype();
    }

    /**
     * 构建发送给客户端的 NBT。
     *   - 图案：完整发（HexPattern.fromNBT 不需要 level）
     *   - 栈：发 { full: true, display: "..." } 复合标记
     */
    public CompoundTag buildQuadrantNbt() {
        CompoundTag out = new CompoundTag();
        for (int i = 0; i < QUADRANT_COUNT; i++) {
            if (quadrantPatterns[i] != null) {
                out.put("q" + i, quadrantPatterns[i].serializeToNBT());
            }
            if (quadrantStackIota[i] != null) {
                CompoundTag slot = new CompoundTag();
                slot.putBoolean("full", true);

                try {
                    Component display = quadrantStackIota[i].display();
                    String json = Component.Serializer.toJson(display);
                    if (json != null) slot.putString("display", json);
                } catch (Exception ignored) {
                }
                out.put("s" + i, slot);
            }
        }
        return out;
    }

    // ==================== 只读访问视图 ====================

    private InscriptionTableAccess cachedAccess;

    public InscriptionTableAccess asAccess() {
        if (cachedAccess == null) {
            cachedAccess = new InscriptionTableAccess() {
                @Override public Level getLevel() { return SpellInscriptionTableBlockEntity.this.level; }
                @Override public BlockPos getPos() { return SpellInscriptionTableBlockEntity.this.worldPosition; }
                @Override public ItemStack getCentralItem() { return storedItem; }

                @Override public HexPattern getCentralPattern() {
                    ItemStack s = storedItem;
                    if (s.isEmpty()) return null;

                    // 途径 1：Hex 卷轴（IotaHolderItem），直接读 pattern
                    if (s.getItem() instanceof IotaHolderItem holder) {
                        CompoundTag tag = holder.readIotaTag(s);
                        if (tag != null && "hexcasting:pattern".equals(tag.getString(HexIotaTypes.KEY_TYPE))) {
                            try {
                                return HexPattern.fromNBT(tag.getCompound(HexIotaTypes.KEY_DATA));
                            } catch (Exception ignored) { /* fallthrough */ }
                        }
                    }

                    // 途径 2：ISS 卷轴，从 spell_container 读 spell id → 反查 hex 图案
                    HexPattern fromIss = extractPatternFromIssScroll(s);
                    if (fromIss != null) return fromIss;

                    return null;
                }

                @Override
                public ResourceLocation getCentralSpellId() {
                    ItemStack s = storedItem;
                    if (s.isEmpty()) return null;
                    return extractIssSpellId(s);   // 非 ISS 卷轴会返回 null，正常
                }

                @Override public HexPattern[] getStackPatterns() {
                    HexPattern[] out = new HexPattern[QUADRANT_COUNT];
                    for (int i = 0; i < QUADRANT_COUNT; i++) {
                        if (quadrantStackIota[i] instanceof PatternIota pi) out[i] = pi.getPattern();
                    }
                    return out;
                }

                @Override public long getMedia() { return media; }
                @Override public long getMaxMedia() { return MAX_MEDIA; }
            };
        }
        return cachedAccess;
    }

    // ==================== 比对入口 ====================

    public ComparisonResult runComparison(@Nullable ResourceLocation preferredKind) {
        ComparisonRequest req = new ComparisonRequest(asAccess(), preferredKind, null);
        return SpellComparators.run(req);
    }

    public ComparisonResult runComparison(@Nullable ResourceLocation preferredKind,
                                          @Nullable Player caller) {
        ComparisonRequest req = new ComparisonRequest(asAccess(), preferredKind, caller);
        return SpellComparators.run(req);
    }
}