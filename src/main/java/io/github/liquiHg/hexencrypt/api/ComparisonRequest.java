package io.github.liquiHg.hexencrypt.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * 一次比对请求。
 *
 * @param access         方块只读视图
 * @param preferredKind  优先使用的策略 ID，null 表示走默认顺序
 * @param caller         调用者玩家，可为 null（例如机器自动化）
 */
public record ComparisonRequest(
        InscriptionTableAccess access,
        @Nullable ResourceLocation preferredKind,
        @Nullable Player caller
) {
    public static ComparisonRequest of(InscriptionTableAccess access) {
        return new ComparisonRequest(access, null, null);
    }
    public static ComparisonRequest of(InscriptionTableAccess access, ResourceLocation kind) {
        return new ComparisonRequest(access, kind, null);
    }
}