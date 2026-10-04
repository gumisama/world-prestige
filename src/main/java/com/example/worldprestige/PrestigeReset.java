package com.example.worldprestige;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * ワールドリセット。
 *
 * 流れ:
 *  1. /prestige reset -> 警告を表示 / /prestige reset confirm -> サーバーを停止
 *  2. サーバーが完全に止まった後(ServerStoppedEvent)に、ワールドフォルダの中身を入れ替える
 *     (動いている最中にワールドのファイルを消すのは危険なので、必ず停止後に行う)
 *  3. 次にワールドを開く/サーバーを起動すると、新しいワールドが生成される
 */
public final class PrestigeReset {
    private static final Logger LOGGER = LogUtils.getLogger();

    // ================= 設定(ここを書き換えて調整) =================
    /** リセット1回あたりに共通ポイントへ加算する量。0 で無し。 */
    public static final int REWARD_POINTS = 1;
    /** true: リセットのたびにシード値を新しくする。false: 同じ地形になる。 */
    private static final boolean NEW_SEED = true;
    /** true: 古いワールドを prestige_archive/ に移動して残す。false: 完全に削除する。 */
    private static final boolean KEEP_ARCHIVE = true;
    /** /prestige reset で確認 GUI を開いてから、最後の「はい」を押すまでの有効時間(ミリ秒)。 */
    private static final long CONFIRM_MILLIS = 120_000L;
    /** リセット時に消す(退避する)ワールド内のフォルダ。ここに無いもの(stats, datapacks 等)は残る。 */
    private static final List<String> TARGETS = List.of(
            "region", "entities", "poi",          // オーバーワールドの地形・エンティティ・POI
            "DIM-1", "DIM1", "dimensions",        // ネザー・エンド・他MODのディメンション
            "data",                               // マップ、スコアボード等
            "playerdata", "advancements");        // 持ち物・位置・進捗
    // ================================================================

    private static final String ARCHIVE_DIR = "prestige_archive";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final Map<UUID, Long> confirmations = new HashMap<>();
    private static volatile boolean resetPending;
    private static volatile Path worldRoot;

    private PrestigeReset() {}

    public static void register() { MinecraftForge.EVENT_BUS.register(PrestigeReset.class); }

    // ---------------- コマンド側 ----------------

    /** リセットを実行できる人の条件。条件(ドラゴン討伐など)を足したいときはここ。 */
    public static boolean canUse(CommandSourceStack source) {
        if (source.hasPermission(2)) return true;
        // シングルプレイのホスト本人は、チートOFFでも実行できる
        return source.getEntity() instanceof ServerPlayer sp
                && source.getServer().isSingleplayerOwner(sp.getGameProfile());
    }

    /** /prestige reset : 確認 GUI(2 段階)を開かせる。実行は、GUI で「はい」を 2 回押したとき(confirmFromGui)。 */
    public static int request(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        confirmations.put(player.getUUID(), System.currentTimeMillis() + CONFIRM_MILLIS);
        PrestigeNetwork.openResetConfirm(player);
        return 1;
    }

    /** 確認 GUI の最後の「はい」が押されたとき(サーバー側で呼ばれる)。 */
    public static void confirmFromGui(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || !canUse(player.createCommandSourceStack())) return;
        Long expires = confirmations.remove(player.getUUID());
        if (expires == null || expires < System.currentTimeMillis()) {
            player.sendSystemMessage(Component.literal("確認の有効期限が切れました。もう一度 /prestige reset を実行してください。"));
            return;
        }
        resetPending = true;
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("ワールドをリセットするため停止します。停止後にもう一度ワールドを開いてください。"), false);
        server.halt(false);
    }

    // ---------------- サーバーのライフサイクル ----------------

    @SubscribeEvent public static void onStarting(ServerStartingEvent e) {
        // シングルプレイではJVMが生きたままワールドを開き直すので、状態を必ず初期化する
        resetPending = false;
        worldRoot = null;
        confirmations.clear();
    }

    @SubscribeEvent public static void onStopping(ServerStoppingEvent e) {
        worldRoot = e.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    }

    @SubscribeEvent public static void onStopped(ServerStoppedEvent e) {
        if (!resetPending) return;
        resetPending = false;
        Path root = worldRoot;
        if (root == null) {
            LOGGER.error("[WorldPrestige] ワールドの場所が不明なためリセットを中止しました。");
            return;
        }
        try {
            performReset(root);
            LOGGER.info("[WorldPrestige] ワールドのリセットが完了しました: {}", root);
        } catch (Exception ex) {
            LOGGER.error("[WorldPrestige] ワールドのリセットに失敗しました。", ex);
        }
    }

    // ---------------- リセット本体(サーバー停止後に実行) ----------------

    private static void performReset(Path root) throws IOException {
        // 1. 古いワールドを退避(または削除)
        Path levelDat = root.resolve("level.dat");
        Path archive = root.resolve(ARCHIVE_DIR).resolve(LocalDateTime.now().format(STAMP));
        if (KEEP_ARCHIVE) {
            Files.createDirectories(archive);
            if (Files.exists(levelDat)) Files.copy(levelDat, archive.resolve("level.dat"));
        }
        for (String name : TARGETS) {
            Path src = root.resolve(name);
            if (!Files.exists(src)) continue;
            try {
                if (KEEP_ARCHIVE) Files.move(src, archive.resolve(name));
                else deleteRecursively(src);
            } catch (IOException ex) {
                LOGGER.error("[WorldPrestige] {} を処理できませんでした。", src, ex);
            }
        }

        // 2. level.dat を新しいワールド用に書き換える
        if (Files.exists(levelDat)) {
            CompoundTag top = NbtIo.readCompressed(levelDat.toFile());
            CompoundTag data = top.getCompound("Data");
            data.remove("Player");              // シングルプレイのホストの位置・持ち物
            data.remove("DragonFight");         // エンドラ討伐済みの記録(残すと次の周でドラゴンが出ない)
            data.putBoolean("initialized", false);  // スポーン地点を作り直させる
            data.putLong("Time", 0L);
            data.putLong("DayTime", 0L);
            data.putBoolean("raining", false);
            data.putBoolean("thundering", false);
            if (NEW_SEED) {
                CompoundTag gen = data.getCompound("WorldGenSettings");
                if (!gen.isEmpty()) gen.putLong("seed", new Random().nextLong());
            }
            NbtIo.writeCompressed(top, levelDat.toFile());
        }

        // 3. 共通データ(ポイント・周回数・アップグレード)は残したまま、報酬だけ加える
        SharedPrestige.applyResetToFile(root);
    }

    // ---------------- 小物 ----------------

    private static void deleteRecursively(Path path) throws IOException {
        try (var walk = Files.walk(path)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }
}
