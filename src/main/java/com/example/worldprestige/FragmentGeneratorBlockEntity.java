package com.example.worldprestige;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.IItemHandler;

/**
 * FE を受け取り、受け取った電力を毎 tick 無条件に「消費」して progress に加える。
 * progress が必要電力(WorldPrestigeConfig.cost)に達するたびに World Fragment を 1 個作る(余りは持ち越し)。
 * つまり生成に使われるのは「ブロックに溜まっていた電力」ではなく「消費した電力」。
 * 1 tick に受け取れる量(入力速度)は、ブロックを右クリックした GUI で自由に設定できる。
 * 必要電力は世界全体で作られた個数(SharedPrestige.getFragmentsMade)で増えていく。
 * 機械の中が満杯のときだけ、電力を受け取らない(ケーブル側の電力を無駄にしないため)。
 *
 * 「入力上限なし」モード(GUI で切り替え): 入力速度の設定を無視して、受け取れるだけ受け取る。
 * さらに、隣接する FE の供給元(エネルギーキューブ・バッテリー等)から、こちらから吸い出す。
 * Forge Energy は 1 回の受け渡しが int(約 21 億)までなので、吸い出しを 1 tick に何度も繰り返してその上限を超える。
 * 繰り返し回数と時間の上限は config (pullLoopsPerTick / pullTimeBudgetMs)。
 * (ケーブル経由の場合、ケーブル側の転送量の上限と FE 変換の都合は変えられない)
 */
public class FragmentGeneratorBlockEntity extends BlockEntity {
    // ================= 設定(ここを書き換えて調整) =================
    /** 設置直後の入力速度(FE/t)。GUI で変えられる。 */
    public static final int DEFAULT_SPEED = 50_000;
    /** 機械の中に溜められる World Fragment の数。満杯になると電力を受け取らなくなる。 */
    public static final int MAX_STORED_FRAGMENTS = 1_000_000;
    // 初期の必要電力・増加率・吸い出し回数/時間の上限は config/worldprestige-common.toml (WorldPrestigeConfig)
    // ================================================================

    private long speed = DEFAULT_SPEED;  // 1 tick に受け取れる上限 (FE/t)。long なので int の上限(約 21 億)を超えて設定できる
    private long pending;                // 今の tick に受け取った電力(次の serverTick で消費される)
    private long progress;               // 消費した電力のうち、まだ World Fragment になっていない分
    private int fragments;               // 機械の中の World Fragment
    private long windowSum;              // 実測入力の計算用(20 tick の合計)
    private int windowTicks;
    private long measured;               // 直近 20 tick の平均入力 (FE/t)。GUI に表示するだけ
    private boolean unlimited;           // true: 入力速度の上限を無視する(受け取れるだけ受け取り、隣接する供給元からも吸い出す)
    private boolean autoConvert;   // true: 作ったフラグメントを、すぐ Prestige Point に変える
    private final IEnergyStorage energy = new IEnergyStorage() {
        @Override public int receiveEnergy(int max, boolean simulate) {
            if (fragments >= MAX_STORED_FRAGMENTS) return 0;
            if (unlimited) {
                int all = Math.max(0, max);
                if (all > 0 && !simulate) pending = addSaturated(pending, all);
                return all;
            }
            long room = speed - pending;
            int r = (int) Math.max(0L, Math.min((long) max, room));
            if (r > 0 && !simulate) pending += r;
            return r;
        }
        @Override public int extractEnergy(int max, boolean simulate) { return 0; }
        @Override public int getEnergyStored() { return (int) Math.min((long) Integer.MAX_VALUE, pending); }
        @Override public int getMaxEnergyStored() { return unlimited ? Integer.MAX_VALUE : (int) Math.min((long) Integer.MAX_VALUE, speed); }
        @Override public boolean canExtract() { return false; }
        @Override public boolean canReceive() { return (unlimited || speed > 0) && fragments < MAX_STORED_FRAGMENTS; }
    };

    private final IItemHandler output = new IItemHandler() {
        @Override public int getSlots() { return 1; }
        @Override public ItemStack getStackInSlot(int slot) {
            return fragments > 0 ? new ItemStack(ModRegistry.WORLD_FRAGMENT.get(), Math.min(fragments, 64)) : ItemStack.EMPTY;
        }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            int n = Math.min(Math.min(amount, fragments), 64);
            if (n <= 0) return ItemStack.EMPTY;
            if (!simulate) {
                fragments -= n;
                setChanged();
            }
            return new ItemStack(ModRegistry.WORLD_FRAGMENT.get(), n);
        }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
    };

    private final LazyOptional<IEnergyStorage> energyCap = LazyOptional.of(() -> energy);
    private final LazyOptional<IItemHandler> itemCap = LazyOptional.of(() -> output);

    public FragmentGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.FRAGMENT_GENERATOR_BE.get(), pos, state);
    }

    public void serverTick() {
        // 0. 無制限モード: 隣接する供給元から電力を吸い出す
        if (unlimited && fragments < MAX_STORED_FRAGMENTS) pullFromNeighbors();
        // 実測入力(GUI 表示用)
        windowSum = addSaturated(windowSum, pending);
        if (++windowTicks >= 20) {
            measured = windowSum / windowTicks;
            windowSum = 0;
            windowTicks = 0;
        }
        // 1. 受け取った電力を無条件に消費して progress にする
        if (pending > 0) {
            progress = addSaturated(progress, pending);
            pending = 0;
            setChanged();
        }
        // 2. progress が必要電力に届いた分だけ World Fragment を作る(必要電力は 1 個ごとに増える)
        // (1 tick に何個でも作れる。個数は等比数列の和で一括計算する)
        int room = MAX_STORED_FRAGMENTS - fragments;
        if (room > 0 && progress > 0) {
            WorldPrestigeConfig.Batch batch = WorldPrestigeConfig.affordable(SharedPrestige.getFragmentsMade(), progress, room);
            if (batch.count() > 0) {
                progress -= batch.spent();
                fragments += batch.count();
                SharedPrestige.addFragmentsMade(batch.count());
                setChanged();
            }
        }
        if (autoConvert && autoUnlocked() && fragments > 0 && level != null && level.getServer() != null) convertToPoints(level.getServer());
    }

    // ---- GUI から呼ばれる ----

    /** オーバーフローしたら Long.MAX_VALUE で止める。 */
    private static long addSaturated(long a, long b) {
        long r = a + b;
        return ((a ^ r) & (b ^ r)) < 0 ? Long.MAX_VALUE : r;
    }

    /**
     * 隣接する 6 方向の供給元から、extractEnergy を繰り返して電力を吸い出す。
     * 1 回で最大 int(約 21 億)なので、供給元が空になるか、回数/時間の上限(config)に達するまで繰り返す。
     */
    private void pullFromNeighbors() {
        if (level == null) return;
        final int maxLoops = WorldPrestigeConfig.PULL_LOOPS.get();
        final long deadline = System.nanoTime() + WorldPrestigeConfig.PULL_TIME_BUDGET_MS.get() * 1_000_000L;
        for (Direction d : Direction.values()) {
            BlockEntity neighbor = level.getBlockEntity(worldPosition.relative(d));
            if (neighbor == null || neighbor instanceof FragmentGeneratorBlockEntity) continue;
            neighbor.getCapability(ForgeCapabilities.ENERGY, d.getOpposite()).ifPresent(source -> {
                if (!source.canExtract()) return;
                for (int i = 0; i < maxLoops; i++) {
                    int got = source.extractEnergy(Integer.MAX_VALUE, false);
                    if (got <= 0) break;
                    pending = addSaturated(pending, got);
                    if ((i & 63) == 63 && System.nanoTime() > deadline) break;   // 底なしの供給元でサーバーが重くならないように
                }
            });
            if (System.nanoTime() > deadline) break;
        }
    }

    public void setUnlimited(boolean value) {
        unlimited = value;
        setChanged();
    }
    private static boolean autoUnlocked() { return SharedPrestige.getActiveLevel(Upgrade.AUTO_CONVERT) > 0; }

    public void setAutoConvert(boolean value) {
        autoConvert = value && autoUnlocked();
        setChanged();
    }

    public void setSpeed(long value) {
        speed = Math.max(0L, value);
        setChanged();
    }

    /** 溜まっている World Fragment をプレイヤーに渡す。持ち物に入りきらない分は機械の中に残る。 */
    public void giveTo(Player player) {
        while (fragments > 0) {
            int n = Math.min(64, fragments);
            ItemStack stack = new ItemStack(ModRegistry.WORLD_FRAGMENT.get(), n);
            player.getInventory().add(stack);
            int given = n - stack.getCount();   // add() は入りきらなかった分を stack に残す
            if (given <= 0) break;              // 持ち物がいっぱい
            fragments -= given;
            if (given < n) break;
        }
        setChanged();
    }

    /** 機械の中の World Fragment を、アイテムにせず直接 Prestige Point に変える。増えたポイントを返す。 */
    public long convertToPoints(MinecraftServer server) {
        if (fragments <= 0) return 0L;
        long gained = (long) fragments * WorldFragmentItem.POINTS_PER_FRAGMENT;
        fragments = 0;
        setChanged();
        SharedPrestige.setPoints((int) Math.min((long) Integer.MAX_VALUE, (long) SharedPrestige.getPoints() + gained));
        PrestigeNetwork.syncAll(server);
        return gained;
    }

    public PrestigeNetwork.GeneratorPacket snapshot(boolean open) {
        long made = SharedPrestige.getFragmentsMade();
        return new PrestigeNetwork.GeneratorPacket(worldPosition, speed, progress,
                WorldPrestigeConfig.cost(made), fragments, made, unlimited, open, measured, autoConvert, autoUnlocked());
    }

    public void dropContents(Level level, BlockPos pos) {
        if (level.isClientSide) return;
        int stacks = 0;
        while (fragments > 0 && stacks < 64) {   // 落とすのは最大 64 スタック
            int n = Math.min(64, fragments);
            Block.popResource(level, pos, new ItemStack(ModRegistry.WORLD_FRAGMENT.get(), n));
            fragments -= n;
            stacks++;
        }
        // 落としきれない分は無駄にせず、Prestige Point にする
        if (fragments > 0 && level.getServer() != null) convertToPoints(level.getServer());
        fragments = 0;
    }

    @Override public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
        if (cap == ForgeCapabilities.ENERGY) return energyCap.cast();
        if (cap == ForgeCapabilities.ITEM_HANDLER) return itemCap.cast();
        return super.getCapability(cap, side);
    }

    @Override public void invalidateCaps() {
        super.invalidateCaps();
        energyCap.invalidate();
        itemCap.invalidate();
    }

    @Override protected void saveAdditional(CompoundTag tag) {
        tag.putBoolean("AutoConvert", autoConvert);
        super.saveAdditional(tag);
        tag.putLong("Speed", speed);
        tag.putLong("Progress", progress);
        tag.putInt("Fragments", fragments);
        tag.putBoolean("Unlimited", unlimited);
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        speed = tag.contains("Speed") ? Math.max(0L, tag.getLong("Speed")) : DEFAULT_SPEED;
        progress = Math.max(0L, tag.getLong("Progress"));
        unlimited = tag.getBoolean("Unlimited");
        fragments = Math.max(0, Math.min(MAX_STORED_FRAGMENTS, tag.getInt("Fragments")));
        autoConvert = tag.getBoolean("AutoConvert");
    }
}
