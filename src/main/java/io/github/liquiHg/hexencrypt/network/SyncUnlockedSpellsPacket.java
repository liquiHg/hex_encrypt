package io.github.liquiHg.hexencrypt.network;

import io.github.liquiHg.hexencrypt.client.ClientSpellCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

public class SyncUnlockedSpellsPacket {
    private final Set<String> unlockedSpells;

    public SyncUnlockedSpellsPacket(Set<String> unlockedSpells) {
        this.unlockedSpells = unlockedSpells;
    }

    public static void encode(SyncUnlockedSpellsPacket msg, FriendlyByteBuf buf) {
        buf.writeCollection(msg.unlockedSpells, FriendlyByteBuf::writeUtf);
    }

    public static SyncUnlockedSpellsPacket decode(FriendlyByteBuf buf) {
        Set<String> spells = buf.readCollection(HashSet::new, FriendlyByteBuf::readUtf);
        return new SyncUnlockedSpellsPacket(spells);
    }

    public static void handle(SyncUnlockedSpellsPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientSpellCache.update(msg.unlockedSpells)));
        ctx.get().setPacketHandled(true);
    }
}