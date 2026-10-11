package io.github.liquiHg.hexencrypt.comparator;

import at.petrak.hexcasting.api.casting.math.HexPattern;
import io.github.liquiHg.hexencrypt.HexEncrypt;
import io.github.liquiHg.hexencrypt.api.*;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 官能团序列匹配：按象限 0→1→2→3 顺序，在中央图案里依次查找每个栈图案。
 * 匹配成功的字符位置之后就是"后缀"。
 */
public class FunctionalGroupComparator implements SpellComparator {

    public static final ResourceLocation ID =
            new ResourceLocation(HexEncrypt.MODID, "functional_group");

    @Override public ResourceLocation getId() { return ID; }

    @Override public int priority() { return 100; }

    @Override
    public ComparisonResult compare(ComparisonRequest req) {
        InscriptionTableAccess access = req.access();
        HexPattern central = access.getCentralPattern();
        if (central == null) return ComparisonResult.invalid("central slot has no pattern");

        HexPattern[] stacks = access.getStackPatterns();

        // 收集非空栈，按索引升序
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < stacks.length; i++) if (stacks[i] != null) order.add(i);
        if (order.size() < 3) return ComparisonResult.noMatch("need at least 3 filled stacks");

        String fullSig = central.anglesSignature();
        int cursor = 0;

        for (int q : order) {
            HexPattern sub = stacks[q];
            String subSig = sub.anglesSignature();
            if (subSig.isEmpty()) continue;

            int pos = fullSig.indexOf(subSig, cursor);
            if (pos < 0) {
                return ComparisonResult.noMatch(
                        "quadrant " + (q + 1) + " segment " + subSig + " not found after index " + cursor);
            }
            cursor = pos + subSig.length();
        }

        String suffix = fullSig.substring(cursor);
        Map<Character, Integer> counts = new HashMap<>();
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            counts.merge(c, 1, Integer::sum);
        }

        return new ComparisonResult.Matched(
                null,                       // 目前不反查 ISS 法术，预留
                central,
                cursor,
                suffix,
                counts,
                order
        );
    }
}