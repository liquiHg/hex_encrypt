package io.github.liquiHg.hexencrypt.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class SpellInscriptionTableBlock extends BaseEntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 桌面高度：上半砖样式，不让玩家踩在上面视觉穿模。 */
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 12, 16);

    public SpellInscriptionTableBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // ==================== 交互 ====================

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SpellInscriptionTableBlockEntity table)) return InteractionResult.PASS;

        // 客户端只负责触发动画；真正的状态改变在服务端
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        ItemStack held = player.getItemInHand(hand);

        // 1) 手持物品 → 直接尝试放入槽位
        if (!held.isEmpty()) {
            // 槽位为空 → 放入
            if (table.getStoredItem().isEmpty()) {
                ItemStack one = held.copy();
                one.setCount(1);
                table.setStoredItem(one);
                held.shrink(1);
                return InteractionResult.CONSUME;
            }

            // 槽位已有物品 → 交换（旧的还给玩家）
            ItemStack old = table.getStoredItem();
            ItemStack one = held.copy();
            one.setCount(1);
            table.setStoredItem(one);
            held.shrink(1);

            if (!player.getInventory().add(old)) {
                player.drop(old, false);
            }
            return InteractionResult.CONSUME;
        }

        // 2) 空手 + 潜行 → 取出物品
        if (player.isShiftKeyDown()) {
            ItemStack stored = table.getStoredItem();
            if (!stored.isEmpty()) {
                table.setStoredItem(ItemStack.EMPTY);
                if (!player.getInventory().add(stored)) {
                    player.drop(stored, false);
                }
                return InteractionResult.CONSUME;
            }
        }

        // 3) 空手 + 不潜行 → 打开 UI
        player.openMenu(table);
        return InteractionResult.CONSUME;
    }

    // ==================== 方块实体 ====================

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpellInscriptionTableBlockEntity(pos, state);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof SpellInscriptionTableBlockEntity table) {
                ItemStack stack = table.getStoredItem();
                if (!stack.isEmpty()) {
                    Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}