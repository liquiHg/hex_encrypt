package io.github.liquiHg.hexencrypt.api;

import at.petrak.hexcasting.api.casting.math.HexPattern;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;

public sealed interface ComparisonResult
        permits ComparisonResult.Matched, ComparisonResult.NoMatch, ComparisonResult.Invalid {

    /** 匹配成功。 */
    record Matched(
            ResourceLocation issSpellId,     // 匹配到的 ISS 法术（可选，可能为 null）
            HexPattern canonicalPattern,     // 完整图案
            int matchedSegmentEnd,           // 官能团结束的字符索引（后缀从这里开始）
            String suffix,                   // 后缀原文
            Map<Character, Integer> counts,  // 后缀字符分布
            List<Integer> quadrantOrder      // 用到的象限索引，按匹配顺序
    ) implements ComparisonResult {
        public int suffixLength() {
            return suffix.length();
        }
    }

    /** 没匹配上。 */
    record NoMatch(String reason) implements ComparisonResult {}

    /** 输入非法（例如中央槽不是图案、方块离线）。 */
    record Invalid(String reason) implements ComparisonResult {}

    // ===== 便捷构造 =====
    static ComparisonResult noMatch(String reason) { return new NoMatch(reason); }
    static ComparisonResult invalid(String reason) { return new Invalid(reason); }
}