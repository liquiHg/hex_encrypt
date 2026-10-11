package io.github.liquiHg.hexencrypt.client.screen;

import at.petrak.hexcasting.api.casting.math.HexAngle;
import at.petrak.hexcasting.api.casting.math.HexCoord;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.client.render.RenderLib;
import at.petrak.hexcasting.common.lib.HexItems;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.github.liquiHg.hexencrypt.block.SpellInscriptionTableMenu;
import io.github.liquiHg.hexencrypt.network.ModNetwork;
import io.github.liquiHg.hexencrypt.network.PacketTableAction;
import io.github.liquiHg.hexencrypt.util.SpellUnlockManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SpellInscriptionTableScreen
        extends AbstractContainerScreen<SpellInscriptionTableMenu> {

    // ==================== 常量 ====================

    private static final int GUI_WIDTH  = 256;
    private static final int GUI_HEIGHT = 256;
    private static final int INVENTORY_TOP   = 174;
    private static final int PLAYER_INV_LEFT = 47;

    private static final ResourceLocation BG = new ResourceLocation(
            HexEncrypt.MODID, "textures/gui/spell_analysis_table.png");

    /** Hex 卷轴底图（与原版 tooltip 一致）。 */
    private static final ResourceLocation SCROLL_BG = new ResourceLocation(
            "hexcasting", "textures/gui/scroll.png");
    private static final int SCROLL_TEX_SIZE = 48;   // 纹理内部有效区域

    /** 每个象限的 hex 网格半径（像素）。 */
    private static final float HEX_SIZE = 10f;

    // 颜色
    private static final int CROSS_COLOR       = 0xB0_BFE0FF;
    private static final int CROSS_CORE_COLOR  = 0xF0_FFFFFF;
    private static final int MEDIA_BAR_BG      = 0xFF_1A1A1A;
    private static final int MEDIA_BAR_FILL    = 0xFF_8844CC;
    private static final int MEDIA_BAR_BORDER  = 0xFF_555555;

    // ==================== 象限几何 ====================

    private final int[] qL = new int[4], qT = new int[4], qR = new int[4], qB = new int[4];

    // ==================== 本地状态（与服务端同步） ====================

    private final HexPattern[] localPatterns = new HexPattern[4];

    /**
     * 画图时的本地乐观扣减覆盖值。
     * -1 表示"使用 menu.getMedia()"（权威值）；
     * >= 0 表示"这一帧用这个值"，服务端回包时被清零。
     */
    private long localMediaOverride = -1;

    // ==================== 绘制状态机 ====================

    private int drawingQuadrant = -1;
    private HexCoord lastCoord = null;
    private final Set<HexCoord> visitedInStroke = new HashSet<>();

    private boolean coordsLogged = false;

    /** 每个象限的栈是否已满（容量 1）。 */
    private final boolean[] localStackFull = new boolean[4];

    /** ⭐ 每个象限栈内 iota 的显示文本（来自服务端同步）。 */
    private final Component[] localStackDisplay = new Component[4];

// ==================== 栈图标常量 ====================

    /** 栈图标：thought_knot 物品。 */
    private static final ItemStack STACK_ICON = new ItemStack(HexItems.THOUGHT_KNOT);
    /** 图标相对象限右上角的偏移（像素）。 */
    private static final int STACK_ICON_OFFSET_X = 4;
    private static final int STACK_ICON_OFFSET_Y = 4;
    private static final int STACK_ICON_SIZE = 16;

    public SpellInscriptionTableScreen(SpellInscriptionTableMenu menu,
                                       Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth  = GUI_WIDTH;
        this.imageHeight = GUI_HEIGHT;
        this.inventoryLabelX = PLAYER_INV_LEFT;
        this.inventoryLabelY = INVENTORY_TOP - 12;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        computeQuadrants();
        // ⭐ 打开 UI 时主动请求一次同步
        ModNetwork.sendTableAction(PacketTableAction.requestSync());
    }

    private void computeQuadrants() {
        int L = leftPos, T = topPos;
        int R = leftPos + imageWidth, B = topPos + INVENTORY_TOP;
        int cx = (L + R) / 2, cy = (T + B) / 2;

        // 0=左上 1=右上 2=左下 3=右下
        qL[0] = L; qT[0] = T; qR[0] = cx; qB[0] = cy;
        qL[1] = cx; qT[1] = T; qR[1] = R; qB[1] = cy;
        qL[2] = L; qT[2] = cy; qR[2] = cx; qB[2] = B;
        qL[3] = cx; qT[3] = cy; qR[3] = R; qB[3] = B;
    }

    // ==================== 服务端同步回调 ====================

    /**
     * 收到 PacketTableSync 时调用（玩家画/退/清之后）。
     * 注意：media 字段已经由 ContainerData 每 tick 同步，这里只用来清本地覆盖。
     */
    public void applyServerSync(long media, CompoundTag nbt) {
        this.localMediaOverride = -1;

        for (int i = 0; i < 4; i++) {
            String pk = "q" + i;
            localPatterns[i] = nbt.contains(pk) ? HexPattern.fromNBT(nbt.getCompound(pk)) : null;

            String sk = "s" + i;
            if (nbt.contains(sk, Tag.TAG_COMPOUND)) {
                CompoundTag slot = nbt.getCompound(sk);
                localStackFull[i] = slot.getBoolean("full");

                String json = slot.getString("display");
                if (!json.isEmpty()) {
                    try {
                        localStackDisplay[i] = Component.Serializer.fromJson(json);
                    } catch (Exception e) {
                        localStackDisplay[i] = null;
                    }
                } else {
                    localStackDisplay[i] = null;
                }
            } else {
                localStackFull[i] = false;
                localStackDisplay[i] = null;
            }
        }
    }

    /** 计算某个象限的栈图标左上角屏幕坐标。 */
    private int stackIconX(int q) {
        return qR[q] - STACK_ICON_SIZE - STACK_ICON_OFFSET_X;
    }
    private int stackIconY(int q) {
        return qT[q] + STACK_ICON_OFFSET_Y;
    }

    /** 若鼠标悬停在某个象限的栈图标上，返回象限编号；否则 -1。 */
    private int findHoveredStackQuadrant(int mouseX, int mouseY) {
        for (int q = 0; q < 4; q++) {
            if (!localStackFull[q]) continue;
            int ix = stackIconX(q), iy = stackIconY(q);
            if (mouseX >= ix && mouseX < ix + STACK_ICON_SIZE
                    && mouseY >= iy && mouseY < iy + STACK_ICON_SIZE) {
                return q;
            }
        }
        return -1;
    }

    /** 绘制四个象限的栈图标。 */
    private void renderStackIcons(GuiGraphics gg) {
        for (int q = 0; q < 4; q++) {
            if (!localStackFull[q]) continue;
            gg.renderItem(STACK_ICON, stackIconX(q), stackIconY(q));
        }
    }

    private HexPattern getLocalPattern(int q) {
        return (q >= 0 && q < 4) ? localPatterns[q] : null;
    }

    /** 当前应显示的媒质值：优先本地乐观覆盖，其次 ContainerData 同步值。 */
    private long currentMedia() {
        return localMediaOverride >= 0 ? localMediaOverride : menu.getMedia();
    }

    // ==================== 渲染 ====================

    @Override
    protected void renderBg(GuiGraphics gg, float partialTick, int mouseX, int mouseY) {
        computeQuadrants();

        // 1) 底图
        gg.blit(BG, leftPos, topPos, 0, 0, imageWidth, imageHeight);

        // 2) 四象限卷轴背景
        for (int q = 0; q < 4; q++) renderQuadrantTiles(gg, q);

        // 3) 象限内已保存的图案
        for (int q = 0; q < 4; q++) renderQuadrantPattern(gg, q);

        // 4) 十字
        drawCross(gg);

        // 5) 栈状态图标（thought_knot）
        renderStackIcons(gg);

        // 6) 媒质条
        renderMediaBar(gg);

        if (!coordsLogged) {
            coordsLogged = true;
            logCoords();
        }
    }

    /**
     * 九宫格拉伸。
     */
    private static void blitNineSliced(GuiGraphics gg, ResourceLocation tex,
                                       int x, int y, int w, int h,
                                       int texSize, int border) {
        int innerW = w - 2 * border;
        int innerH = h - 2 * border;
        int innerTex = texSize - 2 * border;

        if (innerW <= 0 || innerH <= 0) {
            gg.blit(tex, x, y, w, h, 0f, 0f, texSize, texSize, texSize, texSize);
            return;
        }

        // 四角：不拉伸
        gg.blit(tex, x, y, border, border,
                0f, 0f, border, border, texSize, texSize);
        gg.blit(tex, x + w - border, y, border, border,
                (float)(texSize - border), 0f, border, border, texSize, texSize);
        gg.blit(tex, x, y + h - border, border, border,
                0f, (float)(texSize - border), border, border, texSize, texSize);
        gg.blit(tex, x + w - border, y + h - border, border, border,
                (float)(texSize - border), (float)(texSize - border), border, border, texSize, texSize);

        // 上下边：只水平拉伸
        gg.blit(tex, x + border, y, innerW, border,
                (float)border, 0f, innerTex, border, texSize, texSize);
        gg.blit(tex, x + border, y + h - border, innerW, border,
                (float)border, (float)(texSize - border), innerTex, border, texSize, texSize);

        // 左右边：只垂直拉伸
        gg.blit(tex, x, y + border, border, innerH,
                0f, (float)border, border, innerTex, texSize, texSize);
        gg.blit(tex, x + w - border, y + border, border, innerH,
                (float)(texSize - border), (float)border, border, innerTex, texSize, texSize);

        // 中间：双向拉伸
        gg.blit(tex, x + border, y + border, innerW, innerH,
                (float)border, (float)border, innerTex, innerTex, texSize, texSize);
    }

    private void renderQuadrantTiles(GuiGraphics gg, int q) {
        int x0 = qL[q], y0 = qT[q];
        int w = qR[q] - x0;
        int h = qB[q] - y0;
        if (w <= 0 || h <= 0) return;

        blitNineSliced(gg, SCROLL_BG, x0, y0, w, h, SCROLL_TEX_SIZE, 4);
    }

    /** 绘制一个象限中保存的图案。 */
    private void renderQuadrantPattern(GuiGraphics gg, int q) {
        HexPattern pattern = getLocalPattern(q);
        if (pattern == null) return;

        int cx = (qL[q] + qR[q]) / 2;
        int cy = (qT[q] + qB[q]) / 2;
        int qw = qR[q] - qL[q];
        int qh = qB[q] - qT[q];

        // 1) 计算节点像素（以象限中心为原点）
        List<HexCoord> coords = pattern.positions();
        List<Vec2> raw = new ArrayList<>(coords.size());
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (HexCoord c : coords) {
            Vec2 p = hexToPixel(c, HEX_SIZE);
            raw.add(p);
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
        }

        // 2) 居中 + 缩放（留 12px 边距）
        float centerX = (minX + maxX) * 0.5f;
        float centerY = (minY + maxY) * 0.5f;
        float spanX = Math.max(1f, maxX - minX);
        float spanY = Math.max(1f, maxY - minY);
        float padW = qw - 12f, padH = qh - 12f;
        float scale = Math.min(1f, Math.min(padW / spanX, padH / spanY));

        List<Vec2> pts = new ArrayList<>(raw.size());
        for (Vec2 p : raw) {
            pts.add(new Vec2((p.x - centerX) * scale, (p.y - centerY) * scale));
        }

        // 3) 用 RenderLib 画（含 zappy 效果）
        var pose = gg.pose();
        pose.pushPose();
        pose.translate(cx, cy, 0);

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        Matrix4f mat = pose.last().pose();
        RenderLib.drawPatternFromPoints(
                mat,
                pts,
                null,               // dupIndices
                true,               // drawLast
                argb(0xFF, 0xC8, 0xA0, 0x40),   // ⭐ tail：暗金黄
                argb(0xFF, 0xFF, 0xE8, 0x80),   // ⭐ head：亮金黄
                0.10f,              // flowIrregular
                0.20f,              // readabilityOffset
                0.80f,              // lastSegmentLenProportion
                0.0                 // seed
        );

        pose.popPose();

    }

    private void drawCross(GuiGraphics gg) {
        int L = qL[0], T = qT[0], R = qR[1], B = qB[2];
        int cx = (L + R) / 2, cy = (T + B) / 2;
        gg.fill(L, cy, R, cy + 1, CROSS_COLOR);
        gg.fill(cx, T, cx + 1, B, CROSS_COLOR);

        boolean active = hasISSSpell(menu.getStoredItem());
        if (active) {
            int arm = 10;
            gg.fill(cx - arm, cy, cx + arm, cy + 1, CROSS_CORE_COLOR);
            gg.fill(cx, cy - arm, cx + 1, cy + arm, CROSS_CORE_COLOR);
        }
    }

    private void renderMediaBar(GuiGraphics gg) {
        int bx = leftPos + SpellInscriptionTableMenu.MEDIA_BAR_X;
        int by = topPos  + SpellInscriptionTableMenu.MEDIA_BAR_Y;
        int bw = SpellInscriptionTableMenu.MEDIA_BAR_W;
        int bh = SpellInscriptionTableMenu.MEDIA_BAR_H;

        // 边框 + 背景
        gg.fill(bx - 1, by - 1, bx + bw + 1, by + bh + 1, MEDIA_BAR_BORDER);
        gg.fill(bx, by, bx + bw, by + bh, MEDIA_BAR_BG);

        // ⭐ 用 currentMedia()，会自动用 ContainerData 同步值
        long maxMedia = menu.getMaxMedia();
        long shown    = currentMedia();
        float ratio = maxMedia > 0
                ? Math.min(1f, (float) shown / (float) maxMedia) : 0f;
        int fh = (int) (bh * ratio);
        if (fh > 0) gg.fill(bx, by + bh - fh, bx + bw, by + bh, MEDIA_BAR_FILL);

        // ==================== 竖排文本（整体旋转 90°） ====================

        String txt = shown + " / " + maxMedia;
        int textW = font.width(txt);
        int lineH = font.lineHeight;

        int tx = bx + bw + 4;
        int ty = by + (bh - textW) / 2;

        var pose = gg.pose();
        pose.pushPose();
        pose.translate(tx, ty, 0);
        pose.mulPose(Axis.ZP.rotationDegrees(90f));
        gg.drawString(font, txt, 0, -lineH, 0xA020F0, false);
        pose.popPose();
    }

    // ==================== 鼠标交互 ====================

    /** 命中测试：鼠标是否落在某个 slot 的 16×16 区域内。 */
    private Slot findHoveredSlot(double mx, double my) {
        for (Slot slot : this.menu.slots) {
            int sx = this.leftPos + slot.x;
            int sy = this.topPos + slot.y;
            if (mx >= sx && mx < sx + 16 && my >= sy && my < sy + 16) {
                return slot;
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 先让父类处理 slot 点击（取出/放入物品）
        if (findHoveredSlot(mx, my) != null) {
            return super.mouseClicked(mx, my, button);
        }

        int q = findQuadrant(mx, my);
        if (q < 0) return super.mouseClicked(mx, my, button);

        // ===== 左键 =====
        if (button == 0) {
            if (hasShiftDown()) {
                // ⭐ Shift + 左键 = 用空白卷轴清空栈
                ModNetwork.sendTableAction(PacketTableAction.clearStack(q));
                return true;
            }
            // 普通左键 = 开始绘制
            HexCoord start = pixelToHex(mx, my, q);
            if (start == null) return false;
            drawingQuadrant = q;
            lastCoord = start;
            visitedInStroke.clear();
            visitedInStroke.add(start);
            return true;
        }

        // ===== 右键 =====
        if (button == 1) {
            if (hasShiftDown()) {
                // ⭐ Shift + 右键 = 入栈（考察）
                ModNetwork.sendTableAction(PacketTableAction.consider(q));
                return true;
            }
            // 普通右键 = 回退最后一条边
            ModNetwork.sendTableAction(PacketTableAction.undo(q));
            return true;
        }

        // ===== 中键 =====
        if (button == 2) {
            // ★ 调试探针 1
            Minecraft.getInstance().player.sendSystemMessage(
                    Component.literal("§b[调试] 中键命中象限 q=" + q + "，准备发 parse 包"));
            ModNetwork.sendTableAction(PacketTableAction.parse());
            return true;
        }

        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (findHoveredSlot(mx, my) != null) {
            return super.mouseDragged(mx, my, button, dx, dy);
        }
        if (drawingQuadrant < 0 || button != 0) {
            return super.mouseDragged(mx, my, button, dx, dy);
        }
        int q = drawingQuadrant;
        if (mx < qL[q] || mx > qR[q] || my < qT[q] || my > qB[q]) return true;

        HexCoord cur = pixelToHex(mx, my, q);
        if (cur == null || cur.equals(lastCoord)) return true;

        HexDir dir = lastCoord.immediateDelta(cur);
        if (dir == null) return true;
        if (visitedInStroke.contains(cur)) return true;

        // ⭐ 用 currentMedia() 判断，兼容本地乐观覆盖
        long media = currentMedia();
        if (media < 1) return true;

        ModNetwork.sendTableAction(PacketTableAction.draw(q, dir));

        HexPattern local = localPatterns[q];
        if (local == null) {
            localPatterns[q] = new HexPattern(dir, new ArrayList<HexAngle>());
        } else {
            List<HexAngle> angles = new ArrayList<>(local.getAngles());
            HexPattern copy = new HexPattern(local.getStartDir(), angles);
            if (copy.tryAppendDir(dir)) localPatterns[q] = copy;
            else return true;
        }
        // ⭐ 本地乐观扣减，写进 override
        localMediaOverride = Math.max(0L, media - 1);

        lastCoord = cur;
        visitedInStroke.add(cur);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0 && drawingQuadrant >= 0) {
            drawingQuadrant = -1;
            lastCoord = null;
            visitedInStroke.clear();
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    // ==================== 坐标换算（尖顶六边形，轴向坐标） ====================

    private static Vec2 hexToPixel(HexCoord c, float size) {
        float x = size * (float) Math.sqrt(3) * (c.getQ() + c.getR() / 2f);
        float y = size * 1.5f * c.getR();
        return new Vec2(x, y);
    }

    private HexCoord pixelToHex(double mouseX, double mouseY, int q) {
        float cx = (qL[q] + qR[q]) / 2f;
        float cy = (qT[q] + qB[q]) / 2f;
        float lx = (float) mouseX - cx;
        float ly = (float) mouseY - cy;
        float rf = ly / (HEX_SIZE * 1.5f);
        float qf = lx / (HEX_SIZE * (float) Math.sqrt(3)) - rf / 2f;
        return hexRound(qf, rf);
    }

    private static HexCoord hexRound(float qf, float rf) {
        float sf = -qf - rf;
        int q = Math.round(qf);
        int r = Math.round(rf);
        int s = Math.round(sf);
        float dq = Math.abs(q - qf);
        float dr = Math.abs(r - rf);
        float ds = Math.abs(s - sf);
        if (dq > dr && dq > ds) q = -r - s;
        else if (dr > ds) r = -q - s;
        return new HexCoord(q, r);
    }

    private int findQuadrant(double mx, double my) {
        for (int q = 0; q < 4; q++) {
            if (mx >= qL[q] && mx < qR[q] && my >= qT[q] && my < qB[q]) return q;
        }
        return -1;
    }

    // ==================== 其他 ====================

    private boolean hasISSSpell(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        return !SpellUnlockManager.getSpellsFromItem(s).isEmpty();
    }

    private static int argb(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    private void logCoords() {
        HexEncrypt.LOGGER.info("[UI][解析台] GUI 左上=({}, {})  尺寸 {}×{}",
                leftPos, topPos, imageWidth, imageHeight);
        for (int q = 0; q < 4; q++) {
            HexEncrypt.LOGGER.info("[UI][解析台] 象限{}  局部 ({},{})~({},{})  屏幕 ({},{})~({},{})",
                    q, qL[q] - leftPos, qT[q] - topPos, qR[q] - leftPos, qB[q] - topPos,
                    qL[q], qT[q], qR[q], qB[q]);
        }
        HexEncrypt.LOGGER.info("[UI][解析台] 媒质槽 局部=({},{})  媒质条 局部=({},{}) {}×{}",
                SpellInscriptionTableMenu.MEDIA_SLOT_X, SpellInscriptionTableMenu.MEDIA_SLOT_Y,
                SpellInscriptionTableMenu.MEDIA_BAR_X, SpellInscriptionTableMenu.MEDIA_BAR_Y,
                SpellInscriptionTableMenu.MEDIA_BAR_W, SpellInscriptionTableMenu.MEDIA_BAR_H);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gg);
        super.render(gg, mouseX, mouseY, partialTick);

        // ⭐ 鼠标悬停在栈图标上时优先显示 iota 的 tooltip
        int hoverQ = findHoveredStackQuadrant(mouseX, mouseY);
        if (hoverQ >= 0 && localStackDisplay[hoverQ] != null) {
            gg.renderTooltip(font, localStackDisplay[hoverQ], mouseX, mouseY);
        } else {
            this.renderTooltip(gg, mouseX, mouseY);
        }
    }
}