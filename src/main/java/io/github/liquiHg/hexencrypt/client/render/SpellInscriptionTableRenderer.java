package io.github.liquiHg.hexencrypt.client.render;

import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.client.render.PatternColors;
import at.petrak.hexcasting.client.render.WorldlyPatternRenderHelpers;
import at.petrak.hexcasting.common.particles.ConjureParticleOptions;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.liquiHg.hexencrypt.block.SpellInscriptionTableBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

public class SpellInscriptionTableRenderer
        implements BlockEntityRenderer<SpellInscriptionTableBlockEntity> {

    public SpellInscriptionTableRenderer(BlockEntityRendererProvider.Context ctx) {}

    // ==================== 环绕轨道参数 ====================

    private static final float ORBIT_RADIUS   = 0.30f;
    private static final float ORBIT_CENTER_Y = 1.50f;
    private static final float ORBIT_SPEED    = 0.6f;
    private static final double ORBIT_PHI     = Math.PI / 6;

    /** 高度浮动幅度（格）。0 = 关闭浮动。 */
    private static final float ORBIT_BOB_AMPLITUDE = 0.06f;

    /** 高度浮动频率（弧度 / 秒）。值越大上下起伏越快。 */
    private static final float ORBIT_BOB_FREQ = 1.6f;

    /** 每象限浮动相位的随机散布范围（弧度）。 */
    private static final float ORBIT_BOB_PHASE_SPREAD = (float)(Math.PI * 2);

    /** 图案在环绕轨道上的相对缩放（1.0 = 约 1 格）。 */
    private static final float ORBIT_SCALE    = 0.30f;

// ==================== 四角参数 ====================

    private static final float CORNER_INSET = 0.20f;
    private static final float CORNER_Y     = 1.01f;

    /** 四角图案的相对缩放。 */
    private static final float CORNER_SCALE = 0.30f;

    // ==================== 主入口 ====================

    @Override
    public void render(SpellInscriptionTableBlockEntity be,
                       float partialTick,
                       PoseStack pose,
                       MultiBufferSource buffers,
                       int packedLight,
                       int packedOverlay) {

        long time = be.getLevel() == null ? 0L : be.getLevel().getGameTime();
        float t = (time + partialTick) / 20f;

        renderStoredItem(be, pose, buffers, packedLight, packedOverlay, t);

        renderOrbitPatterns(be, pose, buffers, packedLight, t);
        renderCornerPatterns(be, pose, buffers, packedLight);
    }

    // ==================== 中央槽物品（保持原有行为） ====================

    private void renderStoredItem(SpellInscriptionTableBlockEntity be, PoseStack pose,
                                  MultiBufferSource buffers,
                                  int packedLight, int packedOverlay, float t) {
        ItemStack stack = be.getStoredItem();
        if (stack.isEmpty()) return;

        pose.pushPose();
        pose.translate(0.5, 1.05 + Math.sin(t * 1.2) * 0.05, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(t * 24f));
        pose.scale(0.65f, 0.65f, 0.65f);

        Minecraft.getInstance().getItemRenderer().renderStatic(
                stack, ItemDisplayContext.FIXED,
                packedLight, packedOverlay, pose, buffers,
                be.getLevel(), (int) be.getBlockPos().asLong());
        pose.popPose();
    }

    /**
     * 由方块位置 + 象限号派生一个稳定的浮动相位。
     * 同一方块、同一象限每次渲染都得到相同的相位，避免图案抖动。
     * 不同方块、不同象限相位不同，看起来像各飘各的。
     */
    private static float bobPhaseFor(int blockSeed, int q) {
        int h = blockSeed * 0x9E3779B1 + q * 0x85EBCA77;
        // 把 hash 映射到 [0, 2π)
        float unit = ((h >>> 8) & 0xFFFF) / 65535f;
        return unit * ORBIT_BOB_PHASE_SPREAD;
    }

    // ==================== 半球壳环绕 ====================

    private void renderOrbitPatterns(SpellInscriptionTableBlockEntity be, PoseStack pose,
                                     MultiBufferSource buffers, int light, float t) {

        List<Integer> active = new ArrayList<>(4);
        for (int q = 0; q < 4; q++) {
            if (be.getClientStackPattern(q) != null) active.add(q);
        }
        if (active.isEmpty()) return;

        int n = active.size();
        double sinPhi = Math.sin(ORBIT_PHI);
        double cosPhi = Math.cos(ORBIT_PHI);
        int baseSeed = be.getBlockPos().hashCode();

        double thetaDegFactor = 180.0 / Math.PI;
        double phiDeg = ORBIT_PHI * thetaDegFactor;

        for (int idx = 0; idx < n; idx++) {
            int q = active.get(idx);

            long startTick = be.getTransferStartTick(q);
            if (startTick > 0L) {
                long elapsed = (be.getLevel() == null ? 0L : be.getLevel().getGameTime()) - startTick;
                if (elapsed >= 0L
                        && elapsed < SpellInscriptionTableBlockEntity.TRANSFER_DURATION_TICKS) {
                    continue;   // 该象限正在播放"角落 → 环绕"动画，暂不渲染环绕图案
                }
            }

            HexPattern pattern = be.getClientStackPattern(q);

            double theta = t * ORBIT_SPEED + idx * (2.0 * Math.PI / n);

            // ⭐ 每个图案独立的浮动相位（同一方块、同一象限永远一致）
            float bobPhase = bobPhaseFor(baseSeed, q);

            // ⭐ 高度浮动
            float bob = (float) Math.sin(t * ORBIT_BOB_FREQ + bobPhase) * ORBIT_BOB_AMPLITUDE;

            float px = (float) (0.5 + ORBIT_RADIUS * sinPhi * Math.cos(theta));
            float py = (float) (ORBIT_CENTER_Y + ORBIT_RADIUS * cosPhi) + bob;   // ⭐ +bob
            float pz = (float) (0.5 + ORBIT_RADIUS * sinPhi * Math.sin(theta));

            pose.pushPose();
            pose.translate(px, py, pz);

            // === 竖直环绕的关键两步 ===
            double thetaDeg = theta * thetaDegFactor;
            pose.mulPose(Axis.YP.rotationDegrees((float)(90.0 - thetaDeg)));
            pose.mulPose(Axis.XP.rotationDegrees((float)-(90.0 - phiDeg)));
            // ========================

            pose.scale(ORBIT_SCALE, ORBIT_SCALE, 1f);

            WorldlyPatternRenderHelpers.renderPattern(
                    pattern,
                    WorldlyPatternRenderHelpers.WORLDLY_SETTINGS_WOBBLY,
                    PatternColors.DEFAULT_PATTERN_COLOR,
                    baseSeed * 31 + q,
                    pose, buffers,
                    null, null,
                    light,
                    1);

            pose.popPose();
        }
    }

    // ==================== 方块顶面四角 ====================

    private void renderCornerPatterns(SpellInscriptionTableBlockEntity be, PoseStack pose,
                                      MultiBufferSource buffers, int light) {

        float a = CORNER_INSET;
        float b = 1f - CORNER_INSET;

        // 0=左上(x-,z-)  1=右上(x+,z-)  2=左下(x-,z+)  3=右下(x+,z+)
        float[][] corners = {
                { a, CORNER_Y, a },
                { b, CORNER_Y, a },
                { a, CORNER_Y, b },
                { b, CORNER_Y, b }
        };

        int baseSeed = be.getBlockPos().hashCode();

        for (int q = 0; q < 4; q++) {
            HexPattern pattern = be.getQuadrantPattern(q);
            if (pattern == null) continue;

            pose.pushPose();
            pose.translate(corners[q][0], corners[q][1], corners[q][2]);
            pose.mulPose(Axis.XP.rotationDegrees(90f));
            pose.scale(CORNER_SCALE, CORNER_SCALE, 1f);

            WorldlyPatternRenderHelpers.renderPattern(
                    pattern,
                    WorldlyPatternRenderHelpers.WORLDLY_SETTINGS,
                    PatternColors.DEFAULT_PATTERN_COLOR,
                    baseSeed * 17 + q,
                    pose, buffers,
                    null, null,
                    light,
                    1);

            pose.popPose();
        }
    }

    @Override
    public boolean shouldRenderOffScreen(SpellInscriptionTableBlockEntity be) {
        return false;
    }
}