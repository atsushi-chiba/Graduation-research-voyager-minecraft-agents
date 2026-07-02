# LLM 手順書 (skills) の作り方と使い方ガイド

このプロジェクトで LLM コーディングエージェント用の「手順書 = skills」を
どう作り、どう参照させるかのまとめ。(2026-07-02 作成)

## 設計方針: 情報は3層に分ける

| 層 | 役割 | このプロジェクトでの実体 |
|---|---|---|
| メモリ / CLAUDE.md | **事実**(API仕様、ブロックID、落とし穴) | Claude の memory ファイル群 |
| skills | **手順書**(順序と検証込みの多段作業) | `.claude/skills/*/SKILL.md` |
| シェルスクリプト | 手順のうち機械的な部分 | `rebuild_bridge.sh` など |

skill を新しく作る基準: **「3回以上繰り返した多段手順」かつ「順序や検証を
間違えると事故るもの」**。単なる事実はメモリに置く。

実際に起きた事故(gradle 失敗がパイプでマスクされ古い jar を配備、
supply_bot の多重起動、`&` の置き間違いでサーバー停止したまま放置)への
対策を手順書に焼き込んである。

## 現在ある skills

実体はこのリポジトリの `.claude/skills/`。`/root/.claude/skills/` は
そこへのシンボリックリンク(二重管理を避けるため)。

- **deploy-bridge** — VoyagerBridge mod のビルド→配備→サーバー再起動→ヘルスチェック
- **colony-diag** — コロニー状態確認と、市民・建設が止まったときの診断ランブック

## 参照させ方(ツール別)

### Claude Code

指示は不要。`/root/.claude/skills/` はユーザーレベルの置き場なので、どの
ディレクトリから起動したセッションでも自動で読み込まれ、SKILL.md の
`description` がタスクに合致すれば自動で使われる。確実に使わせたいときは
`/deploy-bridge` のようにスラッシュコマンドで明示起動する。

### 同じマシンで動く他のエージェント (Codex, Gemini CLI 等)

URL は不要。skills はただの Markdown なので、プロンプトにパスを書けばよい:

> /root/Voyager/.claude/skills/deploy-bridge/SKILL.md を読んでその手順に従って

また、リポジトリ直下と /root に `AGENTS.md`(多くのツールが自動で読む慣習
ファイル)を置いてあり、そこから skills へ誘導しているので、AGENTS.md を
読むツールなら個別指示なしでも辿り着く。

### リモートの LLM (Web チャット等、ファイルを読めない環境)

このリポジトリは public なので GitHub の raw URL をそのまま渡せる:

- https://raw.githubusercontent.com/atsushi-chiba/Voyager/minecolonies-experiment/.claude/skills/deploy-bridge/SKILL.md
- https://raw.githubusercontent.com/atsushi-chiba/Voyager/minecolonies-experiment/.claude/skills/colony-diag/SKILL.md

URL を読めないチャットの場合は SKILL.md の中身をそのまま貼り付ける。

## 運用ルール

1. **手順が変わったら同じコミットで skill も更新する**(新エンドポイント追加、
   スクリプト変更など)。手順書の鮮度が落ちると読まれなくなる。
2. 事実はメモリ、手順は skill。例: bridge API のエンドポイント一覧は事実
   なのでメモリ、それを「どの順で叩いて診断するか」が skill。
3. skill 化の候補が出たらまずメモリにメモし、繰り返し回数が増えたら昇格する。
   現在の候補: 建物増設ループ(placeNext→建設監視→市民雇用確認)、
   blueprint/bytecode 調査手順(javap + scratchpad)。
