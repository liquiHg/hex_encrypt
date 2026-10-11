package io.github.liquiHg.hexencrypt.block;

import io.github.liquiHg.hexencrypt.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

public class SpellInscriptionTableBlockEntity extends BlockEntity implements MenuProvider {

    /** 广播 BE 数据的最大距离（方块）。 */
    private static final double SYNC_RADIUS = 64.0;

    private ItemStack storedItem = ItemStack.EMPTY;

    public SpellInscriptionTableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SPELL_INSCRIPTION_TABLE.get(), pos, state);
    }

    public ItemStack getStoredItem() {
        return storedItem;
    }

    public void setStoredItem(ItemStack stack) {
        this.storedItem = (stack == null) ? ItemStack.EMPTY : stack;
        setChanged();

        if (level instanceof ServerLevel sl) {
            PacketDistributor.TRACKING_CHUNK
                    .with(() -> sl.getChunkAt(worldPosition))
                    .send(ClientboundBlockEntityDataPacket.create(this));
        }
    }

    // ==================== NBT ====================

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (!storedItem.isEmpty()) {
            tag.put("StoredItem", storedItem.save(new CompoundTag()));
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("StoredItem")) {
            storedItem = ItemStack.of(tag.getCompound("StoredItem"));
        } else {
            storedItem = ItemStack.EMPTY;
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        // 注意：即使为空也要显式写入一个"空物品"占位，
        // 否则客户端无法区分"从未有过物品"和"物品被取走"。
        tag.put("StoredItem", storedItem.save(new CompoundTag()));
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        super.handleUpdateTag(tag);
        if (tag.contains("StoredItem")) {
            storedItem = ItemStack.of(tag.getCompound("StoredItem"));
        } else {
            storedItem = ItemStack.EMPTY;
        }
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ==================== MenuProvider ====================

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.hexencrypt.spell_inscription_table");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        return new SpellInscriptionTableMenu(containerId, playerInv, this);
    }
}