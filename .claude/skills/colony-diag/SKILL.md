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

1. **空腹** — AI 状態が `CHECK_FOR_FOOD` で止まる。対処: `/giveToCitizen` で食べ物を渡す。
   **パンではなく cooked_beef を渡すこと**: FoodUtils.canEatLevel は住居 lv3+ の市民に
   「栄養値 ≥ 住居レベル+1」を要求する(パン=5 は lv5 住居の市民には食べられない。
   cooked_beef=8 は全レベル OK)。supply_bot の給食も cooked_beef(2026-07-04〜)。
2. **病気** — /status の `sick`/`disease` を見る。supply_bot の treatSickCitizens が自動治療するはずだが、手動なら `cureItems` を全部 `/giveToCitizen`(全治療アイテムが本人のインベントリに揃うと自己治療する。病院は 128,-60,241 に建設済み 2026-07-04)。
   **配達結果の `gave X/Y` を必ず見る**: X<Y はインベントリ満杯で治療不成立。
   `/clearCitizenInventory` してから渡し直す(supply_bot は自動でこれをやる 2026-07-04〜)。
3. **資材待ち** — `/openRequests?x&y&z&citizenId`(建物座標+市民ID必須)と supply_bot.log を確認。
4. **blueprint ロード** — /debugWorkOrders の blueprintLoaded、console.log の "Error loading blueprint"。builder は blueprint ロード完了まで `LOAD_STRUCTURE` に留まる。
5. **道具の tier 制限** — 建物/職レベルを超える tier の道具は使えない。低レベル worker には木/石の道具を渡す(iron 以上は NG)。
6. **work order の偏り** — builder は他人の claimed order を横取りしない。偏っていたら `POST /rebalanceWorkOrders?colonyId=1` で均等化("Claiming an already claimed workorder!" WARN は無害)。
7. **職業別の環境要件(スーパーフラットでは自前で用意)** — 木こり: `LUMBERJACK_NO_TREES_FOUND` なら木がない。`place feature minecraft:oak <x> -60 <z>` をcmd_pipeで送って hut 周辺(半径〜15)に植える(y=-60が地表の空気層)。釣り人: **水深2ブロック必須**。blueprintの池は水深1なので `setblock <x> -62 <z> minecraft:water` で掘り下げる(2026-07-02 に fisher1 の107セルを深化済み)。空腹(CHECK_FOR_FOOD / SEARCH_RESTAURANT)も並発しやすく、パンを渡すまで作業を再開しないことがある。
8. **食堂(cook hut)完成後の全員絶食** — 食堂が稼働すると市民は**食堂メニューに登録された食べ物しか食べなくなる**(自分のインベントリのパンも無視して食堂に集まり、満腹度0で固まる)。新設食堂のメニューは空。対処: `POST /setMenu?x&y&z&items=minecraft:bread,minecraft:cooked_beef,...`(lv1の上限は5品。応答に実際に登録されたメニューが返る)。メニュー登録すると食堂が MinimumStock 要求を出し、supply_bot が自動で食材を納品する。
9. **農民が働かない** — 畑(カカシ)に種が未指定だと農民は一切働かない(通常はカカシGUIで指定)。確認: `GET /fields?colonyId=1`(位置・taken・seed)。対処: `POST /setFieldSeed?x&y&z&seed=minecraft:wheat_seeds`(座標はカカシ位置)。種を入れると farmer hut が自動で畑を claim して働き始める。
10. **claim移動後の亡霊参照ループ** — `LOAD_STRUCTURE→START_BUILDING→BUILDING_STEP→LOAD_STRUCTURE` を5秒周期で無限に回る(citizens info の遷移ログで判別)。原因: builder hut は workOrderId/進捗/資材リストを、AI は structurePlacer をキャッシュしており、claim だけ書き換えると分裂状態になる。bridge の rebalance は `onWorkOrderCancellation` を呼ぶよう修正済み(2026-07-02)だが、同型の症状が出たらサーバー再起動で AI キャッシュが飛び、building 側は getWorkOrder() の自己修復(claimedBy≠自分なら参照クリア)で治る。

## 注意

- シミュレーションは常に 10x で回す方針(ユーザー指示 2026-07-02)。診断のため一時的に 1x に落とすのは可だが、終わったら必ず `curl -s -X POST 'http://localhost:8089/tickrate?multiplier=10'` で戻す(コンソールコマンドではなく bridge の HTTP エンドポイント)。tickrate>1 中はゲーム内時間ベースの現象がすべて加速して見える点に注意。
- cmd_pipe に送ったコマンドの console 応答に `<--[HERE]` が付いていたら**不明コマンドのエラー**。成功と誤読しないこと。
- jobStatus は builder が物理的に作業を始めるまで "idle" のまま。idle ≒ 故障ではない。

## 市民がWORK状態に入らない(ぶらぶらする)とき

- `curl -s 'http://localhost:8089/debugCitizenAI?colonyId=1&citizenId=<id>'` で判定材料を見る。
  WORKに入る条件は「workerAIがAbstractEntityAIBasic かつ canGoIdle()==false かつ leisureTime==0」。
- `leisureTime>0` はランダム発生する余暇(一時的・正常。3600tickで自然消滅)。
- **farmer の canGoIdle:true 張り付き(2026-07-03 解決済みの実例)** —
  farmerの畑スケジューラは**日付ベース**: 畑を1サイクル処理すると
  `checkedExtensions[畑]=colony.getDay()` の刻印を押し、**翌日まで再訪しない**
  (`/debugFarm?x&y&z`(farmer hut座標)で colonyDay / checkedExtensions /
  extensionToWorkOn / stage を確認できる)。
  真因は start_server.sh が `doDaylightCycle false` にしていて **colonyDay が永遠に0**
  だったこと(「翌日」が来ない→畑は再起動ごとに1回しか働けない。再起動で一時的に
  動くのは checkedExtensions がNBT保存バグで消えるため)。
  **対処済み: doDaylightCycle=true に恒久変更**(start_server.sh 2026-07-03)。
  夜は市民が寝る(10xで実時間約1分/晩)。正常リズム =「毎朝1回畑を処理
  (耕す→植える→収穫を日替わりで1段階)→残りの時間は余暇/睡眠」。
- 日付ベースのスケジューラは他職にもあり得るので、「特定の職だけ1日1回しか
  働かない/全く働かない」ときはまず colonyDay の進行を疑う。

## requestBuild したのに work order が現れない/消える(2026-07-06 実例: 辺境のsawmill)

- まず `curl -s 'http://localhost:8089/debugBuildGates?x&y&z'` — 黙って発注を握り潰す
  全ゲート(research effect / 重複order / canBeResolved / **フットプリント各チャンクの
  owner**)を一括ダンプする。
- 最頻原因: **blueprintフットプリントが触れるチャンクに owner≠colonyId が混ざっている**。
  WorkManager.addWorkOrder の isWorkOrderWithinColony がプレイヤー向けメッセージだけ出して
  return する(サーバーログには何も出ない)。辺境の建物で起きる。
- bridge は設置時と requestBuild 時にチャンクを強制ロードして CLOSE_COLONY_CAP へ直接
  claim を書く自己修復を実装済み(2026-07-06)。それでも owner=0 が残る場合は
  debugBuildGates で特定して requestBuild を再実行すれば claim ごとやり直される。
- 豆知識: MineColonies 純正の claim 経路は「アンロードチャンク=次回ロード時に適用」に
  先送りするが、無人コロニーの辺境チャンクは誰も踏まないので永遠に適用されない。

## 病気×飢餓の悪循環(2026-07-04 に 33人中23人が病気になった実例)

メカニズム(CitizenAI.calculateNextState / EntityAISickTask / EntityAIEatTask を逆コンパイルで確認):

- **非guard職は SICK が EATING より優先** — 病気の間は食事状態に入れず満腹度が0に落ちて張り付く。
  「病人の満腹度が全員0」はこの仕様であって給食の故障ではない。
- そこに supply_bot が給食を配り続けると**食べられないままインベントリが満杯**になり、
  治療アイテム配達が `gave 0/1` で弾かれ、**永遠に病気**のまま食堂周辺に密集して感染が広がる。
  対処: `/clearCitizenInventory` → cureItems 配達(supply_bot は自動化済み。病人への給食もスキップする)。
- **guard職だけは EATING が SICK より優先** — 空腹の病気guardは食堂で `WAIT_FOR_FOOD` に固まる。
  座席のない食堂では eatPos が取れず GET_FOOD_YOURSELF への脱出経路も塞がるので、
  メニュー食品(cooked_beef等)を直接 `/giveToCitizen` すると食べて→病気処理→自己治療、と流れる。
- 座席問題: Colonial cookery1 blueprint には `sit_in`/`sit_out` タグが無く
  "Restaurant without sitting position" が毎tickスパムされる。健常市民は
  「インベントリに可食メニュー品があればその場で食べる」フォールバックで実害なし。
  病院(128,-60,241, lv1)建設済みなので今後の病人はベッド+healer経路もある。

## コロニーが「動いて見えるのに何も進まない」とき(最重要の落とし穴)

- **プレイヤーが誰もオンラインでないと、コロニーは UNLOADED 状態になり脳が止まる**
  (work orderバインド・日付・リクエスト処理・建物tickが全停止。市民は歩き回るので気づきにくい)。
- 合図: `/debugFarm` の colonyDay が worldDayTime に対して凍結、`/debugBuilder` で
  claim済みWOがあるのに workOrderId=0、builderが `/debugCitizenAI` で canGoIdle:true のまま余暇。
- bridge の keepColoniesActive()(onServerTick内)が無人時に状態機械をACTIVEへ強制して対処済み
  (2026-07-03)。これが壊れた場合は console に "keepColoniesActive failed" が出る。
- 「ユーザーがログインしている時だけ正常」という症状パターンはまずこれを疑う。
