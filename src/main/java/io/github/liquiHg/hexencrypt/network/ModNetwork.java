package io.github.liquiHg.hexencrypt.network;

import io.github.liquiHg.hexencrypt.HexEncrypt;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Set;

public class ModNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(HexEncrypt.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;
    private static int nextId() {
        return packetId++;
    }

    public static void register() {
        CHANNEL.messageBuilder(SyncUnlockedSpellsPacket.class, nextId())
                .encoder(SyncUnlockedSpellsPacket::encode)
                .decoder(SyncUnlockedSpellsPacket::decode)
                .consumerMainThread(SyncUnlockedSpellsPacket::handle)
                .add();

        CHANNEL.messageBuilder(SyncTimbrePacket.class, nextId())
                .encoder(SyncTimbrePacket::encode)
                .decoder(SyncTimbrePacket::new)
                .consumerMainThread(SyncTimbrePacket::handle)
                .add();
    }

    /** 推送完整解锁集合（幂等全量覆盖）。 */
    public static void sendUnlockedSpells(ServerPlayer player, Set<String> spells) {
        if (player == null) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SyncUnlockedSpellsPacket(spells));
    }

    public static void sendTimbre(ServerPlayer player, String timbre) {
        if (player == null) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncTimbrePacket(timbre));
    }
}