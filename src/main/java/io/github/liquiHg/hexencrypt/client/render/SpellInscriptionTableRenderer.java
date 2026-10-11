package io.github.liquiHg.hexencrypt.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.liquiHg.hexencrypt.block.SpellInscriptionTableBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

public class SpellInscriptionTableRenderer
        implements BlockEntityRenderer<SpellInscriptionTableBlockEntity> {

    public SpellInscriptionTableRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(SpellInscriptionTableBlockEntity be,
                       float partialTick,
                       PoseStack pose,
                       MultiBufferSource buffers,
                       int packedLight,
                       int packedOverlay) {

        ItemStack stack = be.getStoredItem();
        if (stack.isEmpty()) return;

        long time = be.getLevel() == null ? 0L : be.getLevel().getGameTime();
        float t = (time + partialTick) / 20f;

        pose.pushPose();
        // 悬浮在方块上方
        pose.translate(0.5, 1.05 + Math.sin(t * 1.2) * 0.05, 0.5);
        // 缓慢自转
        pose.mulPose(Axis.YP.rotationDegrees(t * 24f));
        pose.scale(0.65f, 0.65f, 0.65f);

        Minecraft.getInstance().getItemRenderer().renderStatic(
                stack,
                ItemDisplayContext.FIXED,
                packedLight,
                packedOverlay,
                pose,
                buffers,
                be.getLevel(),
                (int) be.getBlockPos().asLong()
        );

        pose.popPose();
    }

    /** 允许渲染距离超出默认值（方块在视锥外仍显示悬浮物品）。 */
    @Override
    public boolean shouldRenderOffScreen(SpellInscriptionTableBlockEntity be) {
        return false;
    }
}