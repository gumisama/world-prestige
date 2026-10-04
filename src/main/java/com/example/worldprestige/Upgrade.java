package com.example.worldprestige;

/**
 * アップグレードの定義表。数値を変えたいときはまずここを見る。
 * 何回でも購入でき、購入するたびにレベルが 1 上がる。
 * 効果はすべて「機械の動作速度」。仕組みは PrestigeEffects を参照。
 */
public enum Upgrade {
    //          id           NBTキー              表示名              効果表示      基本価格 上昇幅 1Lvあたりの加速量
    FURNACE    ("furnace",    "UpgradeFurnace",    "かまど類",          "速度+10%",   5,       2,     0.10),
    MACHINE    ("machine",    "UpgradeMachine",    "Mekanism機械",      "速度+10%",   10,      4,     0.10),
    MULTIBLOCK ("multiblock", "UpgradeMultiblock", "Mekマルチブロック", "速度+10%",   20,      8,     0.10);

    private final String id;
    private final String nbtKey;
    private final String displayName;
    private final String effectText;
    private final int baseCost;
    private final int costStep;
    private final double perLevel;

    Upgrade(String id, String nbtKey, String displayName, String effectText,
            int baseCost, int costStep, double perLevel) {
        this.id = id;
        this.nbtKey = nbtKey;
        this.displayName = displayName;
        this.effectText = effectText;
        this.baseCost = baseCost;
        this.costStep = costStep;
        this.perLevel = perLevel;
    }

    public String id() { return id; }
    public String nbtKey() { return nbtKey; }
    public String displayName() { return displayName; }
    public String effectText() { return effectText; }
    /** 1 レベルあたりの加速量。0.10 なら Lv.1 で +10%、Lv.10 で 2 倍速。 */
    public double perLevel() { return perLevel; }

    /** 現在のレベル level から次のレベルへ上げるのに必要なポイント。 */
    public int cost(int level) {
        long c = (long) baseCost + (long) costStep * level;   // 線形: 基本価格 + 上昇幅 x レベル
        return (int) Math.min(Integer.MAX_VALUE, c);
    }

    public static Upgrade byId(String id) {
        for (Upgrade u : values()) if (u.id.equals(id)) return u;
        return null;
    }
}
