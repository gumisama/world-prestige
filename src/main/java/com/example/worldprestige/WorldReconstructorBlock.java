package com.example.worldprestige;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** 右クリックで World Prestige の強化 GUI (/prestige と同じ画面) を開くブロック。 */
public class WorldReconstructorBlock extends Block {
    public WorldReconstructorBlock(Properties properties) { super(properties); }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                           InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) {
            PrestigeNetwork.open(sp);   // 強化 GUI を開く
        }
        return InteractionResult.CONSUME;
    }
}
