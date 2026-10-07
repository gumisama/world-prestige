package com.example.worldprestige;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class PrestigeNetwork {
    /** パケットの形やアップグレードの数を変えたら上げる(クライアントとサーバーの版が違うと接続拒否される)。 */
    private static final String VERSION = "10." + Upgrade.values().length;
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(WorldPrestige.MOD_ID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);
    private static int id;
    private PrestigeNetwork() {}

    public static void init() {
        // クライアント -> サーバー: 購入要求
        CHANNEL.registerMessage(id++, BuyPacket.class,
                (m, b) -> b.writeUtf(m.upgrade()),
                b -> new BuyPacket(b.readUtf(32)),
                (m, c) -> {
                    ServerPlayer p = c.get().getSender();
                    if (p != null) c.get().enqueueWork(() -> handleBuy(p, m.upgrade()));
                    c.get().setPacketHandled(true);
                });

        // サーバー -> クライアント: 状態の通知(open=true なら GUI を開く)
        CHANNEL.registerMessage(id++, StatePacket.class,
                (m, b) -> {
                    b.writeInt(m.points());
                    b.writeInt(m.laps());
                    b.writeBoolean(m.open());
                    b.writeVarInt(m.levels().length);
                    for (int lv : m.levels()) b.writeVarInt(lv);
                    for (int i = 0; i < m.levels().length; i++) b.writeVarInt(i < m.active().length ? m.active()[i] : 0);
                },
                b -> {
                    int points = b.readInt();
                    int laps = b.readInt();
                    boolean open = b.readBoolean();
                    int n = b.readVarInt();
                    int[] levels = new int[n];
                    for (int i = 0; i < n; i++) levels[i] = b.readVarInt();
                    int[] active = new int[n];
                    for (int i = 0; i < n; i++) active[i] = b.readVarInt();
                    return new StatePacket(points, laps, open, levels, active);
                },
                (m, c) -> {
                    c.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                            Dist.CLIENT, () -> () -> PrestigeClient.receive(m)));
                    c.get().setPacketHandled(true);
                });

        // クライアント -> サーバー: 生成機の操作 (ACTION_*)
        CHANNEL.registerMessage(id++, GeneratorActionPacket.class,
                (m, b) -> {
                    b.writeBlockPos(m.pos());
                    b.writeVarInt(m.action());
                    b.writeLong(m.value());
                },
                b -> new GeneratorActionPacket(b.readBlockPos(), b.readVarInt(), b.readLong()),
                (m, c) -> {
                    ServerPlayer p = c.get().getSender();
                    if (p != null) c.get().enqueueWork(() -> handleGeneratorAction(p, m));
                    c.get().setPacketHandled(true);
                });

        // サーバー -> クライアント: 生成機の状態(open=true なら GUI を開く)
        CHANNEL.registerMessage(id++, GeneratorPacket.class,
                (m, b) -> {
                    b.writeBlockPos(m.pos());
                    b.writeLong(m.speed());
                    b.writeLong(m.progress());
                    b.writeLong(m.cost());
                    b.writeVarInt(m.stored());
                    b.writeLong(m.made());
                    b.writeBoolean(m.unlimited());
                    b.writeBoolean(m.open());
                    b.writeLong(m.measured());
                    b.writeBoolean(m.auto());
                    b.writeBoolean(m.autoUnlocked());
                },
                b -> new GeneratorPacket(b.readBlockPos(), b.readLong(), b.readLong(), b.readLong(),
                        b.readVarInt(), b.readLong(), b.readBoolean(), b.readBoolean(), b.readLong(), b.readBoolean(), b.readBoolean()),
                (m, c) -> {
                    c.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                            Dist.CLIENT, () -> () -> PrestigeClient.receiveGenerator(m)));
                    c.get().setPacketHandled(true);
                });

        // サーバー -> クライアント: リセット確認 GUI を開く
        CHANNEL.registerMessage(id++, ResetPromptPacket.class,
                (m, b) -> { },
                b -> new ResetPromptPacket(),
                (m, c) -> {
                    c.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                            Dist.CLIENT, () -> () -> PrestigeClient.receiveResetPrompt()));
                    c.get().setPacketHandled(true);
                });

        // クライアント -> サーバー: リセット確認 GUI の最終的な「はい」
        CHANNEL.registerMessage(id++, ResetExecutePacket.class,
                (m, b) -> { },
                b -> new ResetExecutePacket(),
                (m, c) -> {
                    ServerPlayer p = c.get().getSender();
                    if (p != null) c.get().enqueueWork(() -> PrestigeReset.confirmFromGui(p));
                    c.get().setPacketHandled(true);
                });
    }

    private static void handleBuy(ServerPlayer p, String upgradeId) {
        Upgrade u = Upgrade.byId(upgradeId);
        MinecraftServer server = p.getServer();
        if (server == null) return;
        if (u != null && SharedPrestige.purchase(u)) {
            server.getPlayerList().broadcastSystemMessage(Component.literal("ワールド強化: " + u.displayName()
                    + " Lv." + SharedPrestige.getLevel(u) + " (購入: " + p.getName().getString() + ") ※適用はワールドリセット後"), false);
        } else {
            p.sendSystemMessage(Component.literal("購入できません(ポイント不足、または購入済み)。"));
        }
        syncAll(server);   // ポイント・レベルは共通なので全員の GUI を更新
    }

    public static StatePacket state(boolean open) {
        return new StatePacket(SharedPrestige.getPoints(), SharedPrestige.getLaps(), open, SharedPrestige.getLevels(), SharedPrestige.getActiveLevels());
    }

    /** GUI を開かせる。 */
    public static void open(ServerPlayer p) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), state(true)); }

    /** 開かずに状態だけ送る(GUI が開いていればその場で更新される)。 */
    public static void sync(ServerPlayer p) { CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), state(false)); }

    /** 全員に状態を送る。共通ポイント・周回数が変わったときに使う。 */
    public static void syncAll(MinecraftServer server) {
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) sync(sp);
    }

    // ---------------- World Fragment Generator ----------------
    public static final int ACTION_SET_SPEED = 0, ACTION_TAKE = 1, ACTION_REFRESH = 2, ACTION_SET_UNLIMITED = 3, ACTION_CONVERT = 4, ACTION_SET_AUTO = 5;

    private static void handleGeneratorAction(ServerPlayer p, GeneratorActionPacket m) {
        BlockPos pos = m.pos();
        if (!p.level().isLoaded(pos)) return;
        if (p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) return;   // 遠すぎる操作は無視
        if (!(p.level().getBlockEntity(pos) instanceof FragmentGeneratorBlockEntity g)) return;
        switch (m.action()) {
            case ACTION_SET_SPEED -> g.setSpeed(m.value());
            case ACTION_TAKE -> g.giveTo(p);
            case ACTION_SET_UNLIMITED -> g.setUnlimited(m.value() != 0);
            case ACTION_CONVERT -> {
                MinecraftServer srv = p.getServer();
                long gained = srv == null ? 0L : g.convertToPoints(srv);
                if (gained > 0) {
                    p.displayClientMessage(Component.literal("World Fragment を変換: Prestige Point +" + gained
                            + " (合計 " + SharedPrestige.getPoints() + ")"), true);
                }
            }
            case ACTION_SET_AUTO -> g.setAutoConvert(m.value() != 0);
            default -> { }
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), g.snapshot(false));
    }

    /** 生成機の GUI を開かせる。 */
    public static void openGenerator(ServerPlayer p, FragmentGeneratorBlockEntity g) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), g.snapshot(true));
    }

    /** リセット確認 GUI を開かせる。 */
    public static void openResetConfirm(ServerPlayer p) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new ResetPromptPacket());
    }

    public record ResetPromptPacket() {}
    public record ResetExecutePacket() {}
    public record GeneratorActionPacket(BlockPos pos, int action, long value) {}
    public record GeneratorPacket(BlockPos pos, long speed, long progress, long cost, int stored, long made, boolean unlimited, boolean open, long measured, boolean auto, boolean autoUnlocked) {}

    public record BuyPacket(String upgrade) {}
    public record StatePacket(int points, int laps, boolean open, int[] levels, int[] active) {}
}
