package io.github.liquiHg.hexencrypt.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家级的"准星覆盖"状态。
 * 当玩家画了 setTarget 操作符后，这个玩家的下一次施法会直接使用这里指定的目标，
 * 跳过 Utils.preCastTargetHelper 的射线检测。
 *
 * 用 UUID 存储，避免强引用 LivingEntity 导致内存泄漏。
 */
public final class HexCastTargetOverride {

    private static final Map<UUID, UUID> OVERRIDES = new ConcurrentHashMap<>();

    private HexCastTargetOverride() {
    }

    public static void set(ServerPlayer player, LivingEntity target) {
        if (target == null) {
            OVERRIDES.remove(player.getUUID());
        } else {
            OVERRIDES.put(player.getUUID(), target.getUUID());
        }
    }

    public static LivingEntity get(ServerPlayer player) {
        UUID targetId = OVERRIDES.get(player.getUUID());
        if (targetId == null) return null;
        if (!(player.level() instanceof ServerLevel sl)) return null;
        Entity e = sl.getEntity(targetId);
        return e instanceof LivingEntity le ? le : null;
    }

    public static boolean has(ServerPlayer player) {
        return OVERRIDES.containsKey(player.getUUID());
    }

    public static void clear(ServerPlayer player) {
        OVERRIDES.remove(player.getUUID());
    }
}