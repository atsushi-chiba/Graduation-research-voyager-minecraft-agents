# 市民ペルソナシステム — 設計決定書 (2026-07-17 方針セッションで確定)

ユーザーと詰めた設計決定。**並列エージェント実装の仕様書**。
上位方針: 研究目標③(SNS発言)の前段 + 目標①(戦争)の下ごしらえ。
前提: 通常ワールドコロニー(id=1)稼働中、bridge API はメモリ minecolonies-bridge-api 参照。

## 確定した設計決定

| # | 論点 | 決定 |
|---|---|---|
| P-D1 | ペルソナの単位 | 市民1人=1ペルソナ。**セグメント(遺伝子区画)に分割**して保持 |
| P-D2 | 初期世代 | **手動定義**(テンプレートを人間+operatorで作る)。以後は出生時に遺伝 |
| P-D3 | 遺伝方式 | **セグメント単位で両親からランダム継承(交叉)+低確率の突然変異**。LLMは遺伝に使わない |
| P-D4 | 適応度(fitness) | **MineColonies の happiness を参照**(親の幸福度が高いほどそのセグメントが選ばれやすい重み付き交叉)。将来は貨幣・資産制度を導入したら資産ベースに差し替え可能な設計にする |
| P-D5 | 保存先 | **Node.js 側 JSON**(`personas.json`)。bridge には持たせない |
| P-D6 | 行動への反映(MVP) | ①職業配属(選好) ②忠誠度・政治スタンス(数値保持のみ) ③つぶやき生成 ④状況対応行動(下記) |
| P-D7 | 状況対応行動 | **疑似演出ではなく実際に動く**。聞いた側の市民がペルソナで判断し、bridge の移動/戦闘指示APIで本当に行動する |
| P-D8 | 援軍要請 | 要請側が**人数と行動内容を指定**し、**全体ブロードキャスト(全体ツール)で座標を伝える**。受けた各衛兵がペルソナ(忠誠・勇敢さ・従順さ・距離)で応じるか判断 |
| P-D9 | 判断の実行主体 | 緊急時判断は **LLM(メニュー選択方式、council と同様)**。タイムアウト時はペルソナ傾向値の重み付きサンプリングにフォールバック。発言(つぶやき)は常にLLM |

### P-D1 の細目: ペルソナのセグメント構造(personas.json)

```json
{
  "citizenId": 12,
  "name": "...",
  "generation": 0,
  "parents": [],
  "segments": {
    "jobPreference":   { "liked": ["farmer","cook"], "disliked": ["miner"] },
    "temperament":     { "bravery": 0.7, "empathy": 0.4, "obedience": 0.8,
                         "greed": 0.2, "sociability": 0.6 },
    "politics":        { "loyalty": 0.9, "ambition": 0.3 },
    "combatResponse":  { "evacuateCivilians": 0.2, "engage": 0.5,
                         "callReinforcements": 0.2, "betray": 0.1 },
    "speechStyle":     { "tone": "ぶっきらぼう", "verbosity": 0.3 }
  }
}
```

- セグメント= 遺伝の最小単位(`jobPreference` を丸ごと親Aから、`temperament` を丸ごと親Bから、のように継承)。
- 突然変異: セグメント継承後に確率 ~10% で数値をガウスジッタ or 選好を1つ入替え。
- `politics` は当面**保持のみ**(戦争フェーズで参照)。ただし P-D8 の援軍judgeでは loyalty を既に使う。

### P-D3/P-D4 の細目: 遺伝の流れ

1. 出生検知: `/status` の citizens 差分を Node 側デーモンがポーリング(新 citizenId の出現)。
2. 親の特定: MineColonies が親子関係を保持しているか **P0 で調査**。保持していれば bridge から返す。
   **保持していなければ**、遺伝デーモンが「同コロニーの成人からhappiness重み付きで2名抽選」して遺伝上の親とする
   (この場合も選択圧はhappiness経由でかかる)。
3. 子ペルソナ生成: セグメントごとに親A/Bから選択。選択確率 = 両親のhappinessの比(高い方が選ばれやすい)。
4. `personas.json` に世代番号・親IDと共に追記。全履歴が家系図として残る(将来のwiki可視化素材)。

注: 本方式は正確には**遺伝的アルゴリズム(GA)の重み付き交叉**。CMA-ES(共分散行列適応)は連続パラメータ
(temperament等)に将来適用する拡張余地として残す。論文表記は「happiness を適応度とする進化的手法」が安全。

### P-D7/P-D8 の細目: 状況対応行動(衛兵シナリオ)

```
1. bridge /threats が敵(hostile mob / raid)をコロニー領域内に検知
2. persona_reactor.js が検知 → 近傍の衛兵・市民ごとに LLM 判断
   (入力: ペルソナ + 脅威の位置/種類/数 + 自分の位置/装備、出力: メニュー選択)
   衛兵の選択肢: 応戦 / 市民に避難を呼びかけ / 援軍要請(人数・行動・座標) / 裏切り(素通り)
3. 選択の実行:
   - 応戦        → /guardOrder?mode=engage&x&y&z (脅威座標へ移動+交戦)
   - 避難呼びかけ → 全体ツールに避難ブロードキャスト → 聞いた市民が各自のペルソナで
                   逃げる(/moveCitizen で town hall 等へ)か無視するか判断
   - 援軍要請    → 全体ツールに {count, action, x,y,z} を投稿 → 各衛兵が応諾判断 →
                   応諾者を /guardOrder で座標へ
   - 裏切り      → /guardOrder?mode=standdown (その場に留まり交戦しない。bridge が
                   攻撃ターゲットを継続的にクリアして実現)
4. 全ての判断・発言はゲーム内チャット(cmd_pipe say/tellraw)にミラー + JSONL ログ
```

- **全体ツール** = persona_reactor 内のメッセージバス。ゲーム内チャットへのミラーで観察可能にする。
- 料理人の「パン人気→増産」(消費統計→/setMenu調整)は同じ reactor 基盤の上に**後続タスク**として載せる(MVP対象外)。

## 並列エージェントのタスク仕様

各エージェントは隔離 worktree・狭スコープ・コード生成のみ。**ライブ系に触らない**。
operator が受け取り直列にデプロイ(bridge Java 変更は deploy-bridge skill)。

### Agent P0 — bridge 拡張(Java)【基盤・最リスク・先行】
- スコープ: VoyagerBridge に以下を追加。
  - `GET /threats?colonyId=1` — コロニー領域内の hostile エンティティ一覧(種類/座標/数) + raid イベント状態。
  - `POST /moveCitizen?colonyId&citizenId&x&y&z` — 市民を指定座標へ実移動(navigation直叩き)。
    MineColonies AI との競合対策(一時 pause / AI割り込み)を調査して実装。到着 or タイムアウトで解放。
  - `POST /guardOrder?colonyId&citizenId&mode=engage|standdown|return&x&y&z` — 衛兵への行動指示。
    engage=座標へ移動して交戦(rally banner 機構 or guard task 直接設定を調査)、
    standdown=その場で非交戦(攻撃ターゲットをtickでクリア)、return=通常勤務へ復帰。
  - 親子関係の調査: MineColonies が出生時に親を記録するか確認し、あれば `/status` citizens に `parents` を追加。
    なければ「なし」と報告(遺伝デーモン側で抽選する)。
- 受け入れ: テスト用手順書付き(zombie召喚→/threats反映、/moveCitizenで市民が実際に歩く、
  /guardOrderの3モードが目視確認できる)。Mojangマッピング。ビルドが通ること(デプロイはoperator)。

### Agent P1 — ペルソナ基盤 + 遺伝デーモン(Node)【独立・即着手可】
- スコープ: `personas.js`(スキーマ/読み書きユーティリティ) + `persona_daemon.js`(常駐)。
  - 初期世代ジェネレータ: 既存市民全員に手動定義テンプレート群からペルソナを割り当て(operator が実行)。
  - 出生検知(status差分ポーリング)→ P-D3/P-D4 の重み付き交叉+突然変異で子ペルソナ生成。
  - 死亡検知 → ペルソナに deceased マーク(削除しない=家系図保存)。
  - すべて personas.json に永続化。ログは persona_daemon.log。
- 受け入れ: モックの status JSON を食わせた単体テストで交叉・突然変異・世代番号が正しい。実colonyへの接続は operator。

### Agent P2 — council 職業配属にペルソナ反映(Node)【P1 依存】
- スコープ: council.js / 職業スキルマッチャー(/autoAssignJobs 周辺)の配属スコアに
  personas.json の jobPreference を加点/減点として合成。市長プロンプトにも候補市民の選好を注釈。
- 受け入れ: 選好が同点候補の順位を入れ替えることをテストで確認。ペルソナ未定義市民は従来挙動。

### Agent P3 — つぶやき生成デーモン(Node)【P1 依存・P0 不要】
- スコープ: `persona_voice.js`。イベント(職業変更/空腹/建物完成/脅威/怪我) × ペルソナ(speechStyle, temperament)
  → ollama で短文発言を生成 → `tweets.jsonl` に追記 + ゲーム内チャットへミラー。
  レート制限(1市民あたり毎分N件まで)とイベントの間引きを入れる。
- 受け入れ: モックイベントで口調がペルソナごとに変わる。ollama 停止時は静かにスキップ。

### Agent P4 — persona_reactor(状況対応行動)(Node)【P0+P1 依存・最後】
- スコープ: `persona_reactor.js`。/threats ポーリング → P-D7 のフロー実装。
  LLM 判断は council と同じ「メニュー選択+スキーマ強制+think:false」方式。タイムアウト時は
  combatResponse の重み付きサンプリング。全体ツール(メッセージバス+チャットミラー+JSONL)。
- 受け入れ: zombie 襲撃シナリオで、ペルソナの異なる衛兵が異なる対応(応戦/避難呼びかけ/援軍/裏切り)を
  実際の移動を伴って行い、ログで追跡できる。

## operator(統合セッション)が直列でやること
1. P0 受領 → deploy-bridge でビルド・配備・再起動 → /threats・/moveCitizen・/guardOrder を実測検証。
2. P1 受領 → 初期ペルソナテンプレートをユーザーと相談して確定 → 全市民に割り当て → persona_daemon 起動。
3. P2/P3 受領 → council 再起動・persona_voice 起動。
4. P4 受領 → zombie 襲撃の統合テスト → チューニング。
5. 節目ごとに progress report + wiki 更新、両リモート push。

## 未決・将来
- 貨幣・資産制度(fitness の差し替え先) — 制度設計から別途。
- 料理人のメニュー需要応答(消費統計→/setMenu) — reactor 基盤の後続タスク。
- 家系図・ペルソナ分布の wiki 可視化 — データが溜まってから。
- OASIS 連携(目標③本体) — tweets.jsonl をソースにする。
