package io.github.liquiHg.hexencrypt.network;

import io.github.liquiHg.hexencrypt.client.screen.SpellInscriptionTableScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：解析台权威状态。
 * media 改为 long，因为 Hex 的 QUENCHED_SHARD_UNIT = 300_000、QUENCHED_BLOCK_UNIT = 1_200_000。
 */
public record PacketTableSync(long media, CompoundTag quadrantNbt) {

    public static PacketTableSync of(long media, CompoundTag nbt) {
        return new PacketTableSync(media, nbt);
    }

    public static void encode(PacketTableSync msg, FriendlyByteBuf buf) {
        buf.writeVarLong(msg.media);          // ⭐ long 用 writeVarLong
        buf.writeNbt(msg.quadrantNbt);
    }

    public static PacketTableSync decode(FriendlyByteBuf buf) {
        return new PacketTableSync(buf.readVarLong(), buf.readNbt());  // ⭐ readVarLong
    }

    public static void handle(PacketTableSync msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientOnly.apply(msg)));
        ctx.setPacketHandled(true);
    }

    /** 只允许客户端加载的类。 */
    private static final class ClientOnly {
        static void apply(PacketTableSync msg) {
            if (Minecraft.getInstance().screen instanceof SpellInscriptionTableScreen screen) {
                screen.applyServerSync(msg.media(), msg.quadrantNbt());
            }
        }
    }
}