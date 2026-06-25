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
