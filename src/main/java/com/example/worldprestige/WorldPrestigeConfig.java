package com.example.worldprestige;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * config/worldprestige-common.toml。ゲームやサーバーの再起動で確実に反映される(動作中にファイルを書き換えても読み直されることが多い)。
 * サーバー(シングルプレイでは自分のワールド)側の設定が使われる。
 */
public final class WorldPrestigeConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.LongValue INITIAL_ENERGY_COST;
    public static final ForgeConfigSpec.DoubleValue COST_GROWTH_PERCENT;
    public static final ForgeConfigSpec.IntValue PULL_LOOPS;
    public static final ForgeConfigSpec.IntValue PULL_TIME_BUDGET_MS;

    /** 計算結果の上限(オーバーフロー防止)。 */
    private static final long MAX_COST = 4_000_000_000_000_000_000L;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("World Fragment Generator").push("fragment_generator");
        INITIAL_ENERGY_COST = b
                .comment("Energy (FE) needed to make the FIRST World Fragment.")
                .defineInRange("initialEnergyCost", 1_000_000L, 1L, 1_000_000_000_000_000L);
        COST_GROWTH_PERCENT = b
                .comment("How many percent the cost grows after EACH World Fragment is made (world-wide, all generators share it).",
                        "0 = the cost never grows. 1.0 = +1% per fragment (compound).")
                .defineInRange("costGrowthPercent", 1.0, 0.0, 1000.0);
        PULL_LOOPS = b
                .comment("Unlimited mode only: max number of extract calls per adjacent energy source per tick.",
                        "One call moves at most ~2.1 billion FE (the int limit of Forge Energy), so this is the multiplier that beats the limit.",
                        "The loop stops early when the source is empty. Raise it to pull more from huge or bottomless sources.")
                .defineInRange("pullLoopsPerTick", 20_000, 1, 10_000_000);
        PULL_TIME_BUDGET_MS = b
                .comment("Unlimited mode only: time limit (milliseconds) for ALL pulling done by one generator in one tick.",
                        "Protects the server from lag when a source never runs dry. Raise it to pull more; lower it if TPS drops.")
                .defineInRange("pullTimeBudgetMs", 5, 1, 1000);
        b.pop();
        SPEC = b.build();
    }

    private WorldPrestigeConfig() {}

    /** すでに made 個作られているときの、次の 1 個に必要な電力。cost = 初期値 x (1 + 増加率)^made。 */
    public static long cost(long made) {
        double c = INITIAL_ENERGY_COST.get() * Math.pow(1.0 + COST_GROWTH_PERCENT.get() / 100.0, (double) made);
        if (Double.isNaN(c) || c >= (double) MAX_COST) return MAX_COST;
        return Math.max(1L, (long) Math.ceil(c));
    }

    public record Batch(int count, long spent) {}

    /**
     * progress の電力で、あと何個作れるか(個数と消費電力)。
     * 1 個ずつ計算すると大量に作れるときに重いので、等比数列の和で一括計算する。
     * maxCount は機械の中の空き。
     */
    public static Batch affordable(long made, long progress, int maxCount) {
        if (maxCount <= 0 || progress <= 0) return new Batch(0, 0L);
        double r = 1.0 + COST_GROWTH_PERCENT.get() / 100.0;
        double c0 = INITIAL_ENERGY_COST.get() * Math.pow(r, (double) made);   // 次の 1 個の必要電力
        if (Double.isNaN(c0) || Double.isInfinite(c0) || c0 >= (double) MAX_COST || c0 > (double) progress) return new Batch(0, 0L);
        double k = r <= 1.0000000001
                ? Math.floor((double) progress / c0)
                : Math.floor(Math.log1p((double) progress * (r - 1.0) / c0) / Math.log(r));
        int count = (int) Math.max(0.0, Math.min((double) maxCount, k));
        while (count > 0 && totalCost(c0, r, count) > (double) progress) count--;   // 丸め誤差の補正
        if (count == 0) return new Batch(0, 0L);
        long spent = (long) Math.min((double) progress, Math.ceil(totalCost(c0, r, count)));
        return new Batch(count, Math.max(1L, spent));
    }

    /** c0 から始まり毎回 r 倍になる k 個ぶんの合計。 */
    private static double totalCost(double c0, double r, int k) {
        if (r <= 1.0000000001) return c0 * k;
        return c0 * Math.expm1(k * Math.log(r)) / (r - 1.0);
    }
}
