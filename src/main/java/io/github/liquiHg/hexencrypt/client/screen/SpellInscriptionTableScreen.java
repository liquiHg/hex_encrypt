package io.github.liquiHg.hexencrypt.client.screen;

import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.github.liquiHg.hexencrypt.block.SpellInscriptionTableMenu;
import io.github.liquiHg.hexencrypt.util.SpellUnlockManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public class SpellInscriptionTableScreen
        extends AbstractContainerScreen<SpellInscriptionTableMenu> {

    // ==================== 基础布局常量（256×256 采样） ====================

    private static final int GUI_WIDTH  = 256;
    private static final int GUI_HEIGHT = 256;

    /** 物品栏区域顶边（= 上方区域底边）。 */
    private static final int INVENTORY_TOP = 174;

    /** 物品栏左起点：(256 - 9*18)/2 = 47。 */
    private static final int PLAYER_INV_LEFT = 47;
    private static final int PLAYER_INV_RIGHT = PLAYER_INV_LEFT + 9 * 18;   // 209

    private static final int PLAYER_BACKPACK_TOP = INVENTORY_TOP;
    private static final int PLAYER_HOTBAR_TOP   = PLAYER_BACKPACK_TOP + 3 * 18 + 4;   // 232
    private static final int PLAYER_INV_BOTTOM   = PLAYER_HOTBAR_TOP + 18;             // 250

    /** 物品槽位内容区左上角：(256/2 - 8, 174/2 - 8) = (120, 79)。 */
    private static final int SLOT_LEFT_LOCAL = 120;
    private static final int SLOT_TOP_LOCAL  = 79;

    private static final ResourceLocation BG = new ResourceLocation(
            HexEncrypt.MODID, "textures/gui/spell_analysis_table.png");

    // ==================== Hex Casting 卷轴 UI 资源 ====================

    /** 与 at.petrak.hexcasting.client.gui.PatternTooltipComponent#PRISTINE_BG 一致。 */
    private static final ResourceLocation SCROLL_PRISTINE =
            new ResourceLocation("hexcasting", "textures/gui/scroll.png");
    /** 与 PatternTooltipComponent#ANCIENT_BG 一致（先留着，后面切状态可以用）。 */
    private static final ResourceLocation SCROLL_ANCIENT =
            new ResourceLocation("hexcasting", "textures/gui/scroll_ancient.png");

    /** 卷轴 UI 是 64×64 的纹理，PatternTooltipComponent.SIZE 也是 64。 */
    private static final int SCROLL_SIZE = 64;

    private boolean coordsLogged = false;

    public SpellInscriptionTableScreen(SpellInscriptionTableMenu menu,
                                       Inventory inv,
                                       Component title) {
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
    }

    // ==================== 渲染 ====================

    @Override
    protected void renderBg(GuiGraphics gg, float partialTick, int mouseX, int mouseY) {
        // 背景：GUI 与纹理同为 256×256 → 1:1 绘制。
        gg.blit(BG, leftPos, topPos, 0, 0, imageWidth, imageHeight);

        // 上方区域（十字所在的区域）
        int regionLeft   = leftPos;
        int regionTop    = topPos;
        int regionRight  = leftPos + imageWidth;
        int regionBottom = topPos + INVENTORY_TOP;

        int crossX = (regionLeft + regionRight) / 2;
        int crossY = (regionTop + regionBottom) / 2;

        // 1) 先画四象限里的 4 张卷轴
        renderQuadrantScrolls(gg, regionLeft, regionTop, regionRight, regionBottom);

        // 2) 再画十字（这样十字会叠在卷轴之上，形成"被十字分隔"的观感）
        boolean active = hasISSSpell(menu.getStoredItem());
        drawCross(gg, crossX, crossY,
                regionLeft, regionTop, regionRight, regionBottom, active);

        if (!coordsLogged) {
            coordsLogged = true;
            logCoordinates(regionLeft, regionTop, regionRight, regionBottom, crossX, crossY);
        }
    }

    // ==================== 四象限卷轴绘制 ====================

    /**
     * 在十字分隔出的四个象限中各绘制一张 Hex 空白卷轴 UI。
     *
     * 布局（以 GUI 局部坐标为例，left=0, top=0, right=256, bottom=174）：
     *
     *   (0,0) ───────── cx=128 ───────── (256,0)
     *     │   [左上卷轴]  │  [右上卷轴]   │
     *     │              │              │
     *   cy=87 ───────────┼───────────────   ← 十字
     *     │   [左下卷轴]  │  [右下卷轴]   │
     *     │              │              │
     *   (0,174) ─────────────────────── (256,174)
     *
     * 每个象限中心 = 该象限矩形对角中点；卷轴以中心点居中放置。
     */
    private void renderQuadrantScrolls(GuiGraphics gg,
                                       int left, int top, int right, int bottom) {
        int cx = (left + right) / 2;
        int cy = (top + bottom) / 2;

        int half = SCROLL_SIZE / 2;

        // {中心 X, 中心 Y, 卷轴纹理} —— 目前四张都用原版空白卷轴
        int[][] quadrantCenters = {
                {(left + cx) / 2, (top + cy) / 2},       // 左上
                {(cx + right) / 2, (top + cy) / 2},      // 右上
                {(left + cx) / 2, (cy + bottom) / 2},    // 左下
                {(cx + right) / 2, (cy + bottom) / 2},   // 右下
        };

        for (int[] c : quadrantCenters) {
            int sx = c[0] - half;
            int sy = c[1] - half;

            // 空白卷轴 = 只有背景，没有图案。
            gg.blit(SCROLL_PRISTINE,
                    sx, sy,
                    0, 0,
                    SCROLL_SIZE, SCROLL_SIZE,
                    SCROLL_SIZE, SCROLL_SIZE);

            // 若以后想让某个象限显示实际图案，只需在 blit 之后按 PatternTooltipComponent
            // 里 renderImage 的做法叠一层 HexPattern 渲染即可，例如：
            //
            //   HexPattern pattern = ...;
            //   PatternRenderer.renderPattern(pattern,
            //           gg.pose(), new Point2F(sx + half, sy + half),
            //           64f, PatternRenderer.DEFAULT_SIZE, 0f, 0f, 0f);
            //
            // （具体签名以你使用的 Hex Casting 版本为准。）
        }
    }

    // ==================== 十字 ====================

    private void drawCross(GuiGraphics gg, int cx, int cy,
                           int left, int top, int right, int bottom,
                           boolean active) {
        int alpha = active ? 220 : 90;
        int color = (alpha << 24) | 0xBFE0FF;

        gg.fill(left, cy, right, cy + 1, color);
        gg.fill(cx, top, cx + 1, bottom, color);

        if (active) {
            int armLen = 10;
            int coreColor = (240 << 24) | 0xFFFFFF;
            gg.fill(cx - armLen, cy, cx + armLen, cy + 1, coreColor);
            gg.fill(cx, cy - armLen, cx + 1, cy + armLen, coreColor);
        }
    }

    private boolean hasISSSpell(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return !SpellUnlockManager.getSpellsFromItem(stack).isEmpty();
    }

    // ==================== 坐标日志（保持原样） ====================

    private void logCoordinates(int regionLeft, int regionTop, int regionRight, int regionBottom,
                                int crossX, int crossY) {
        int invLeft   = leftPos + PLAYER_INV_LEFT;
        int invTop    = topPos  + INVENTORY_TOP;
        int invRight  = leftPos + PLAYER_INV_RIGHT;
        int invBottom = topPos  + PLAYER_INV_BOTTOM;

        int slotLeft   = leftPos + SLOT_LEFT_LOCAL - 1;
        int slotTop    = topPos  + SLOT_TOP_LOCAL  - 1;
        int slotRight  = slotLeft + 18;
        int slotBottom = slotTop  + 18;

        HexEncrypt.LOGGER.info("[UI][解析台] ============== 坐标一览（屏幕绝对坐标） ==============");
        HexEncrypt.LOGGER.info("[UI][解析台] GUI 左上角      leftPos={}, topPos={}", leftPos, topPos);
        HexEncrypt.LOGGER.info("[UI][解析台] GUI 尺寸        {}×{}（= 纹理 256×256）",
                imageWidth, imageHeight);
        HexEncrypt.LOGGER.info("[UI][解析台] 物品栏四角      左上=({}, {})  右上=({}, {})  左下=({}, {})  右下=({}, {})",
                invLeft, invTop, invRight, invTop, invLeft, invBottom, invRight, invBottom);
        HexEncrypt.LOGGER.info("[UI][解析台] 物品栏局部      左={} 上={} 右={} 下={}",
                PLAYER_INV_LEFT, INVENTORY_TOP, PLAYER_INV_RIGHT, PLAYER_INV_BOTTOM);
        HexEncrypt.LOGGER.info("[UI][解析台] 物品槽四角      左上=({}, {})  右上=({}, {})  左下=({}, {})  右下=({}, {})",
                slotLeft, slotTop, slotRight, slotTop, slotLeft, slotBottom, slotRight, slotBottom);
        HexEncrypt.LOGGER.info("[UI][解析台] 物品槽局部      内容左上=({}, {})  边框左上=({}, {})  尺寸 18×18",
                SLOT_LEFT_LOCAL, SLOT_TOP_LOCAL, SLOT_LEFT_LOCAL - 1, SLOT_TOP_LOCAL - 1);
        HexEncrypt.LOGGER.info("[UI][解析台] 十字中心        屏幕=({}, {})  相对 GUI=({}, {})",
                crossX, crossY, crossX - leftPos, crossY - topPos);
        HexEncrypt.LOGGER.info("[UI][解析台] 上方区域        屏幕 左={} 上={} 右={} 下={}",
                regionLeft, regionTop, regionRight, regionBottom);
        HexEncrypt.LOGGER.info("[UI][解析台] 上方区域        局部 宽={} 高={}",
                imageWidth, INVENTORY_TOP);
        HexEncrypt.LOGGER.info("[UI][解析台] 卷轴 UI 尺寸     {}×{}  （Hex 原版 PatternTooltipComponent）",
                SCROLL_SIZE, SCROLL_SIZE);
        HexEncrypt.LOGGER.info("[UI][解析台] ===============================================");
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gg);
        super.render(gg, mouseX, mouseY, partialTick);
        this.renderTooltip(gg, mouseX, mouseY);
    }
}