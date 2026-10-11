package io.github.liquiHg.hexencrypt.api;

import at.petrak.hexcasting.api.casting.math.HexPattern;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 铭刻台的只读访问视图。
 *
 * <p>所有方法都返回快照或不可变数据，调用方不应假设返回对象可以在下一 tick 继续使用。
 * 允许返回 null 表示"该字段不存在"。
 */
public interface InscriptionTableAccess {

    @Nullable Level getLevel();
    @Nullable BlockPos getPos();

    /** 中央槽物品。可能为空 ItemStack（永不返回 null）。 */
    ItemStack getCentralItem();

    /** 中央槽物品中提取出的 HexPattern；没有则为 null。 */
    @Nullable HexPattern getCentralPattern();

    /**
     * 四个象限栈里的图案快照。
     * 索引 0..3 分别对应左上/右上/左下/右下。
     * 空槽位为 null。
     */
    HexPattern[] getStackPatterns();

    /** 当前媒质值。 */
    long getMedia();
    long getMaxMedia();

    /** 是否"可以立即比对"：中央槽有图案 && 至少 3 个象限有栈。 */
    default boolean isReady() {
        if (getCentralPattern() == null) return false;
        int filled = 0;
        for (HexPattern p : getStackPatterns()) if (p != null) filled++;
        return filled >= 3;
    }
}