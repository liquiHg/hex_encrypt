package io.github.liquiHg.hexencrypt.network;

import io.github.liquiHg.hexencrypt.util.HexTimbreClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SyncTimbrePacket {

    private final String timbre;

    public SyncTimbrePacket(String timbre) {
        this.timbre = timbre;
    }

    public SyncTimbrePacket(FriendlyByteBuf buf) {
        this.timbre = buf.readUtf();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(timbre);
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get();
        ctx.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> HexTimbreClientState.set(timbre)));
        ctx.setPacketHandled(true);
    }
}