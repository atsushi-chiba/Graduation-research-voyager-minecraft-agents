# 進捗報告(ベータ版・逐一更新)

> このファイルは作業の節目ごとに随時更新される「生きた」進捗報告。
> セッションの区切りで日付版(progress_report_YYYY-MM-DD.md)に整理・昇格する。
> 最終更新: 2026-07-07(日付版へ昇格・リセット)

## 直近の確定版

- **progress_report_2026-07-07.md** — 2026-07-03〜07 のまとめ(病気×飢餓スパイラル根治、
  施設検証スイート、市長の建設暴走ガバナー、builder作業半径・チャンクロード問題、
  クリーン監査達成、次フェーズ方針)
- **HANDOFF_2026-07-07.md** — 後続セッションへの引き継ぎ(最優先タスク:
  /assignHome デプロイ→辺境builderの引っ越し→建設停滞解消)

## 2026-07-07 午後: レシピ教示システム + 錬金術師整理(引き継ぎは4日目に延期)

- ユーザー指示で Fable 5 継続(あと4日)、引き継ぎ準備は最終日に実施
- **レシピ教示**(職業ブロックのGUI限定機能をbridge化、ユーザー要望):
  - `/teachRecipe`(RecipeStorage組立→全craftingモジュールにaddRecipe試行。
    容量式 **2^建物lv×研究倍率×canLearnManyRecipes(×5)** と職業別適合タグは
    ゲーム側の判定をそのまま使用)、`/recipes`(taught/max/一覧)
  - `crafter_recipes.json`: 7職種(sawmill/stonemason/stonesmeltery/blacksmith/
    glassblower/fletcher/mechanic)の優先順教示リスト
  - supply_bot が10サイクルごとに自動教示(満杯で停止→レベルアップで自動再開)。
    実測: sawmill 8レシピ、stonesmeltery は製錬レシピがSmeltingModuleへ正しく振り分け
- **錬金術師整理**(ユーザー指摘「多すぎ」): 41棟→未建設23棟撤去(work order 64→40)、
  建設済みlv1-2の15棟は `/setHiringMode MANUAL+fire` で退役(構造物は温存)、
  lv5×3棟のみ稼働継続。就労18人→3人、15人が求職プールへ
- `/assignHome` デプロイ・稼働: 辺境builder 7人中3人を職場近くへ引っ越し
  (残り4人は近隣住宅が全満室 — 新築完成後に再試行)

## 現在の状態スナップショット(2026-07-07)

- コロニー: 203棟 / 市民118人 / 建設待ち64件(通勤問題で停滞中 — HANDOFF参照)
- 常駐系: council(常駐化済み)+ supply_bot + colony_watch.sh(セッションごと要再アーム)
- 運用ガイド: /root/CLAUDE.md(セッション開始チェックリスト・鉄の掟)
