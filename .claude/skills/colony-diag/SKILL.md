---
name: colony-diag
description: MineColonies コロニーの状態確認と、市民・建設が止まったときの診断ランブック。「状況を確認して」「〇〇が働かない」「建設が進まない」系の依頼で使う。
---

# コロニー診断ランブック

bridge API は `http://localhost:8089`。サーバーコンソールは
`echo '<コマンド>' > /root/mc-server-forge/cmd_pipe` で送信、出力は
`/root/mc-server-forge/console.log` を tail して読む。

## クイック状態確認(セッション開始時など)

1. サーバー生存 + コロニー概況: `curl -s http://localhost:8089/status`
   (citizens には `sick` / `disease` / `cureItems`、buildings には workers が入っている)
2. supply_bot 稼働確認: `pgrep -fa supply_bot.js` — 停止中だと建設は資材待ちで全停止(creativeresolve=false)。多重起動も不可。
3. work order 一覧: `curl -s 'http://localhost:8089/debugWorkOrders?colonyId=1'`
   (id / 種別 / 位置 / claimedBy / stage / blueprintLoaded)
4. 直近エラー: `grep -iE "error|exception" /root/mc-server-forge/console.log | tail -20`

## 市民が働かないときのチェック順

個別診断はまずこれ(出力は console.log):
```bash
echo 'minecolonies citizens info <colonyId> <citizenId>' > /root/mc-server-forge/cmd_pipe
```

1. **空腹** — AI 状態が `CHECK_FOR_FOOD` で止まる。対処: `/giveToCitizen` でパンを 32 個渡す。
2. **病気** — /status の `sick`/`disease` を見る。supply_bot の treatSickCitizens が自動治療するはずだが、手動なら `cureItems` を全部 `/giveToCitizen`(全治療アイテムが本人のインベントリに揃うと自己治療する。病院がないので放置すると永久に待つ)。
3. **資材待ち** — `/openRequests?x&y&z&citizenId`(建物座標+市民ID必須)と supply_bot.log を確認。
4. **blueprint ロード** — /debugWorkOrders の blueprintLoaded、console.log の "Error loading blueprint"。builder は blueprint ロード完了まで `LOAD_STRUCTURE` に留まる。
5. **道具の tier 制限** — 建物/職レベルを超える tier の道具は使えない。低レベル worker には木/石の道具を渡す(iron 以上は NG)。
6. **work order の偏り** — builder は他人の claimed order を横取りしない。偏っていたら `POST /rebalanceWorkOrders?colonyId=1` で均等化("Claiming an already claimed workorder!" WARN は無害)。
7. **claim移動後の亡霊参照ループ** — `LOAD_STRUCTURE→START_BUILDING→BUILDING_STEP→LOAD_STRUCTURE` を5秒周期で無限に回る(citizens info の遷移ログで判別)。原因: builder hut は workOrderId/進捗/資材リストを、AI は structurePlacer をキャッシュしており、claim だけ書き換えると分裂状態になる。bridge の rebalance は `onWorkOrderCancellation` を呼ぶよう修正済み(2026-07-02)だが、同型の症状が出たらサーバー再起動で AI キャッシュが飛び、building 側は getWorkOrder() の自己修復(claimedBy≠自分なら参照クリア)で治る。

## 注意

- シミュレーションは常に 10x で回す方針(ユーザー指示 2026-07-02)。診断のため一時的に 1x に落とすのは可だが、終わったら必ず `curl -s -X POST 'http://localhost:8089/tickrate?multiplier=10'` で戻す(コンソールコマンドではなく bridge の HTTP エンドポイント)。tickrate>1 中はゲーム内時間ベースの現象がすべて加速して見える点に注意。
- cmd_pipe に送ったコマンドの console 応答に `<--[HERE]` が付いていたら**不明コマンドのエラー**。成功と誤読しないこと。
- jobStatus は builder が物理的に作業を始めるまで "idle" のまま。idle ≒ 故障ではない。
