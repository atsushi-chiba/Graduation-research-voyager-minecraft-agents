# エージェント向け指示 (MineColonies bridge 実験)

このリポジトリは Voyager のフォークで、LLM エージェントが MineColonies コロニーを
運営する実験(`minecolonies-experiment` ブランチ)を含む。実験の実体は
`voyager/env/minecolonies-bridge/`(Forge mod + Node.js エージェント群)。

## 手順書 (skills)

定型作業の手順書が `.claude/skills/` にある。**該当する作業を始める前に必ず読むこと**:

- `.claude/skills/deploy-bridge/SKILL.md` — VoyagerBridge mod のビルド→配備→
  サーバー再起動→ヘルスチェック。ビルドや配備をするときはこの手順に従う。
- `.claude/skills/colony-diag/SKILL.md` — コロニー状態確認と、市民・建設が
  止まったときの診断ランブック。

手順書はただの Markdown なので、Claude Code 以外のツール(Codex、Gemini CLI 等)も
ファイルとして読んで従えばよい。

## 基本事実(実験マシン上)

- Minecraft サーバー: `/root/mc-server-forge`(Forge 1.20.1、ゲーム port 25566)
- bridge HTTP API: `http://localhost:8089`(エンドポイント実装は
  `voyager/env/minecolonies-bridge/src/main/java/com/voyagerbridge/VoyagerBridge.java`)
- サーバーコンソール入力: `echo '<cmd>' > /root/mc-server-forge/cmd_pipe`、
  出力は `/root/mc-server-forge/console.log`
- ビルドには JDK17 (`/opt/jdk-17.0.19+10`) が必要

## ルール

- 検証が取れた変更は `minecolonies-experiment` ブランチにコミットし、
  `origin` と `fork` の両方に push する。
- 手順そのものを変えたら、同じコミットで該当 SKILL.md も更新する。
