package com.example.worldprestige;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

import java.math.BigDecimal;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * アップグレードの定義表。強化を増やすときは、ここに 1 行足すだけ(画面・購入・保存・通信は自動で対応する)。
 * 何回でも購入でき、購入するたびにレベルが 1 上がる(maxLevel を指定すると上限が付く)。
 * 機械の動作速度を上げる強化は、仕組みを PrestigeEffects で参照。
 *
 * 引数: id, NBTキー, 表示名, 説明(ホバーで表示), 基本価格, 上昇幅, 1Lvあたりの加速量(0=加速しない強化),
 *       最大Lv(0=上限なし), アイコン, 対象の機械の判定(null = PrestigeEffects 側で判定する / 機械ではない強化)
 */
public enum Upgrade {
    FURNACE("furnace", "UpgradeFurnace", "かまど類", "かまど・溶鉱炉・燻製器の動作速度が上がる",
            5, 2, 0.01, 0, () -> Items.FURNACE, null),
    MACHINE("machine", "UpgradeMachine", "Mekanism機械", "Mekanism の機械の動作速度が上がる",
            10, 4, 0.01, 0, () -> modItem("mekanism", "enrichment_chamber", Items.BLAST_FURNACE), null),
    MULTIBLOCK("multiblock", "UpgradeMultiblock", "Mekマルチブロック", "Mekanism のマルチブロックの動作速度が上がる",
            20, 8, 0.01, 0, () -> modItem("mekanism", "steel_casing", Items.IRON_BLOCK), null),
    AUTO_CONVERT("auto_convert", "UpgradeAutoConvert", "自動ポイント変換", "ジェネレーターで、作ったフラグメントをその場でポイントに変えられるようになる",
            20, 0, 0.0, 1, () -> Items.HOPPER, null);

    // ---- 追加の例(行頭の // を外して、上の ; を , に直す) ----
    // 別 MOD の機械を加速する強化。名前空間 "create" のブロックエンティティが対象になる:
    // CREATE("create", "UpgradeCreate", "Create機械", "Create の機械の動作速度が上がる",
    //         10, 4, 0.01, 0, () -> modItem("create", "mechanical_press", Items.PISTON), modNamespace("create")),
    // ※ 回転力などで動く機械は、tick を増やしても速くならない。

    private final String id;
    private final String nbtKey;
    private final String displayName;
    private final String description;
    private final int baseCost;
    private final int costStep;
    private final double perLevel;
    private final int maxLevel;
    private final Supplier<Item> icon;
    private final Predicate<BlockEntity> matcher;

    Upgrade(String id, String nbtKey, String displayName, String description,
            int baseCost, int costStep, double perLevel, int maxLevel,
            Supplier<Item> icon, Predicate<BlockEntity> matcher) {
        this.id = id;
        this.nbtKey = nbtKey;
        this.displayName = displayName;
        this.description = description;
        this.baseCost = baseCost;
        this.costStep = costStep;
        this.perLevel = perLevel;
        this.maxLevel = maxLevel;
        this.icon = icon;
        this.matcher = matcher;
    }

    public String id() { return id; }
    /** セーブデータ上のキー。変えると既存のレベルが 0 に戻る。 */
    public String nbtKey() { return nbtKey; }
    public String displayName() { return displayName; }
    /** ホバーで出す説明。 */
    public String description() { return description; }
    /** 1 レベルあたりの加速量。0.01 なら Lv.1 で +1%、Lv.100 で 2 倍速。0 なら加速しない強化。 */
    public double perLevel() { return perLevel; }
    /** 購入できる最大レベル。0 なら上限なし。 */
    public int maxLevel() { return maxLevel; }
    /** 対象の機械の判定。null なら PrestigeEffects の既定の判定(かまど・Mekanism)に任せる。 */
    public Predicate<BlockEntity> matcher() { return matcher; }
    /** ノードのアイコン。 */
    public ItemStack icon() { return new ItemStack(icon.get()); }

    /** 効果の表示。perLevel から自動で作る(手で合わせる必要はない)。 */
    public String effectText() {
        if (perLevel <= 0) return "";
        return "速度+" + BigDecimal.valueOf(perLevel).movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }

    /** 現在のレベル level から次のレベルへ上げるのに必要なポイント。 */
    public int cost(int level) {
        long c = (long) baseCost + (long) costStep * level;   // 線形: 基本価格 + 上昇幅 x レベル
        return (int) Math.min(Integer.MAX_VALUE, c);
    }

    public static Upgrade byId(String id) {
        for (Upgrade u : values()) if (u.id.equals(id)) return u;
        return null;
    }

    /** 別 MOD のアイテム。入っていなければ fallback。 */
    static Item modItem(String namespace, String path, Item fallback) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(namespace, path));
        return item == null || item == Items.AIR ? fallback : item;
    }

    /** ブロックエンティティの名前空間(MOD 名)で対象を決める判定。 */
    static Predicate<BlockEntity> modNamespace(String namespace) {
        return be -> {
            ResourceLocation key = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType());
            return key != null && key.getNamespace().equals(namespace);
        };
    }
}
