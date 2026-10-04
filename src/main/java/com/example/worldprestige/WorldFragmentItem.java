package com.example.worldprestige;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 右クリックで使うと、共通の Prestige Point が増える。スニーク+右クリックで持っている分を全部使う。 */
public class WorldFragmentItem extends Item {
    /** 1 個使うと増えるポイント。 */
    public static final int POINTS_PER_FRAGMENT = 1;

    public WorldFragmentItem(Properties properties) { super(properties); }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(stack);
        if (!(player instanceof ServerPlayer sp)) return InteractionResultHolder.pass(stack);
        MinecraftServer server = sp.getServer();
        if (server == null) return InteractionResultHolder.pass(stack);

        int used = player.isShiftKeyDown() ? stack.getCount() : 1;
        long gained = (long) used * POINTS_PER_FRAGMENT;
        long total = Math.min(Integer.MAX_VALUE, (long) SharedPrestige.getPoints() + gained);
        SharedPrestige.setPoints((int) total);
        if (!player.getAbilities().instabuild) stack.shrink(used);
        PrestigeNetwork.syncAll(server);   // 共通ポイントなので全員の GUI を更新

        sp.displayClientMessage(Component.literal("World Fragment x" + used + " を使用: Prestige Point +" + gained
                + " (合計 " + total + ")"), true);
        level.playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.2f);
        return InteractionResultHolder.consume(stack);
    }
}
