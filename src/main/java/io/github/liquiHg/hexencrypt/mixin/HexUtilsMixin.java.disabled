package io.github.liquiHg.hexencrypt.mixin;

import at.petrak.hexcasting.api.utils.HexUtils;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = HexUtils.class, remap = false)
public class HexUtilsMixin {

    /**
     * 当被查询的 key 属于 hexencrypt 命名空间，且查询的标签是 hexcasting:per_world_pattern 时，
     * 强制返回 true。这样无论是在 processRegistry 还是在 ScrungledPatternsSave 里，
     * 我们的法术都会被当作 Per-World 处理。
     */
    @Inject(method = "isOfTag", at = @At("HEAD"), cancellable = true, remap = false)
    private static <T> void hexencrypt$isOfTag(Registry<T> registry, ResourceKey<T> key, TagKey<T> tag,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (key.location().getNamespace().equals("hexencrypt")
                && tag.location().equals(new ResourceLocation("hexcasting", "per_world_pattern"))) {
            cir.setReturnValue(true);
        }
    }
}