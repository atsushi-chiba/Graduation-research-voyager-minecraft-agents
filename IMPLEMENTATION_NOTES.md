# 実装内容メモ(2026-06-25時点)

`village.js`(4人村づくり、最新かつ最も複雑な実装)を中心に説明する。アーキテクチャは他のスクリプト(`lumber_team.js`, `house_build.js`, `pvp_duel.js`)も基本的に同じパターン。

## 全体の流れ(`village.js` 1ターンのループ)

```
spawnAgent() → bot.spawn → turn(name) ループ(4.5秒ごとに自分自身を再帰呼び出し)
```

各ターンで: 状態をテキスト化 → LLMに送信 → 返答を「発言」と「コード」に分けて解釈 → コード実行、というシンプルな反復構造。

## 1. プロンプトが書かれている場所

| 関数 | 場所 | 内容 |
|---|---|---|
| `buildSystemPrompt(selfName)` | village.js:182-221 | **システムプロンプト本体**。役割(木こり/建築家)ごとに分岐し、チーム構成・チェストの使い方・日本語で話すルール・返答フォーマット(`SAY:`/`CODE:`)を指示。`spawnAgent`内で`agent.history`の最初のメッセージとしてセット(village.js:423) |
| `buildStateContext(bot, selfName)` | village.js:292-318 | **毎ターンのユーザーメッセージ**。座標・インベントリ・チェスト位置・進捗・直近の会話ログを埋め込む `[STATE]` 行 |
| `primitivesDocs` | `control_primitives_context/*.js`を読み込んで連結 | 使える関数(`mineBlock`, `craftItem`, `placeItem`など)の説明をシステムプロンプト末尾に挿入 |

つまりプロンプトはハードコードされた文字列ではなく、**JS側で動的に組み立てて埋め込む**設計。

## 2. エージェントの「動き」を実際に制御している場所

- **`askLLM()`** (village.js:320-361): OpenRouter API(`gpt-5`)にhistory(systemプロンプト+直近10往復)を送って返答を取得
- **`parseReply()`** (village.js:363-369): 返答を正規表現で `SAY:` の文言と ```` ```javascript ```` ブロックのコードに分解
- **`evaluateCode(bot, code)`** (village.js:223-290): LLMが生成したJSコードを実際に動かす場所。`control_primitives/*.js`の生コード文字列 + 村専用ヘルパー(`villagePrimitivesCode`)+ LLM生成コードを**1つの非同期関数として`eval()`**し、最後に`act(bot)`を呼ぶ。45秒のタイムアウトあり

つまりLLMは「自由なJSコードを書く」のではなく、**事前に用意された関数(プリミティブ)を組み合わせて呼ぶだけ**という制約の中で動いている。これがVoyagerのコア発想(スキルライブラリ+コード生成)。

## 3. LLMが使える「道具」(スキル)

- `control_primitives/*.js` — 元のVoyagerが持っている汎用スキル(`mineBlock`, `craftItem`, `placeItem`, `killMob`, `smeltItem`, `useChest`など)。生のコード文字列としてファイルから読み込み、eval内に展開(village.js:34-38)
- `villagePrimitivesCode` (village.js:50-117) — このシナリオ専用に追加したヘルパー。`depositLogsToChest`/`withdrawFromChest`(チェスト排他制御込み)、`buildAssigned`(ブループリント座標へ自動設置、異物除去・再検証ロジック込み)
- `lib/skillLoader.js` — mineflayerの低レベルAPI(`bot.sleep`, `bot.fish`など)に安全ガードを追加するラッパー。`bot.loadPlugin`後に`inject(bot)`で適用(village.js:436)

## 4. 「ゲーム」固有のロジック(LLMには見せない部分)

- `generateBlueprint()` — 家の座標リストをコード側で生成し、各座標を建築家に割り振る(LLMはブループリントの座標計算をしない)
- `isPlacedNow()` / `remainingFor()` / `totalRemaining()` — 進捗判定。キャッシュを信用せず近くのボットの実ブロックで再検証(前回のバグ修正箇所)
- `turn()` (village.js:371-412) — ループの司令塔。完成判定・ターン数/時間制限のチェック、LLM呼び出し、結果実行、次ターンのスケジューリングをすべてここで行う
- `spawnAgent()` (village.js:414-485) — bot生成、プラグイン読み込み、**`bot.chat`の上書き**(ライブラリ内部の英語chatを握り潰し、`sayToChat`経由のLLM発言だけが実際に発声される、前回ログにあったバグ修正の実装箇所)

## 5. MineColonies Bridgeアーキテクチャ(別実験)

`voyager/env/minecolonies-bridge/` 以下に、mineflayerが接続できないForgeサーバーをLLMで操作するための別システムがある。詳細は同ディレクトリの`README.md`を参照。ここでは**他のClaudeが作業を引き継ぐために必要な知識**だけをまとめる。

### ファイル構成

| ファイル | 役割 |
|---|---|
| `VoyagerBridge.java` | Forge Mod本体。HTTP API(port 8089)を生やしてサーバーJVM内から直接MineColonies操作 |
| `council.js` | 複数のLLM「知事」ペルソナが交代でコロニー運営方針を議論・実行するメインループ |
| `supply_bot.js` | council.jsと並走し、全市民のオープンリクエストを6秒ごとに自動解決するサポートスクリプト |
| `building_registry.json` | block_id → Colonialパック blueprint パスのマッピング表 |

### Domum Ornamentum フレーム付きブロックの取り扱い(重要)

MineColoniesのblueprintには `domum_ornamentum:framed` のような**テクスチャ付き装飾ブロック**が含まれる。これは通常のアイテムと異なり、ItemStackのNBTトップレベルに `textureData` CompoundTag(テクスチャパス→素材ブロックのマッピング)を持つ。

建築家NPCがこのブロックをリクエストしたとき、`/resolveRequest` エンドポイントが行う正しい手順：
1. `/openRequests` の `materials[]` 配列から必要な**素材アイテム**を特定
2. `giveToCitizen` で素材を市民インベントリに投入
3. `/resolveRequest` を呼ぶ → 内部で素材を消費し `textureData` 付きの完成品をインベントリに挿入してからリクエストを OVERRULED にマーク

**やってはいけないこと:** `overruleNextOpenRequestOfCitizenWithStack()` はリクエスト状態を変えるだけで完成品をインベントリに入れない。完成品を入れずにこれだけ呼ぶと、建築家AIは「アイテムが手元にない」と判断して同一リクエストを再発行し続ける無限ループになる。(2026-07-01修正済み: `VoyagerBridge.java` の `handleResolveRequest` で素材消費後に `inv.insertItem` を追加)

### サーバー運用メモ

```bash
# Forgeサーバー起動(cmd_pipeの常駐書き込みプロセスも同時に立ち上げる)
cd /root/mc-server-forge
nohup bash -c 'tail -f /dev/null > cmd_pipe & bash run.sh nogui < cmd_pipe >> console.log 2>&1' > /dev/null 2>&1 &

# Bridgeのビルド(JDK17必須、JDK21では動かない)
cd /root/Voyager/voyager/env/minecolonies-bridge
JAVA_HOME=/opt/jdk-17.0.19+10 ./gradlew build -x test
cp build/libs/voyagerbridge-0.1.0.jar /root/mc-server-forge/mods/
# その後サーバー再起動

# council + supply_bot の起動
cd /root/Voyager/voyager/env/minecolonies-bridge
node supply_bot.js &
OPENROUTER_API_KEY=... node council.js
```

### なぜ `cmd_pipe` への書き込みに O_NONBLOCK が必要か

cmd_pipe はLinuxの名前付きFIFO。`tail -f /dev/null > cmd_pipe &` の常駐プロセスが死ぬと、次の書き込みプロセスが reader が来るまで永遠にブロックする。`O_NONBLOCK` フラグで開くと reader 不在時に即 `ENXIO` を返すので、council.jsのループが詰まらない。

---

## まとめ図

```
[システムプロンプト: buildSystemPrompt]  ←役割・ルール・利用可能関数の説明
        +
[毎ターンの状態: buildStateContext]      ←座標/持ち物/進捗/直近チャット
        ↓
   askLLM() → OpenRouter(gpt-5)
        ↓
parseReply() → { say, code }
        ↓
sayToChat(say)          evaluateCode(code) → eval(primitives + village専用helper + LLMコード + act(bot))
        ↓                          ↓
  実際のMinecraftチャット      実際のbot操作(移動/採掘/設置/クラフト/チェスト)
```

他のファイル(`lumber_team.js`, `house_build.js`, `pvp_duel.js`)もほぼ同型の構造で、システムプロンプトの内容とシナリオ専用ヘルパー(`villagePrimitivesCode`相当)だけが差分。
