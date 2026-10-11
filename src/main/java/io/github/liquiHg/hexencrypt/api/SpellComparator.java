package io.github.liquiHg.hexencrypt.api;

import net.minecraft.resources.ResourceLocation;

/**
 * 一次比对的策略。UI 鼠标事件、Hex 法术、其他模组都通过注册表调用。
 *
 * <p>实现必须是**纯函数**：除了读取 {@link ComparisonRequest#access()} 之外
 * 不产生任何副作用（不发卷轴、不发聊天消息、不改方块状态）。
 * 副作用由调用方决定。
 */
public interface SpellComparator {

    /** 策略唯一 ID，例如 hexencrypt:functional_group */
    ResourceLocation getId();

    /** 优先级：数值越大越先被尝试。内置实现建议用 100。 */
    default int priority() { return 100; }

    /** 快速剪枝：这一步返回 false 时 {@link #compare} 不会被调用。 */
    default boolean applies(ComparisonRequest req) {
        return req.access().isReady();
    }

    /** 执行比对。永不返回 null。 */
    ComparisonResult compare(ComparisonRequest req);
}