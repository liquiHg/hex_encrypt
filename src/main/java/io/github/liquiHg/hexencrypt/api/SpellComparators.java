package io.github.liquiHg.hexencrypt.api;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局策略注册表。线程安全的静态容器。
 *
 * <p>建议在 {@code FMLCommonSetupEvent} 或模组构造里集中注册：
 * <pre>{@code
 * SpellComparators.register(new FunctionalGroupComparator());
 * }</pre>
 */
public final class SpellComparators {

    private static final Map<ResourceLocation, SpellComparator> REGISTRY = new ConcurrentHashMap<>();
    private static volatile List<SpellComparator> SORTED = List.of();

    private SpellComparators() {}

    public static synchronized void register(SpellComparator comparator) {
        REGISTRY.put(comparator.getId(), comparator);
        rebuild();
    }

    public static synchronized void unregister(ResourceLocation id) {
        REGISTRY.remove(id);
        rebuild();
    }

    private static void rebuild() {
        List<SpellComparator> list = new ArrayList<>(REGISTRY.values());
        list.sort(Comparator.comparingInt(SpellComparator::priority).reversed());
        SORTED = List.copyOf(list);
    }

    public static List<SpellComparator> all() { return SORTED; }

    @Nullable
    public static SpellComparator get(ResourceLocation id) { return REGISTRY.get(id); }

    /**
     * 按请求顺序跑一遍：优先走 preferredKind，其余按优先级。
     * 只返回第一个 Matched；若全部 NoMatch，返回最后一个 NoMatch；
     * 若某个策略返回 Invalid，会覆盖先前的 NoMatch（Invalid 更具体）。
     */
    public static ComparisonResult run(ComparisonRequest req) {
        // 1) 优先尝试指定策略
        if (req.preferredKind() != null) {
            SpellComparator preferred = REGISTRY.get(req.preferredKind());
            if (preferred != null && preferred.applies(req)) {
                ComparisonResult r = preferred.compare(req);
                if (r instanceof ComparisonResult.Matched) return r;
            }
        }
        // 2) 按优先级依次尝试
        ComparisonResult last = null;
        for (SpellComparator c : SORTED) {
            if (!c.applies(req)) continue;
            ComparisonResult r = c.compare(req);
            if (r instanceof ComparisonResult.Matched) return r;
            if (r instanceof ComparisonResult.Invalid || last == null) {
                last = r;
            }
        }
        return last != null ? last : ComparisonResult.noMatch("no comparator applicable");
    }
}