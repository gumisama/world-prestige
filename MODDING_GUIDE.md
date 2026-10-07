# World Prestige 改造ガイド

自分で数値や仕様をいじるための手引きです。「どのファイルの、どこを、どう変えると、何が起きるか」を書いています。

> **注意**: このコードはビルドも実機テストもできていません(構文チェックのみ)。
> 最初に必ず、**ワールドをコピーしたテスト用ワールド**で確認してください(第 8 章)。

**このMODの考え方**: ポイント・周回数・アップグレード(ワールド強化)は、すべて **ワールド全体で1つ**。
誰が買っても全員のポイントが減ります。強化の効果は **機械の動作速度**(かまど・Mekanism の機械・マルチブロック)で、プレイヤー自身は強化されません。リセットしても残ります。

---

## 1. フォルダ構成

```
プロジェクト/
├─ build.gradle              ビルド設定(通常はいじらない)
├─ gradle.properties         MOD の名前・版・作者などの設定
├─ settings.gradle           プロジェクト名
├─ build_mod.bat             Windows 用ビルドスクリプト
└─ src/main/
   ├─ java/com/example/worldprestige/
   │   ├─ WorldPrestige.java        MOD の入口。コマンド定義
   │   ├─ Upgrade.java              強化の定義表(値段・加速量)
   │   ├─ SharedPrestige.java       ポイント・周回数・強化レベルの保存/読み出し(全員共通)
   │   ├─ PrestigeEffects.java      機械の加速(追加ティック)
   │   ├─ PrestigeNetwork.java      クライアント⇔サーバーの通信
   │   ├─ PrestigeReset.java        ワールドリセット
   │   ├─ PrestigeScreen.java       GUI 画面(クライアント専用)
   │   ├─ PrestigeClient.java       受信したデータで GUI を開く/更新(クライアント専用)
   │   ├─ ModRegistry.java          ブロック・アイテム・クリエイティブタブの登録
   │   ├─ FragmentGeneratorBlock / BlockEntity / Screen(GUI)   電力 → World Fragment の生成機
   │   ├─ WorldPrestigeConfig.java  config(初期の必要電力・増加率)
   │   └─ WorldFragmentItem.java    World Fragment(使うとポイント加算)
   └─ resources/
       ├─ META-INF/mods.toml        MOD のメタデータ
       ├─ pack.mcmeta               リソースパックの情報
       ├─ assets/worldprestige/     モデル・テクスチャ・言語ファイル(ja_jp / en_us)
       └─ data/                     レシピ・ルートテーブル・ツールタグ
```

| やりたいこと | 触るファイル |
|---|---|
| 値段・加速量を変える | `Upgrade.java` |
| 値段の上がり方(式)を変える | `Upgrade.java` の `cost()` |
| 加速の範囲・上限・対象 MOD を変える | `PrestigeEffects.java` 冒頭の設定 |
| 強化を増やす | `Upgrade.java` + `PrestigeEffects.classify()` |
| リセットの報酬・消す物を変える | `PrestigeReset.java` 冒頭の設定 |
| リセットを実行できる条件を変える | `PrestigeReset.canUse()` |
| コマンドの追加・権限変更 | `WorldPrestige.java` |
| GUI の見た目・文言 | `PrestigeScreen.java` |
| 作者名・説明 | `gradle.properties` |
| 生成機の初期の必要電力・増加率 | `config/worldprestige-common.toml`(定義は `WorldPrestigeConfig.java`) |
| 生成機の入力速度 | ゲーム内: ブロックを右クリック → GUI で数字を入力 |
| 生成機の貯蔵数・設置直後の入力速度 | `FragmentGeneratorBlockEntity.java` 冒頭の設定 |
| World Fragment 1 個のポイント量 | `WorldFragmentItem.POINTS_PER_FRAGMENT` |
| 生成機のレシピ | `data/worldprestige/recipes/fragment_generator.json` |
| 見た目(テクスチャ) | `assets/worldprestige/textures/` の png(16x16。仮の絵なので差し替え推奨) |

---

## 2. 全体の流れ

### 2-1. データの持ち方

すべて `SharedPrestige` がメモリに持ち、ワールドフォルダの **`worldprestige_shared.dat`** に保存します
(値が変わるたびに即保存。リセットの対象外)。

```
worldprestige_shared.dat
├─ Points        : int   共通ポイント
├─ Laps          : int   周回数(リセットした回数)
├─ UpgradeFurnace    : int   かまど類のレベル
├─ UpgradeMachine    : int   Mekanism機械のレベル
├─ UpgradeMultiblock : int   Mekマルチブロックのレベル
├─ Upgrade○○Active  : int   各強化の「適用中」レベル(リセットのとき購入レベルに揃う)
└─ FragmentsMade     : long  世界全体で作られた World Fragment の数(生成機の必要電力の増加に使う)
```

プレイヤー個人にはデータを持たせていません(個人のセーブには何も書き込まない)。
内容はゲーム内では `/prestige points` で確認できます。

### 2-2. 購入の流れ

```
[GUI のボタンを押す]
  PrestigeScreen.purchase()        BuyPacket(強化ID) を送る
        ↓
  PrestigeNetwork.handleBuy()      ←サーバー側。ここで本当の判定をする
        ↓
  SharedPrestige.purchase()        ポイント不足なら false / OK ならポイントを引いてレベル +1
        ↓
  PrestigeNetwork.syncAll()        StatePacket(ポイント・周回・レベル)を全員へ
```

購入したレベル(`SharedPrestige.levels`)は、すぐには効きません。**次のワールドリセットで「適用中」のレベル(`activeLevels`)に反映**されます。`PrestigeEffects` は適用中のレベルを見て機械を加速します。
クライアントの表示(ボタンが押せるか等)はあくまで目安で、**価格の判定は必ずサーバー側**です。

### 2-3. 機械加速の仕組み

機械(BlockEntity)は、ブロックごとに決まった「tick 処理」を毎 tick 1 回呼ばれて動いています。
`PrestigeEffects` はこの tick 処理を **追加でもう何回か呼ぶ**ことで加速します。

- 追加回数 = `perLevel` × レベル(例: Lv.100 で 1.0 回 = 2 倍速)。小数部は確率で切り上げ。
- 20 tick ごとに、プレイヤーの周囲 `SCAN_RADIUS` チャンクのロード済み機械を探して一覧にする。
- サーバー側だけで完結するので、クライアントへの同期は要らない。
- 燃料・電力・材料も加速したぶん多く消費する(1 個あたりのコストは変わらず、時間あたりの量が増える)。

---

## 3. ファイル別リファレンス

### 3-1. `Upgrade.java` ― 数値の表

```java
//          id         NBTキー           表示名       効果表示    基本価格 上昇幅 1Lvあたりの加速量
FURNACE    ("furnace", "UpgradeFurnace", "かまど類",  "速度+10%", 5,       2,     0.10),
```

| 項目 | 意味 | 変えるとどうなる |
|---|---|---|
| `id` | 通信で使う名前 | 変更可 |
| `nbtKey` | `worldprestige_shared.dat` 上のキー | **変えると既存のレベルが 0 に戻る**。基本は触らない |
| `displayName` | GUI・メッセージの名前 | 見た目だけ |
| `effectText` | ボタンに出す効果の文字 | **見た目だけ**。実際の効果は `perLevel`。手で合わせる |
| `baseCost` | 最初の購入(Lv.0→1)の値段 | 全体の値段が上下 |
| `costStep` | 1 レベルごとの値段の増加量 | 大きいほど後半が高くなる |
| `perLevel` | 1 レベルあたりの加速量 | 0.01 = +1%。Lv.100 で 2 倍速、Lv.200 で 3 倍速 |

**値段の式**は `cost(int level)`(線形)。倍々に増やしたいとき(1.5 倍ずつ):

```java
public int cost(int level) {
    double c = baseCost * Math.pow(1.5, level);
    return (int) Math.min(Integer.MAX_VALUE, Math.round(c));
}
```

加速には上限(`PrestigeEffects.MAX_EXTRA`、初期値 9 = 最大 10 倍速)があります。
**ワールド共通の強化は全員の機械に効く**ので、人数が多いサーバーでは値段のバランスも考えてください。

### 3-2. `SharedPrestige.java` ― 保存と読み出し(全員共通)

| メソッド | 役割 |
|---|---|
| `getPoints / setPoints` | 共通ポイント |
| `getLaps / addLap` | 周回数 |
| `getLevel(upgrade)` / `getLevels()` | 強化レベル(`getLevels` は通信用の配列) |
| `purchase(upgrade)` | 購入(判定→ポイント減算→レベル +1)。**購入条件を変えたいときはここ** |
| `applyResetToFile(root)` | リセット時、停止後のファイルに周回数 +1・ポイント加算を書く |

- サーバー起動時(`ServerStartingEvent`)に読み込み、変更のたびに保存、停止時(`ServerStoppingEvent`)にも保存。
  シングルプレイでワールドを開き直すたびに読み直されます。
- 書き込みは一時ファイル経由なので、保存中に落ちても壊れにくい構造です。
- 共通の値を変える処理の後は `PrestigeNetwork.syncAll(server)` を呼ぶと、全員の GUI に反映されます。

### 3-3. `PrestigeEffects.java` ― 機械の加速

冒頭の設定(`MACHINE_MODS` / `SCAN_RADIUS` / `SCAN_INTERVAL` / `MAX_EXTRA`)で調整できます。

| 強化 | 対象 | 判定 |
|---|---|---|
| かまど類 | かまど・溶鉱炉・燻製器(とその継承クラス) | `AbstractFurnaceBlockEntity` |
| Mekanism機械 | `TileEntityMekanism` を継承した BlockEntity(アドオン含む)、または `MACHINE_MODS` の名前空間 | ケーブル・パイプ類(`TileEntityTransmitter`)、マルチブロックの構成ブロックと内部パーツ(`TileEntityInternalMultiblock`)は除く |
| Mekマルチブロック | `TileEntityMultiblock` / `IMultiblock` を持つ BlockEntity | `getMultiblock()` で構造データを取り、その `tick(Level)` を **リフレクション**で追加で呼ぶ(構造 1 つにつき 1 回/tick。`tick(Level)` が無ければ構成ブロックの tick を追加で呼ぶ) |

- Mekanism は依存に入れていないので、Mekanism が無い環境でもビルド・起動できる。
- 追加 tick で例外が出たクラスは、ログに出したうえで以後の加速を止める(サーバーを落とさないため)。
- プレイヤーから遠い機械は加速されない(通常の速度では動く)。範囲を広げると負荷が増える。

### 3-4. `PrestigeNetwork.java` ― 通信

- `VERSION` : **パケットの形や強化の数を変えたら必ず上げる**(例 `"5"`→`"6"`)。版が違うと接続できなくなる。
- `BuyPacket` (クライアント→サーバー) : 買いたい強化の id だけ。
- `StatePacket` (サーバー→クライアント) : ポイント・周回数・`open`・レベル配列。`open=true` で GUI を開く。
- `open(p)` : GUI を開かせる / `sync(p)` : 1 人に状態だけ送る / `syncAll(server)` : 全員に送る。
- `GeneratorActionPacket` / `GeneratorPacket` : 生成機の GUI 用(3-8 参照)。
- `handleBuy` : 購入後に全員の GUI を更新し、「ワールド強化: かまど類 Lv.3 (購入: 名前)」を全体チャットに流す。

### 3-5. `PrestigeScreen.java` / `PrestigeClient.java` ― 画面

`PrestigeScreen.init()` が `Upgrade.values()` を順に回してボタンを作ります。**強化を増やしても画面の変更は不要**です。

| 触りたい所 | 場所 |
|---|---|
| ボタンの幅・高さ・間隔 | `addUpgradeButton` の `bounds(x, y, 220, 20)` と `init()` の `y += 25` |
| ボタンの文字 | `addUpgradeButton` の `label` |
| 上の「Prestige Point」「周回数」の表示 | `render()` |
| 一番下のヒント文 | `render()` の `hintY` の行 |
| 買えないときにボタンを無効化するか | `button.active = ...` の行(消せば常に押せる。判定はサーバー側) |

### 3-6. `WorldPrestige.java` ― コマンド

| コマンド | 権限 | 内容 |
|---|---|---|
| `/prestige`, `/prestige gui` | 誰でも | GUI を開く |
| `/prestige points` | 誰でも | 共通ポイントと周回数を表示 |
| `/prestige reset` | `PrestigeReset.canUse` | ワールドリセット(確認 GUI) |
| `/prestige give` | OP | +1pt して GUI を開く |
| `/prestige add <n>` / `set <n>` | OP | ポイントを増やす/設定 |
| `/prestige lap` | OP | 周回数 +1(デバッグ) |
| `/prestige debug` | OP | 機械加速の診断(対象の数、クラスごとの認識・失敗理由)。ログにも出る |

権限は `.requires(source -> source.hasPermission(2))` の数字(0=誰でも、2=OP、4=最高)。`.requires(...)` を消せば誰でも使えます。

コマンドを足す型:

```java
.then(Commands.literal("hello")
        .executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            context.getSource().sendSuccess(() -> Component.literal("hello " + player.getName().getString()), false);
            return 1;
        }))
```

### 3-7. `gradle.properties` / `mods.toml`

`gradle.properties` の `mod_*` が `mods.toml` に差し込まれます。版を上げたら `mod_version` を変更。
ただし `build_mod.bat` は jar 名 `worldprestige-0.3.0.jar` を固定で書いているので、**版を変えたら .bat の 2 か所も直す**こと。


### 3-8. World Fragment と生成機

```
[電力(FE)] → (入力速度の範囲で受け取り) → 毎 tick 全部消費 → progress に加算
   → progress が必要電力に達するたび World Fragment +1(必要電力は増える)
   → 右クリックで使う → SharedPrestige のポイント +1 → 全員の GUI を更新
```

- `ModRegistry` : ブロック・アイテム・ブロックエンティティ・クリエイティブタブの登録(`WorldPrestige` のコンストラクタから呼ぶ)。
- `FragmentGeneratorBlockEntity` :
  - `speed` : 1 tick に受け取れる上限(FE/t)。**long**。GUI から変更。0 なら何も受け取らない。Forge Energy の受け渡し 1 回は int(約 21 億)までなので、それを超える量は複数回の受け渡しの合計で受け取る。
  - 受け取った分(`pending`)は次の `serverTick` で必ず `progress` に移される(= 無条件に消費)。
  - 必要電力 = `WorldPrestigeConfig.cost(世界全体で作られた個数)` = 初期値 × (1 + 増加率)^個数。1 個ごとに増える。余った `progress` は持ち越し。
  - 満杯(`MAX_STORED_FRAGMENTS`)のときだけ電力を受け取らない。
- `SharedPrestige.getFragmentsMade()` : 作られた総数。ワールド共通で、`worldprestige_shared.dat` に保存され、リセットしても残る(生成機を何台置いても増加率は共通)。
- `WorldPrestigeConfig` : `config/worldprestige-common.toml` の `initialEnergyCost` と `costGrowthPercent`。
- `FragmentGeneratorScreen` : 設定 GUI(クライアント専用)。入力欄に数字を入れて「設定」か Enter。0.5 秒ごとに状態を取り直す。
- 通信 : `GeneratorActionPacket`(クライアント→サーバー: 速度設定/取り出し/状態要求)、`GeneratorPacket`(サーバー→クライアント: 状態)。サーバー側で距離(8 ブロック以内)と BlockEntity の存在を確認している。
- 生成数は `WorldPrestigeConfig.affordable()` で一括計算(1 tick に何個でも作れる)。機械の中の上限は `MAX_STORED_FRAGMENTS`。GUI の「ポイントに変換」は `convertToPoints()`、「実測入力」は直近 20 tick の平均(`measured`)。
- **無制限モード**(`unlimited`、GUI のボタン): `receiveEnergy` が速度の上限を見ずに全部受け取り、`pullFromNeighbors()` が隣接 6 方向の供給元へ `extractEnergy(Integer.MAX_VALUE)` をconfig の pullLoopsPerTick回/tick(時間はpullTimeBudgetMs で制限)繰り返して吸い出す。Forge Energy の 1 回 = int までの上限は、この繰り返しで超える。ケーブルは供給元ではない(`canExtract` が false)ので、吸い出せるのは隣接するキューブ・バッテリー・発電機など。
- 電力は **Forge Energy** の Capability で受ける。Mekanism のケーブルも FE の機械に接続できる(Mekanism 側の FE 変換設定に従う)。
- `WorldFragmentItem` : サーバー側だけで処理。ポイントは共通なので、使ったのが誰でも全員のポイントが増える。
- 新しい物を足すときは、`ModRegistry` に登録 + `assets` のモデル/言語ファイル + (ブロックなら)blockstate・ルートテーブル・ツールタグが要る。

---

## 4. よくある改造レシピ

### A. 値段・効果を調整する
`Upgrade.java` の数値を変えるだけ。`effectText` も合わせる。既存のデータにもそのまま効きます(レベルだけ保存し、値段は毎回計算するため)。

### B. 強化を新しく追加する(例: 別 MOD の機械)

1. `Upgrade.java` に 1 行追加(最後のセミコロンの位置に注意):
   ```java
   MULTIBLOCK ("multiblock", "UpgradeMultiblock", "Mekマルチブロック", "速度+10%", 20, 8, 0.10),
   CREATE     ("create",     "UpgradeCreate",     "Create機械",        "速度+10%", 10, 4, 0.10);
   ```
2. `PrestigeEffects.classify()` で、その機械に対して新しい `Upgrade` を返す:
   ```java
   if (key != null && key.getNamespace().equals("create")) return Upgrade.CREATE;
   ```
3. `PrestigeNetwork.java` の `VERSION` を上げる。

GUI・購入・保存・通信は自動で対応します。
なお回転力で動く MOD など、tick 処理を増やしても速くならない仕組みの機械には効きません。

### C. リセットの報酬を変える
`PrestigeReset.java` 冒頭の `REWARD_POINTS`。0 にすると周回数だけ増えます。
周回数に応じて増やしたいなら、`SharedPrestige.applyResetToFile()` の `PrestigeReset.REWARD_POINTS` を
たとえば `PrestigeReset.REWARD_POINTS + newLaps` のように式にします。

### D. リセットで消すもの/残すものを変える
`TARGETS` のリストがそのまま「消す(退避する)フォルダ」です。

- 進捗を残したい → `"advancements"` を消す
- 持ち物・位置も残す → `"playerdata"` を消す(位置も残るので新しい地形の中に出ることになる。基本おすすめしない)
- 統計(`stats`)、データパック(`datapacks`)は最初から対象外

### E. 毎回同じ地形にする / 古いワールドを残さない
- `NEW_SEED = false` : シードを変えない
- `KEEP_ARCHIVE = false` : 古いワールドを**完全削除**(元に戻せない。慣れてから)

### F. リセットに条件を付ける(例: ドラゴン討伐後だけ)
実行条件は `PrestigeReset.canUse()` に集まっています。`false` を返すと `/prestige reset` 自体が使えなくなります。

```java
public static boolean canUse(CommandSourceStack source) {
    if (!isDragonDefeated(source.getServer())) return false;   // ← 自分で作る条件
    ...(元の判定)
}
```
まず `return false;` を入れて「使えなくなる」ことを確認してから書くと安全です。
条件を満たさないときにメッセージを出したいなら、`request()` の先頭で判定して `source.sendFailure(...)` を返します。

### G. リセットの確認 GUI を変える
確認は `ResetConfirmScreen`(クライアント専用)で、`/prestige reset` がサーバーから `ResetPromptPacket` を送って開かせます。最後の「はい」で `ResetExecutePacket` がサーバーに届き、`PrestigeReset.confirmFromGui` が(権限と有効期限を確認して)リセットを始めます。
文言・ボタンの位置は `ResetConfirmScreen` の `init()` と `render()`、有効時間は `PrestigeReset.CONFIRM_MILLIS`。GUI のボタンから開きたいときは、`PrestigeNetwork` に「リセット要求」パケットを足して `PrestigeReset.request()` 相当を呼びます。

### H. 対象の MOD を増やす
Mekanism のアドオンは `TileEntityMekanism` を継承していれば自動で対象です。継承していない MOD(Thermal など)は、`PrestigeEffects.MACHINE_MODS` に名前空間(例 `"thermal"`)を足すと「Mekanism機械」と同じ扱いになります。
追加の tick で挙動がおかしくなる機械があったら、その MOD は外してください(例外が出たクラスは自動で止まります)。

### I. 名前・作者を変える
`gradle.properties` の `mod_authors`, `mod_description`, `mod_name`。

---

## 5. リセットの仕組み(`PrestigeReset.java`)

### 5-1. なぜ「停止してから」入れ替えるのか
動いているワールドのファイルを差し替えると、保存処理とぶつかって壊れやすいからです。

1. `/prestige reset` : 確認 GUI(`ResetConfirmScreen`)を開く。1 回目「リセットしますか？」→「はい」→ 2 回目「本当にリセットしますか？」(**はい/いいえの位置が逆**)
2. 2 回目で「はい」→ サーバーに通知(120 秒以内。`PrestigeReset.confirmFromGui`)→ フラグを立ててサーバーを停止
   (シングルプレイならタイトル画面に戻る。専用サーバーは停止するので再起動が必要)
3. **サーバーが完全に停止した後**(`ServerStoppedEvent`)に `performReset()` が実行される
4. もう一度ワールドを開くと、新しい地形で始まる

### 5-2. `performReset()` が行うこと(順番が重要)

1. **移動/削除**: `TARGETS` のフォルダを `prestige_archive/<日時>/` へ移動(`KEEP_ARCHIVE=false` なら削除)。`level.dat` も同じ場所にコピー。
2. **level.dat の書き換え**:
   - `Player` を削除(シングルプレイのホストの位置・持ち物)
   - `DragonFight` を削除(残すと「討伐済み」扱いで次の周にドラゴンがいない)
   - `initialized=false`(スポーン地点を作り直させる)
   - `Time`/`DayTime`=0、天気を晴れに
   - `WorldGenSettings.seed` を新しい値に(`NEW_SEED` が true のとき)
3. **報酬**: `worldprestige_shared.dat` に周回数 +1・ポイント加算(1 回だけ)。レベルはそのまま残る。

ポイント・周回数・強化レベルは `worldprestige_shared.dat` にあり、`TARGETS` に入っていないので、リセットで消えません。

### 5-3. 元に戻す方法(`KEEP_ARCHIVE=true` のとき)
1. ゲーム/サーバーを完全に終了
2. `ワールドフォルダ/prestige_archive/<日時>/` の中身(`region` など)をワールドフォルダへ戻す
3. 同じ場所の `level.dat` をワールドフォルダの `level.dat` に上書きコピー

ポイント・周回数・強化は戻らず現状維持です(必要なら `/prestige set` で調整)。

### 5-4. 注意点
- 複数人サーバーでは、**実行した 1 人の操作で全員のワールドが入れ替わります**。
- `data/` を消すので、マップアイテム・スコアボード・他 MOD が `data/` に持つ情報も初期化されます。
- 古いワールドが周回ごとに溜まります。不要なら `prestige_archive/` の中を手で削除してください。
- 他の MOD が独自のフォルダにワールドデータを持つ場合は、`TARGETS` に追加しないと消えません。

---

## 6. 用語メモ

| 用語 | 意味 |
|---|---|
| サーバー側/クライアント側 | シングルプレイでも内部ではサーバー(ワールド計算)とクライアント(画面)が別。ルール判定は必ずサーバー側 |
| Attribute Modifier | 最大体力や移動速度などの「属性」に付ける補正。UUID で識別する |
| NBT | Minecraft のセーブデータ形式。`CompoundTag` が「キーと値の集まり」 |
| Packet | クライアントとサーバーの間で送る小さなメッセージ |

---

## 7. ビルドと更新

- Forge は **47.4.10 固定**、Java **17**。
- 変更したら `build_mod.bat` で再ビルド → `built/worldprestige-0.3.0.jar` を `mods` フォルダへ。
- 通信の形を変えたら `PrestigeNetwork.VERSION` を上げ、**サーバーとクライアントの jar を同じ物にそろえる**。

---

## 8. 動作確認チェックリスト(必ずテスト用ワールドで)

0. 本番ワールドは**フォルダごとコピー**してバックアップ。テストはコピー側で。
1. ワールドに入る → `/prestige` で GUI が開く。ポイント 0 でボタンが押せない(グレー)。
2. OP で `/prestige add 100` → GUI を開いたままポイントが即更新される。
3. ボタンを押す → **画面が閉じずに**レベルが上がり、次の値段が増える。何度でも買える。全体チャットに「ワールド強化: …」が出る。
4. (2 人以上で)片方が買うと、もう片方のポイント表示も減り、**両方に効果が出る**。
5. かまど速度: 強化を買う(GUI の「適用」は 0 のまま)→ **ワールドリセット後**、適用が上がり、かまどが速く焼けるか確認(燃料も速く減る)。
6. Mekanism(入れている場合): 機械・マルチブロックを買ってリセット後、動かして速くなるか・電力が速く減るか確認。`/prestige debug` で状態を見る。プレイヤーから 6 チャンク以上離れた機械は加速されない。
7. `/prestige points` で周回数 0。
8. `/prestige reset` → 確認 GUI が出る。「いいえ」や Esc で閉じるとリセットされない。
9. `/prestige reset` →「はい」→ 2 回目(はい/いいえが逆の位置)で「はい」→ ワールドから出る。
10. ワールドフォルダを確認: `prestige_archive/<日時>/` ができ、`region` 等が移動している。`worldprestige_shared.dat` は残っている。
11. ワールドを開き直す → 新しい地形・新しいスポーン。**ポイント・レベルが残り、周回数 1、ポイント +1**。強化の効果も付いている。
12. 9〜11 をもう一度(2 周目で周回数 2 か)。
13. エンドへ行ってドラゴンが出るか。

14. クリエイティブタブ「World Prestige」に World Fragment と生成機がある。生成機を置いて右クリック → GUI が開く。入力速度を入力して「設定」。
15. FE をケーブル等で入れる → 「消費済み」が増え、必要電力に達するたび「機械の中」が増える。2 個目以降は必要電力が増える(config の増加率どおりか)。取り出しボタン・ホッパー取り出しも確認。World Fragment を右クリック → ポイント +1(スニーク+右クリックで全部)。
16. 入力速度を 0 にすると電力を受け取らない。機械の中が上限(`MAX_STORED_FRAGMENTS`、100 万個)で満杯だと電力を受け取らない。ワールドを開き直しても 速度・消費済み・個数・累計生成数 が残る。生成機を壊すと中身が落ちる。

17. 生成機の GUI で「入力上限」ボタンを押す → 「無制限」になり、入力速度の表示が変わる。エネルギーキューブ等を隣に置くと、一気に吸い出されて「消費済み」が増える。もう一度押すと上限ありに戻り、設定していた入力速度が使われる。

うまくいかないときは `logs/latest.log` を `[WorldPrestige]` で検索すると、保存・リセットの成否が分かります。

---

## 9. トラブルシューティング

| 症状 | 原因の候補 | 対処 |
|---|---|---|
| 接続できない(MOD の版が違う) | `VERSION` が違う jar が混在 | jar を同じ物に |
| `/prestige reset` が使えない | 権限。LAN 公開中の非ホストなど | OP にする、または `canUse` を確認 |
| ポイントや強化が 0 に戻った | `worldprestige_shared.dat` の読み書き失敗/ファイルを消した | ログの `[WorldPrestige]` を確認。ワールドフォルダにファイルがあるか確認 |
| リセットしたのに同じ地形 | `NEW_SEED=false`、または level.dat の書き換え失敗 | 設定とログを確認 |
| リセット後のスポーン位置が古い | `initialized` の書き換えが効いていない | ログと `level.dat` を確認 |
| 次の周でドラゴンが出ない | `DragonFight` が残っている | level.dat の書き換え失敗の可能性。ログを確認 |
| `prestige_archive` への移動に失敗 | Windows で他のソフトがファイルを掴んでいる | ゲームを完全終了/ウイルス対策の除外、ログのパスを確認 |
| 機械が速くならない | 範囲外、対象外の MOD/クラス、強化レベルが 0 | プレイヤーの近くで試す。ログの `[WorldPrestige]` を確認。`MACHINE_MODS` を確認 |

---

## 10. 既知の注意点(未検証の部分)

次の部分は Minecraft/Forge の内部仕様に依存していて、**実機で最初に確認してほしい箇所**です。

- `level.dat` の項目名(`Player` / `DragonFight` / `initialized` / `WorldGenSettings.seed`)は 1.20.1 の構造を前提にしています。
- 停止後(`ServerStoppedEvent`)にフォルダを移動できること(シングルプレイでワールドが完全に閉じ切った後)。
- 一部のメソッド名(`isSingleplayerOwner`、`Screen.rebuildWidgets`、`NbtIo.readCompressed(File)` など)は 1.20.1 の公式マッピングを前提にしています。ビルドで「シンボルが見つからない」エラーが出たら、エラーメッセージを貼ってください。
- 機械の加速(`PrestigeEffects`)は、Mekanism のクラス名(`TileEntityTransmitter` / `TileEntityMultiblock` / `IMultiblock`)と `getMultiblock()` を前提にしています。マルチブロックが加速されない場合は、ここを疑ってください。
