# 進捗報告 2026-08-31（オープンキャンパス展示準備）

## 1. 目的

2026-08-30の学校オープンキャンパス向けに、詳細認知・感情・動的社会関係・市民間援助を展示対象から外し、既存の安定状態を別PCで再現できる二段階のデモ経路を用意する。

## 2. 実施内容

- `4c9a7fc`を一切変更しない`codex/open-campus-pre-persona-2026-08-30`を作成した。
- 上記から`codex/open-campus-demo-fallback-2026-08-30`を分岐した。
- fallbackではBridgeのcompile-time依存jarディレクトリを`DEMO_MODS_DIR`で上書き可能にした。
- preflight、healthcheck、独立起動、正常停止・元JAR復元スクリプトと展示手順書を追加した。
- 起動は常に`--no-agents`とし、persona/social/supply/council daemonを展示対象外に固定した。

## 3. 検証結果

- 無変更branchのcommit/tree hashは`4c9a7fc`と完全一致し、三点diffは空。
- JDK 17によるBridge buildは無変更版・fallback版とも`BUILD SUCCESSFUL`。
- `4c9a7fc`時点のNodeテストは17 + 7 + 8件すべてPASS。
- Forge `1.20.1-47.1.30` / MineColonies `1.20.1-1.1.1231`で`/ping`成功。
- `/status`で`NormalActual`、35市民、67建物を取得。
- Forge 1プロセス、展示対象外daemon 0、adaptive tickrate復元を確認。
- fallbackのbuild→JAR backup→独立起動→health→正常停止→JAR復元を一巡し、復元hashが一致した。
- `git diff --check`と全shell scriptの`bash -n`はPASS。

## 4. 現在のライブ状態

- 検証終了後、Forge/Bridgeは正常停止。persona/social/supply/council daemonも停止中。
- serverのBridge JARは検証前の現行版へhash一致で復元済み。
- world、server log、runtime JSON、依存mod jarはcommitしていない。

## 5. 未解決事項

- MinecraftクライアントGUIからの実接続は作業環境では未検証。研究室PCで展示前に行う。
- 研究室PCの実パス、firewall、既存world配置は現地確認が必要。
- Restaurantのsitting positionエラーは既知のMineColonies blueprint問題で、今回の対象外。

## 6. 次の一週間

1. 研究室PCで無変更branchを最初に試す。
2. パス差またはprocess回収がある場合だけfallbackへ切り替える。
3. Minecraft接続、5〜10分台本、停止・復元を展示前に一巡する。
4. 個人情報を含まないコロニー全景とhealthcheckの予備スクリーンショットを取得する。

## 7. 主要コミット

- `4c9a7fc` — 無変更デモbranchの復元点。
- `demo: prepare open campus fallback` — fallbackの可搬化、運用補助、展示手順（本報告と同一commit）。
