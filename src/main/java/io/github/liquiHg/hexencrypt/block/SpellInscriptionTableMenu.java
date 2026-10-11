package io.github.liquiHg.hexencrypt.block;

import io.github.liquiHg.hexencrypt.registry.ModBlocks;
import io.github.liquiHg.hexencrypt.registry.ModMenuTypes;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class SpellInscriptionTableMenu extends AbstractContainerMenu {

    // === 与 Screen 里保持一致，改一处记得改另一处（256×256 采样） ===

    /** 上方区域底边（= 物品栏顶边）。 */
    private static final int INVENTORY_TOP = 174;
    /** 物品栏左起点（水平居中，(256 - 9*18)/2 = 47）。 */
    private static final int PLAYER_INV_LEFT = 47;
    /** 快捷栏 y。 */
    private static final int PLAYER_HOTBAR_TOP = 232;

    /** 物品槽位内容区左上角（256/2 - 8 = 120，上方区域中心 y = 87 → 79）。 */
    private static final int SLOT_LEFT_LOCAL = 120;
    private static final int SLOT_TOP_LOCAL  = 79;

    private final SpellInscriptionTableBlockEntity blockEntity;
    private final Container container;
    private final ContainerLevelAccess access;

    public SpellInscriptionTableMenu(int id, Inventory playerInv) {
        this(id, playerInv, null);
    }

    public SpellInscriptionTableMenu(int id, Inventory playerInv, SpellInscriptionTableBlockEntity be) {
        super(ModMenuTypes.SPELL_INSCRIPTION_TABLE.get(), id);
        this.blockEntity = be;
        this.access = be != null
                ? ContainerLevelAccess.create(be.getLevel(), be.getBlockPos())
                : ContainerLevelAccess.NULL;

        ItemStack initial = (be != null) ? be.getStoredItem() : ItemStack.EMPTY;
        this.container = new SimpleContainer(1);
        this.container.setItem(0, initial);

        // 中心槽位
        this.addSlot(new Slot(container, 0, SLOT_LEFT_LOCAL, SLOT_TOP_LOCAL) {
            @Override
            public int getMaxStackSize() { return 1; }

            @Override
            public void setChanged() {
                super.setChanged();
                if (blockEntity != null) {
                    blockEntity.setStoredItem(getItem());
                }
            }
        });

        // 玩家背包 3×9
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9,
                        PLAYER_INV_LEFT + col * 18,
                        INVENTORY_TOP + row * 18));
            }
        }

        // 快捷栏 1×9
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col,
                    PLAYER_INV_LEFT + col * 18,
                    PLAYER_HOTBAR_TOP));
        }
    }

    public ItemStack getStoredItem() {
        return container.getItem(0);
    }

    public SpellInscriptionTableBlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stackInSlot = slot.getItem();
        ItemStack copy = stackInSlot.copy();

        if (index == 0) {
            if (!this.moveItemStackTo(stackInSlot, 1, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!this.moveItemStackTo(stackInSlot, 0, 1, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stackInSlot.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModBlocks.SPELL_INSCRIPTION_TABLE.get());
    }
}