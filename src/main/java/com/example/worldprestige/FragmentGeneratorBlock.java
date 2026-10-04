package com.example.worldprestige;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** 電力(FE)を消費して World Fragment を作る機械。右クリックで設定 GUI(入力速度の入力・取り出し)を開く。 */
public class FragmentGeneratorBlock extends Block implements EntityBlock {
    public FragmentGeneratorBlock(Properties properties) { super(properties); }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FragmentGeneratorBlockEntity(pos, state);
    }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != ModRegistry.FRAGMENT_GENERATOR_BE.get()) return null;
        return (lvl, pos, st, be) -> ((FragmentGeneratorBlockEntity) be).serverTick();
    }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                           InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof FragmentGeneratorBlockEntity be && player instanceof ServerPlayer sp) {
            PrestigeNetwork.openGenerator(sp, be);   // 設定 GUI を開く
        }
        return InteractionResult.CONSUME;
    }

    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof FragmentGeneratorBlockEntity be) {
            be.dropContents(level, pos);
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
