package com.example.worldprestige;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.EnumMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ワールド強化の効果: 機械の加速。効くのは「適用中」のレベル(購入したレベルは次のワールドリセットで適用される)。
 *
 * 仕組み: 対象の BlockEntity(かまど・Mekanism の機械・マルチブロック)の tick 処理を、
 * 1 tick あたり追加で何回か呼ぶ。追加回数 = perLevel x レベル (小数部は確率で切り上げ)。
 * 例: Lv.10 (perLevel 0.10) なら追加 1 回 = 2 倍速。燃料や電力も加速したぶん多く消費する。
 *
 * 対象は「プレイヤーの近くのロード済みチャンク」にある物だけ (SCAN_RADIUS)。
 * Mekanism は依存に入れていない。クラス名(TileEntityMekanism の継承など)で判別するので、Mekanism が無くても動き、アドオンも自動で対象になる。
 */
public final class PrestigeEffects {
    private static final Logger LOGGER = LogUtils.getLogger();

    // ================= 設定(ここを書き換えて調整) =================
    /** 「Mekanism機械」「Mekマルチブロック」として扱う MOD の名前空間。他の MOD を足したいときはここ。 */
    private static final Set<String> MACHINE_MODS = Set.of("mekanism", "mekanismgenerators", "mekanismadditions");
    /** プレイヤーの周囲何チャンクまでを加速の対象にするか。 */
    private static final int SCAN_RADIUS = 6;
    /** 対象の機械を探し直す間隔(tick)。 */
    private static final int SCAN_INTERVAL = 20;
    /** 1 tick あたりの追加ティック数の上限(暴走・サーバー負荷の防止)。9 なら最大 10 倍速。 */
    private static final double MAX_EXTRA = 9.0;
    // ================================================================

    private static final int KIND_NONE = 0, KIND_MACHINE = 1, KIND_MULTIBLOCK = 2;

    private record Target(BlockEntity be, Upgrade upgrade) {}

    private static final Map<ResourceKey<Level>, List<Target>> TARGETS = new HashMap<>();
    private static final Map<Class<?>, Integer> KINDS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<Method>> STRUCTURE_METHODS = new ConcurrentHashMap<>();
    /** tick 中に例外を出したクラス。以後は加速しない(サーバーを落とさないため)。 */
    private static final Set<Class<?>> BAD = ConcurrentHashMap.newKeySet();

    // ---- 診断用: クラスごとに「認識されたか・加速できたか・失敗理由」を記録する (/prestige debug とログに出る) ----
    private static final class Stat {
        final Upgrade upgrade;      // 対象外なら null
        String status = "未実行";
        long ticks;                 // 追加 tick を実行できた回数
        Stat(Upgrade upgrade) { this.upgrade = upgrade; }
    }
    private static final Map<String, Stat> STATS = new ConcurrentHashMap<>();

    private static Stat stat(Class<?> c, Upgrade u) { return STATS.computeIfAbsent(c.getName(), n -> new Stat(u)); }

    private static void note(Class<?> c, Stat st, String status) {
        if (status.equals(st.status)) return;
        st.status = status;
        LOGGER.info("[WorldPrestige] {} ({}): {}", c.getName(), st.upgrade == null ? "対象外" : st.upgrade.displayName(), status);
    }

    private PrestigeEffects() {}

    public static void register() { MinecraftForge.EVENT_BUS.register(PrestigeEffects.class); }

    @SubscribeEvent public static void onStopped(ServerStoppedEvent e) {
        TARGETS.clear();
    }

    @SubscribeEvent public static void onLevelTick(TickEvent.LevelTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.level instanceof ServerLevel level)) return;
        if (level.getGameTime() % SCAN_INTERVAL == 0) rescan(level);
        List<Target> list = TARGETS.get(level.dimension());
        if (list == null || list.isEmpty()) return;
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());   // マルチブロックの重複 tick 防止用
        for (Target t : list) boost(level, t, seen);
    }

    // ---------------- 対象の探索 ----------------

    private static void rescan(ServerLevel level) {
        boolean any = false;
        for (Upgrade u : Upgrade.values()) if (u.perLevel() > 0 && SharedPrestige.getActiveLevel(u) > 0) any = true;
        if (!any) {
            TARGETS.remove(level.dimension());
            return;
        }
        Set<BlockEntity> found = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Target> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            ChunkPos cp = p.chunkPosition();
            for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
                for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x + dx, cp.z + dz);
                    if (chunk == null) continue;
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        if (be.isRemoved() || BAD.contains(be.getClass()) || !found.add(be)) continue;
                        Upgrade up = classify(be);
                        if (up != null) out.add(new Target(be, up));
                    }
                }
            }
        }
        TARGETS.put(level.dimension(), out);
    }

    /** どの強化の対象か。対象外なら null。 */
    private static Upgrade classify(BlockEntity be) {
        if (be instanceof AbstractFurnaceBlockEntity) return Upgrade.FURNACE;   // かまど・溶鉱炉・燻製器(とその継承)
        Integer kind = KINDS.get(be.getClass());
        if (kind == null) {
            kind = kindFor(be);
            KINDS.put(be.getClass(), kind);
        }
        return switch (kind) {
            case KIND_MACHINE -> Upgrade.MACHINE;
            case KIND_MULTIBLOCK -> Upgrade.MULTIBLOCK;
            default -> extraUpgrade(be);
        };
    }

    /** クラスごとに 1 回だけ種類を決める。Mekanism と無関係なクラスは何も記録せず対象外にする。 */
    private static int kindFor(BlockEntity be) {
        Class<?> c = be.getClass();
        ResourceLocation key = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType());
        boolean nsMatch = key != null && MACHINE_MODS.contains(key.getNamespace());
        // Mekanism のアドオン(Evolved Mekanism, Mekanism Extras など)は TileEntityMekanism を継承しているので、名前空間に関係なく拾える
        if (!nsMatch && !is(c, "TileEntityMekanism")) return KIND_NONE;
        int k = kindOf(c);
        Stat st = stat(c, k == KIND_MACHINE ? Upgrade.MACHINE : k == KIND_MULTIBLOCK ? Upgrade.MULTIBLOCK : null);
        note(c, st, k == KIND_NONE ? "除外(ケーブル・パイプ・マルチブロックの内部パーツ)" : "対象として認識");
        return k;
    }

    /** Mekanism のクラスを import せず、クラス名(継承・実装)で種類を判定する。 */
    private static int kindOf(Class<?> c) {
        if (is(c, "TileEntityTransmitter")) return KIND_NONE;                               // ケーブル・パイプ類
        if (is(c, "TileEntityInternalMultiblock") || is(c, "IInternalMultiblock")) return KIND_NONE;   // 構造の内部パーツ(ガラス・加熱要素等。本体の tick で動く)
        if (is(c, "TileEntityMultiblock") || is(c, "IMultiblock")) return KIND_MULTIBLOCK;  // マルチブロックの構成ブロック
        return KIND_MACHINE;
    }

    private static final Map<BlockEntityType<?>, Optional<Upgrade>> EXTRA = new ConcurrentHashMap<>();

    /** Upgrade.java で matcher(対象の判定)を指定した強化。Upgrade に 1 行足すだけで、その機械が加速の対象になる。 */
    private static Upgrade extraUpgrade(BlockEntity be) {
        return EXTRA.computeIfAbsent(be.getType(), t -> {
            for (Upgrade u : Upgrade.values()) {
                if (u.matcher() != null && u.matcher().test(be)) return Optional.of(u);
            }
            return Optional.<Upgrade>empty();
        }).orElse(null);
    }

    private static boolean is(Class<?> c, String simpleName) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (k.getSimpleName().equals(simpleName)) return true;
            for (Class<?> i : k.getInterfaces()) if (is(i, simpleName)) return true;
        }
        return false;
    }

    // ---------------- 加速 ----------------

    private static void boost(ServerLevel level, Target t, Set<Object> seen) {
        BlockEntity be = t.be();
        if (be.isRemoved() || be.getLevel() != level) return;
        int lv = SharedPrestige.getActiveLevel(t.upgrade());
        if (lv <= 0) return;
        double mult = Math.min(MAX_EXTRA, lv * t.upgrade().perLevel());
        int extra = (int) mult;
        if (level.random.nextDouble() < mult - extra) extra++;
        if (extra <= 0) return;
        Class<?> cls = be.getClass();
        Stat st = stat(cls, t.upgrade());
        try {
            if (t.upgrade() == Upgrade.MULTIBLOCK) {
                // 構成ブロックが何十個もあるので、構造 1 つにつき 1 回だけ処理する
                Object structure = structureOf(be);
                if (structure == null) {
                    note(cls, st, "getMultiblock() を取得できず加速せず");
                    return;
                }
                if (!seen.add(structure)) return;
                if (!isFormed(structure)) return;
                // 構成ブロック 1 つの tick を呼ぶだけだと「処理を担当するブロック」に当たらないことがあるので、
                // 構造データ本体の tick(Level) を直接追加で呼ぶ(ボイラー・蒸発プラント等の処理はここで行われる)
                Method structureTick = structureTick(structure.getClass());
                if (structureTick != null) {
                    for (int i = 0; i < extra; i++) invokeTick(structureTick, structure, level);
                    if (st.ticks == 0) note(cls, st, "加速OK(構造の tick(Level) を直接呼ぶ)");
                    st.ticks += extra;
                    return;
                }
                // tick(Level) が見つからなければ、下のループで構成ブロックの tick を追加で呼ぶ
            }
            for (int i = 0; i < extra && !be.isRemoved(); i++) {
                int r = tickOnce(level, be);
                if (r == TICK_OK) {
                    if (st.ticks++ == 0) note(cls, st, "加速OK");
                } else {
                    BAD.add(cls);
                    note(cls, st, r == TICK_NO_TICKER ? "getTicker() が null (サーバー用の tick が無い)" : "EntityBlock ではないブロック");
                    return;
                }
            }
        } catch (RuntimeException ex) {
            BAD.add(cls);
            note(cls, st, "例外: " + ex);
            LOGGER.error("[WorldPrestige] {} の追加 tick で例外が出たため、このクラスの加速を止めます。", cls.getName(), ex);
        }
    }

    private static final int TICK_NOT_ENTITY_BLOCK = 0, TICK_NO_TICKER = 1, TICK_OK = 2;

    /** そのブロックが普段呼ばれている tick 処理を、もう 1 回呼ぶ。結果は TICK_* で返す。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int tickOnce(Level level, BlockEntity be) {
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof EntityBlock block)) return TICK_NOT_ENTITY_BLOCK;
        BlockEntityTicker ticker = block.getTicker(level, state, (BlockEntityType) be.getType());
        if (ticker == null) return TICK_NO_TICKER;
        ticker.tick(level, be.getBlockPos(), state, be);
        return TICK_OK;
    }

    private static final Map<Class<?>, Optional<Method>> STRUCTURE_TICKS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<Method>> STRUCTURE_FORMED = new ConcurrentHashMap<>();

    /** 構造データの public tick(Level)。無ければ null。 */
    private static Method structureTick(Class<?> c) {
        return STRUCTURE_TICKS.computeIfAbsent(c, k -> {
            try {
                return Optional.of(k.getMethod("tick", Level.class));
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return Optional.<Method>empty();
            }
        }).orElse(null);
    }

    /** 構造が完成しているか(isFormed())。取れなければ true 扱い。 */
    private static boolean isFormed(Object structure) {
        Method m = STRUCTURE_FORMED.computeIfAbsent(structure.getClass(), k -> {
            try {
                return Optional.of(k.getMethod("isFormed"));
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return Optional.<Method>empty();
            }
        }).orElse(null);
        if (m == null) return true;
        try {
            return !Boolean.FALSE.equals(m.invoke(structure));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return true;
        }
    }

    private static void invokeTick(Method m, Object structure, Level level) {
        try {
            m.invoke(structure, level);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            throw new RuntimeException(ex.getCause() != null ? ex.getCause() : ex);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    /** マルチブロックの構造データ(getMultiblock())を取り出す。取れなければ null (= 加速しない)。 */
    private static Object structureOf(BlockEntity be) {
        Method m = STRUCTURE_METHODS.computeIfAbsent(be.getClass(), c -> {
            try {
                return Optional.of(c.getMethod("getMultiblock"));
            } catch (ReflectiveOperationException | RuntimeException ex) {
                return Optional.<Method>empty();
            }
        }).orElse(null);
        if (m == null) return null;
        try {
            return m.invoke(be);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    /** /prestige debug 用の診断結果。ログにも全行出す。 */
    public static List<String> debugLines(ServerLevel level) {
        List<String> out = new ArrayList<>();
        StringBuilder lv = new StringBuilder("強化レベル:");
        for (Upgrade u : Upgrade.values()) lv.append(' ').append(u.displayName()).append(" Lv.").append(SharedPrestige.getLevel(u))
                .append("(適用").append(SharedPrestige.getActiveLevel(u)).append(")");
        out.add(lv.toString());
        Map<Upgrade, Integer> n = new EnumMap<>(Upgrade.class);
        for (Target t : TARGETS.getOrDefault(level.dimension(), List.of())) n.merge(t.upgrade(), 1, Integer::sum);
        out.add("この階層の加速対象(プレイヤー周囲): " + n);
        for (Map.Entry<String, Stat> en : new TreeMap<>(STATS).entrySet()) {
            Stat st = en.getValue();
            out.add(en.getKey() + " [" + (st.upgrade == null ? "対象外" : st.upgrade.displayName()) + "] "
                    + st.status + " / 追加tick " + st.ticks);
        }
        for (String line : out) LOGGER.info("[WorldPrestige][debug] {}", line);
        return out;
    }
}
