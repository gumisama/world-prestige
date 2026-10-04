package com.example.worldprestige;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * ワールド全体のプレステージ情報(ポイント・周回数・アップグレードレベル)。
 * 全プレイヤー共通で、ワールドフォルダの worldprestige_shared.dat に保存する。
 * ワールドリセットの対象外なので、リセットしても残る。
 * 値が変わるたびにすぐ保存する(ファイルは小さいので問題ない)。
 */
public final class SharedPrestige {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "worldprestige_shared.dat";
    private static final String POINTS = "Points";
    private static final String LAPS = "Laps";
    private static final String FRAGMENTS_MADE = "FragmentsMade";

    private static int points;
    private static int laps;
    /** World Fragment がこれまでに作られた総数(ワールド共通・リセット後も残る)。必要電力の増加に使う。 */
    private static long fragmentsMade;
    /** 購入したレベル。ワールドリセットの時に activeLevels へ反映される。 */
    private static int[] levels = new int[Upgrade.values().length];
    /** 実際に効いているレベル(リセット後から適用)。 */
    private static int[] activeLevels = new int[Upgrade.values().length];
    private static final String ACTIVE_SUFFIX = "Active";
    private static Path file;

    private SharedPrestige() {}

    public static void register() { MinecraftForge.EVENT_BUS.register(SharedPrestige.class); }

    // ---- ポイント・周回数 ----
    public static int getPoints() { return points; }
    public static void setPoints(int value) { points = Math.max(0, value); save(); }
    public static int getLaps() { return laps; }
    public static void addLap() { laps = laps + 1; save(); }
    public static long getFragmentsMade() { return fragmentsMade; }
    public static void addFragmentsMade(long n) { fragmentsMade += Math.max(0L, n); save(); }

    // ---- アップグレード ----
    public static int getLevel(Upgrade u) { return levels[u.ordinal()]; }
    /** Upgrade.values() の順に並べたレベル配列(通信用)。 */
    public static int[] getLevels() { return levels.clone(); }
    /** 実際に効いているレベル(購入したレベルとは別。リセット後から適用)。 */
    public static int getActiveLevel(Upgrade u) { return activeLevels[u.ordinal()]; }
    public static int[] getActiveLevels() { return activeLevels.clone(); }

    /** 購入。ポイントが足りなければ false。レベルに上限は無い。 */
    public static boolean purchase(Upgrade u) {
        int level = levels[u.ordinal()];
        int cost = u.cost(level);
        if (points < cost) return false;
        points -= cost;
        levels[u.ordinal()] = level + 1;
        save();
        return true;
    }

    // ---- 読み込み・保存 ----
    @SubscribeEvent public static void onStarting(ServerStartingEvent e) {
        file = path(e.getServer());
        points = 0;
        laps = 0;
        fragmentsMade = 0;
        levels = new int[Upgrade.values().length];
        activeLevels = new int[Upgrade.values().length];
        try {
            if (Files.exists(file)) {
                CompoundTag tag = NbtIo.readCompressed(file.toFile());
                points = Math.max(0, tag.getInt(POINTS));
                laps = Math.max(0, tag.getInt(LAPS));
                fragmentsMade = Math.max(0L, tag.getLong(FRAGMENTS_MADE));
                for (Upgrade u : Upgrade.values()) {
                    levels[u.ordinal()] = Math.max(0, tag.getInt(u.nbtKey()));
                    // 旧版のデータ(Active キーが無い)は、購入済みのレベルをそのまま適用中として扱う
                    activeLevels[u.ordinal()] = tag.contains(u.nbtKey() + ACTIVE_SUFFIX)
                            ? Math.max(0, tag.getInt(u.nbtKey() + ACTIVE_SUFFIX)) : levels[u.ordinal()];
                }
            }
        } catch (IOException ex) {
            LOGGER.error("[WorldPrestige] 共通データを読めませんでした: {}", file, ex);
        }
    }

    @SubscribeEvent public static void onStopping(ServerStoppingEvent e) { save(); }

    /** リセット時(サーバー停止後)に、ファイルへ直接 周回数+1・ポイント加算 する。レベルはそのまま残る。 */
    static void applyResetToFile(Path root) throws IOException {
        Path f = root.resolve(FILE_NAME);
        CompoundTag tag = Files.exists(f) ? NbtIo.readCompressed(f.toFile()) : new CompoundTag();
        int newLaps = tag.getInt(LAPS) + 1;
        long newPoints = (long) Math.max(0, tag.getInt(POINTS)) + PrestigeReset.REWARD_POINTS;
        tag.putInt(LAPS, newLaps);
        tag.putInt(POINTS, (int) Math.min(Integer.MAX_VALUE, newPoints));
        // 購入済みの強化を、このリセットから適用する
        for (Upgrade u : Upgrade.values()) tag.putInt(u.nbtKey() + ACTIVE_SUFFIX, Math.max(0, tag.getInt(u.nbtKey())));
        writeAtomically(tag, f);
    }

    private static Path path(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().resolve(FILE_NAME);
    }

    private static void save() {
        if (file == null) return;
        CompoundTag tag = new CompoundTag();
        tag.putInt(POINTS, points);
        tag.putInt(LAPS, laps);
        tag.putLong(FRAGMENTS_MADE, fragmentsMade);
        for (Upgrade u : Upgrade.values()) {
            tag.putInt(u.nbtKey(), levels[u.ordinal()]);
            tag.putInt(u.nbtKey() + ACTIVE_SUFFIX, activeLevels[u.ordinal()]);
        }
        try {
            writeAtomically(tag, file);
        } catch (IOException ex) {
            LOGGER.error("[WorldPrestige] 共通データを保存できませんでした: {}", file, ex);
        }
    }

    private static void writeAtomically(CompoundTag tag, Path target) throws IOException {
        Path tmp = target.resolveSibling(FILE_NAME + ".tmp");
        NbtIo.writeCompressed(tag, tmp.toFile());
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
