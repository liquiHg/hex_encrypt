package io.github.liquiHg.hexencrypt.block;

import at.petrak.hexcasting.api.casting.ParticleSpray;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.casting.iota.PatternIota;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.api.item.IotaHolderItem;
import at.petrak.hexcasting.api.item.MediaHolderItem;
import at.petrak.hexcasting.api.misc.MediaConstants;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
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
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public class SpellInscriptionTableBlockEntity extends BlockEntity implements MenuProvider {

    public static final int  QUADRANT_COUNT = 4;

    // === 入栈动画参数（与 Renderer 保持一致）===
    public static final int   TRANSFER_DURATION_TICKS = 20;   // 1 秒 = 20 tick
    public static final float CORNER_INSET            = 0.20f;
    public static final float CORNER_Y                = 1.01f;
    public static final float ORBIT_CENTER_Y          = 1.50f;
    /** 起点到终点之间的额外弧线高度（格）。0 = 直线，越大越拱。 */
    public static final float TRANSFER_ARC_HEIGHT = 0.45f;

    /** 缓存灰色系 pigment（浅紫，视觉上更醒目）。 */
    private FrozenPigment transferPigment;

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

    public static final String TAG_ORIGIN_PREFIX = "origin_";
    private static final String TAG_ORIGIN_Q = "q";
    private static final String TAG_ORIGIN_R = "r";

    /** 客户端渲染专用：栈内若是 PatternIota，反序列化出的图案缓存。 */
    private final HexPattern[] clientStackPatterns = new HexPattern[QUADRANT_COUNT];


    /**
     * ⭐ 暂存未解析的 iota NBT。
     * load() 在 BlockEntity 刚创建、还没进世界时就会被调用（level == null），
     * 而 IotaType.deserialize 需要 ServerLevel。所以此时先把 NBT 存起来，
     * 等第一次 tick 拿到 ServerLevel 再解析。
     */
    private final CompoundTag[] pendingStackNbt = new CompoundTag[QUADRANT_COUNT];

    /** 图案起点在象限坐标系中的格坐标。默认 (0,0)。 */
    private final int[] patternOriginQ = new int[QUADRANT_COUNT];
    private final int[] patternOriginR = new int[QUADRANT_COUNT];

    private static final String TAG_TRANSFER_TICK_PREFIX = "transfer_tick_";

    /**
     * 每个象限最近一次"入栈"的服务端 gameTime。
     * 0 表示从未入栈；> 0 表示客户端应该播放一段从角落到环绕的粒子动画。
     */
    private final long[] transferStartTick = new long[QUADRANT_COUNT];

    /** 入栈轨迹的色相渐变缓存。 */
    private FrozenPigment[] transferPaletteCache;

    private static final String TAG_CANVAS_PREFIX = "canvas_";

    /** 每个象限是否有画布（卷轴）。 */
    private final boolean[] quadrantCanvases = new boolean[QUADRANT_COUNT];

    public boolean hasQuadrantCanvas(int i) {
        if (i < 0 || i >= QUADRANT_COUNT) return false;
        return quadrantCanvases[i];
    }

    public void setQuadrantCanvas(int i, boolean b) {
        if (i < 0 || i >= QUADRANT_COUNT) return;
        quadrantCanvases[i] = b;
        setChanged();
        syncToClient();
    }

    public boolean allCanvasesPlaced() {
        for (int i = 0; i < QUADRANT_COUNT; i++) {
            if (!quadrantCanvases[i]) return false;
        }
        return true;
    }

    public int countCanvases() {
        int c = 0;
        for (int i = 0; i < QUADRANT_COUNT; i++) if (quadrantCanvases[i]) c++;
        return c;
    }

    public SpellInscriptionTableBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public SpellInscriptionTableBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.SPELL_INSCRIPTION_TABLE.get(), pos, state);
    }

    private FrozenPigment transferPigment() {
        if (transferPigment == null) {
            ItemStack stack = new ItemStack(HexItems.DYE_PIGMENTS.get(DyeColor.MAGENTA));
            transferPigment = new FrozenPigment(stack, Util.NIL_UUID);
        }
        return transferPigment;
    }

    /** 冷 → 暖渐变：青 → 浅蓝 → 紫 → 品红 → 粉 → 金黄。 */
    private FrozenPigment[] transferPalette() {
        if (transferPaletteCache == null) {
            DyeColor[] colors = {
                    DyeColor.CYAN,
                    DyeColor.LIGHT_BLUE,
                    DyeColor.PURPLE,
                    DyeColor.MAGENTA,
                    DyeColor.PINK,
                    DyeColor.YELLOW,
            };
            transferPaletteCache = new FrozenPigment[colors.length];
            for (int i = 0; i < colors.length; i++) {
                ItemStack st = new ItemStack(HexItems.DYE_PIGMENTS.get(colors[i]));
                transferPaletteCache[i] = new FrozenPigment(st, Util.NIL_UUID);
            }
        }
        return transferPaletteCache;
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

    public int getPatternOriginQ(int i) {
        if (i < 0 || i >= QUADRANT_COUNT) return 0;
        return patternOriginQ[i];
    }

    public int getPatternOriginR(int i) {
        if (i < 0 || i >= QUADRANT_COUNT) return 0;
        return patternOriginR[i];
    }

    public long getTransferStartTick(int i) {
        if (i < 0 || i >= QUADRANT_COUNT) return 0L;
        return transferStartTick[i];
    }

    public void setTransferStartTick(int i, long tick) {
        if (i < 0 || i >= QUADRANT_COUNT) return;
        transferStartTick[i] = tick;
        setChanged();
        syncToClient();
    }

    public void setPatternOrigin(int i, int q, int r) {
        if (i < 0 || i >= QUADRANT_COUNT) return;
        patternOriginQ[i] = q;
        patternOriginR[i] = r;
        setChanged();
        syncToClient();
    }

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
     * 服务端每 tick 调用。
     * 在入栈动画期间，从象限角落沿直线向环绕中心发射一批粒子，
     * 速度为「(终点-起点) / TRANSFER_DURATION_TICKS」，让粒子 1 秒后精确抵达终点。
     */
    public void tickTransferParticles() {
        if (!(level instanceof ServerLevel sl)) return;
        long now = sl.getGameTime();

        float a = CORNER_INSET;
        float b = 1f - CORNER_INSET;
        float[][] corners = {
                { a, CORNER_Y, a },
                { b, CORNER_Y, a },
                { a, CORNER_Y, b },
                { b, CORNER_Y, b }
        };

        double cx = worldPosition.getX() + 0.5;
        double cy = worldPosition.getY() + ORBIT_CENTER_Y;
        double cz = worldPosition.getZ() + 0.5;

        FrozenPigment[] palette = transferPalette();
        RandomSource rand = sl.random;

        for (int q = 0; q < QUADRANT_COUNT; q++) {
            long startTick = transferStartTick[q];
            if (startTick <= 0L) continue;

            long elapsed = now - startTick;
            if (elapsed >= TRANSFER_DURATION_TICKS) {
                transferStartTick[q] = 0L;
                setChanged();
                continue;
            }

            float progress = (float) elapsed / (float) TRANSFER_DURATION_TICKS;

            double sx = worldPosition.getX() + corners[q][0];
            double sy = worldPosition.getY() + corners[q][1];
            double sz = worldPosition.getZ() + corners[q][2];

            // ===== 主方向（起点 → 终点）=====
            double dx = cx - sx, dy = cy - sy, dz = cz - sz;
            double len = Math.sqrt(dx*dx + dy*dy + dz*dz);
            double ux = dx / len, uy = dy / len, uz = dz / len;

            // ===== 水平垂直轴（在世界 XZ 平面内）=====
            double perpX = uz, perpZ = -ux;
            double perpLen = Math.sqrt(perpX*perpX + perpZ*perpZ);
            if (perpLen < 1.0e-5) { perpX = 1; perpZ = 0; perpLen = 1; }
            perpX /= perpLen; perpZ /= perpLen;

            // ===== 竖直垂直轴 = 主方向 × 水平垂直轴 =====
            double perp2X =  uy * perpZ;
            double perp2Y =  uz * perpX - ux * perpZ;
            double perp2Z = -uy * perpX;
            double p2Len = Math.sqrt(perp2X*perp2X + perp2Y*perp2Y + perp2Z*perp2Z);
            if (p2Len > 1.0e-5) { perp2X /= p2Len; perp2Y /= p2Len; perp2Z /= p2Len; }

            // ===== 主轨迹位置 =====
            double baseX = sx + dx * progress;
            double baseY = sy + dy * progress
                    + Math.sin(progress * Math.PI) * TRANSFER_ARC_HEIGHT;
            double baseZ = sz + dz * progress;

            // ===== 双轴螺旋摆动：相位差 90°，产生"游动"感 =====
            double wobbleAmp = Math.sin(progress * Math.PI) * 0.14;
            double phaseBase = (now + q * 31L) * 0.42;
            double w1 = Math.sin(phaseBase) * wobbleAmp;
            double w2 = Math.cos(phaseBase * 1.3) * wobbleAmp * 0.6;

            double px = baseX + perpX * w1 + perp2X * w2;
            double py = baseY + perp2Y * w2;
            double pz = baseZ + perpZ * w1 + perp2Z * w2;

            // ===== 颜色：沿进度从冷到暖渐变 =====
            int colorIdx = Math.min(palette.length - 1,
                    (int) (progress * palette.length));
            FrozenPigment pig = palette[colorIdx];

            // ===== 体积：0.03 → 0.19 =====
            double fuzz = 0.03 + progress * 0.16;

            // ===== 数量：3 → 15 =====
            int count = 3 + (int) (progress * 12f);

            // ===== 速度：主方向小偏移 + 每颗粒子抖动 → 尾迹自然弥散 =====
            double jitterX = (rand.nextDouble() - 0.5) * 0.008;
            double jitterY = (rand.nextDouble() - 0.5) * 0.008;
            double jitterZ = (rand.nextDouble() - 0.5) * 0.008;

            ParticleSpray trail = new ParticleSpray(
                    new Vec3(px, py, pz),
                    new Vec3(jitterX, 0.010 + jitterY, jitterZ),
                    fuzz,
                    0.028,
                    count);
            trail.sprayParticles(sl, pig);

            // ===== 起点：小爆 + 冷色 =====
            if (elapsed == 0L) {
                ParticleSpray burst = new ParticleSpray(
                        new Vec3(sx, sy, sz),
                        new Vec3(0, 0.03, 0),
                        0.10, 0.08, 12);
                burst.sprayParticles(sl, palette[0]);
            }

            // ===== 终点前一 tick：暖色大爆发 =====
            if (elapsed == TRANSFER_DURATION_TICKS - 1) {
                ParticleSpray burst = new ParticleSpray(
                        new Vec3(cx, cy, cz),
                        new Vec3(0, 0.06, 0),
                        0.38,
                        0.24,
                        45);
                burst.sprayParticles(sl, palette[palette.length - 1]);
            }
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

    @Nullable
    public HexPattern getClientStackPattern(int i) {
        if (i < 0 || i >= QUADRANT_COUNT) return null;
        return clientStackPatterns[i];
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
        clientStackPatterns[quadrant] = null;
        patternOriginQ[quadrant] = 0;
        patternOriginR[quadrant] = 0;
        transferStartTick[quadrant] = 0L;
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

            if (transferStartTick[i] != 0L) {
                tag.putLong(TAG_TRANSFER_TICK_PREFIX + i, transferStartTick[i]);
            }

            if (quadrantCanvases[i]) {
                tag.putBoolean(TAG_CANVAS_PREFIX + i, true);
            }

            // ⭐ 保存 origin
            if (patternOriginQ[i] != 0 || patternOriginR[i] != 0) {
                CompoundTag originTag = new CompoundTag();
                originTag.putInt("q", patternOriginQ[i]);
                originTag.putInt("r", patternOriginR[i]);
                tag.put("origin_" + i, originTag);
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

    @Nullable
    private static HexPattern extractPatternFromIotaNbt(@Nullable CompoundTag stackTag) {
        if (stackTag == null) return null;
        if (!"hexcasting:pattern".equals(stackTag.getString(TAG_IOTA_TYPE))) return null;
        try {
            return HexPattern.fromNBT(stackTag.getCompound(TAG_IOTA_DATA));
        } catch (Exception e) {
            return null;
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

            transferStartTick[i] = tag.getLong(TAG_TRANSFER_TICK_PREFIX + i);

            // ★ 从 NBT 读画布状态（替换原来的 tag.putBoolean(...)）
            quadrantCanvases[i] = tag.getBoolean(TAG_CANVAS_PREFIX + i);

            // ⭐ 读取 origin
            String ok = "origin_" + i;
            if (tag.contains(ok, Tag.TAG_COMPOUND)) {
                CompoundTag originTag = tag.getCompound(ok);
                patternOriginQ[i] = originTag.getInt("q");
                patternOriginR[i] = originTag.getInt("r");
            } else {
                patternOriginQ[i] = 0;
                patternOriginR[i] = 0;
            }

            String sk = TAG_STACK_PREFIX + i;
            if (tag.contains(sk)) {
                CompoundTag stackTag = tag.getCompound(sk);
                if (serverLevel != null) {
                    // 服务端已就绪 → 直接解析
                    quadrantStackIota[i] = iotaFromNbt(stackTag, serverLevel);
                    pendingStackNbt[i] = null;
                    clientStackPatterns[i] = null;                // 服务端不需要缓存
                } else {
                    // 客户端（或 level 尚未加载）→ 暂存 + 尝试提取图案
                    quadrantStackIota[i] = null;
                    pendingStackNbt[i] = stackTag;
                    clientStackPatterns[i] = extractPatternFromIotaNbt(stackTag);   // ⭐
                }
            } else {
                quadrantStackIota[i] = null;
                pendingStackNbt[i] = null;
                clientStackPatterns[i] = null;                    // ⭐
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

            // ⭐ 发送 origin
            CompoundTag originTag = new CompoundTag();
            originTag.putInt(TAG_ORIGIN_Q, patternOriginQ[i]);
            originTag.putInt(TAG_ORIGIN_R, patternOriginR[i]);
            out.put(TAG_ORIGIN_PREFIX + i, originTag);

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
        out.putBoolean("canvases_ready", allCanvasesPlaced());
        for (int i = 0; i < QUADRANT_COUNT; i++) {
            if (quadrantCanvases[i]) {
                out.putBoolean("canvas_" + i, true);
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