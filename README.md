# World Prestige 0.3.0

Minecraft **1.20.1** / Forge **47.4.10** / Java 17.

改造のしかたは [MODDING_GUIDE.md](MODDING_GUIDE.md) を参照。

## Features

ポイント・周回数・アップグレードはすべて**ワールド全体で共通**です(誰かが買うと全員のポイントが減ります)。
強化の効果は **機械の動作速度**です(プレイヤー自身は強化されません)。

- `/prestige` / `/prestige gui` : 強化 GUI を開く(ポイントは増えない)
- `/prestige points` : 共通ポイントと周回数を表示
- `/prestige reset` : ワールドリセット(確認 GUI が 2 回出る。2 回目は「はい/いいえ」の位置が逆。OP、またはシングルプレイのホスト)
- OP 専用の管理コマンド: `/prestige give`(+1pt)、`/prestige add <n>`、`/prestige set <n>`、`/prestige lap`、`/prestige debug`(機械加速の診断)
- ワールド強化(何回でも購入可、買うたびに値段が上がる)。**購入した強化は次のワールドリセット後から適用**される(GUI の「適用」が実際に効いているレベル):
  - かまど類 速度 +1% / Lv  (基本 5pt、1Lv ごとに +2pt)  … かまど・溶鉱炉・燻製器
  - Mekanism機械 速度 +1% / Lv  (基本 10pt、1Lv ごとに +4pt)
  - Mekマルチブロック 速度 +1% / Lv  (基本 20pt、1Lv ごとに +8pt)
- 加速はプレイヤーの周囲 6 チャンク以内の機械だけに効く。燃料・電力も加速したぶん多く消費する。
- **World Fragment Generator**(ブロック): FE(Forge Energy)を**消費**して **World Fragment**(アイテム)を作る。
  - 右クリックで設定 GUI が開く。**入力速度(FE/t)を好きな数字で指定**できる(long。FE の仕組み上 1 回の受け渡しは約 21 億までだが、複数回の合計で上限まで受け取れる)。World Fragment の取り出しもここ(ホッパー/パイプからも取り出せる)。
  - 受け取った電力は毎 tick 無条件に消費され、**消費した電力の合計**が必要量に達するたびに 1 個作られる(ブロックに溜めた電力は使わない)。
  - 必要電力は **1 個作るごとに増える**(世界全体で共通の個数で計算)。初期値と増加率は `config/worldprestige-common.toml` で変更。
    初期値 `initialEnergyCost` = 1,000,000 FE、増加率 `costGrowthPercent` = 1.0 (%)。
  - GUI の「入力上限」ボタンで**無制限モード**にできる: 入力速度の設定を無視して受け取れるだけ受け取り、さらに**隣接する FE の供給元(エネルギーキューブ等)から吸い出す**。
    1 回の受け渡し(約 21 億 FE)の上限は、吸い出しを 1 tick に何度も繰り返すことで超える。ケーブル経由の場合はケーブル側の転送量の上限が残る。
    繰り返しの上限は config で変えられる: `pullLoopsPerTick`(供給元 1 つあたりの回数。初期値 20,000)と `pullTimeBudgetMs`(1 tick に吸い出しへ使う時間。初期値 5 ms。底なしの供給元でサーバーが重くならないための歯止め)。供給元が空になれば、上限に届く前に止まる。
  - 機械の中(最大 100 万個)が満杯のときだけ、電力を受け取らない。1 tick に作れる個数に上限はない(等比数列の和で一括計算)。
  - GUI の「実測入力」で、実際に入ってきている電力(FE/t)と、今のペースで 1 秒に何個作れるかが分かる。
  - 「すべてポイントに変換」ボタンで、アイテムを経由せず機械の中のフラグメントを直接ポイントにできる。
- **World Reconstructor**(ブロック): 右クリックで強化 GUI を開く(`/prestige` と同じ画面。コマンド不要)。レシピは `ICI / CFC / ICI`(I=鉄ブロック、C=Mekanism の鋼鉄ケーシング、F=World Fragment)。Mekanism が入っていないとレシピは登録されない。
- **World Fragment**: 右クリックで 1 個使い、共通の Prestige Point が +1(スニーク+右クリックで持っている分を全部)。
- Mekanism のアドオン(`TileEntityMekanism` を継承した機械)も自動で対象。AE2 系のアドオンは対象外。
- Mekanism は必須ではない(入っていなければ、かまど類だけが対象)。
- データはワールドフォルダの `worldprestige_shared.dat` に保存(リセットしても残る)

## ワールドリセット

サーバー(シングルプレイならワールド)を停止し、停止後にワールドの中身を入れ替えます。
周回数 +1、共通ポイント +1。ポイント・強化・周回数は引き継がれ、
古いワールドは `ワールドフォルダ/prestige_archive/<日時>/` に保管されます。
詳細は MODDING_GUIDE.md の「リセットの仕組み」を参照。

## Build on Windows

Run `build_mod.bat`. It downloads the Forge **1.20.1-47.4.10** MDK, installs this source into it, and builds with the included Gradle wrapper. It requires Java 17 and internet access for the MDK and Gradle dependencies.

The JAR is written to `built\worldprestige-0.3.0.jar`.

The project pins `forge_version=47.4.10`; metadata also restricts the supported Forge version to 47.4.10.

## GitHub で管理する

`.github/workflows/build.yml` があるので、GitHub に push すると自動で jar がビルドされます(Actions タブ → 実行結果の Artifacts から `worldprestige` をダウンロード)。
`v0.3.0` のようなタグを push すると、Releases に jar が添付されます。ビルドエラーのログもそこで見られます。

```
git init
git add .
git commit -m "World Prestige 0.3.0"
git branch -M main
git remote add origin https://github.com/<ユーザー名>/<リポジトリ名>.git
git push -u origin main
```
