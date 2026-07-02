---
name: deploy-bridge
description: VoyagerBridge mod のビルド→配備→サーバー再起動→ヘルスチェックの一連手順。minecolonies-bridge の Java コードを変更してサーバーに反映するときに使う。
---

# VoyagerBridge デプロイ手順

前提: ソースは `/root/Voyager/voyager/env/minecolonies-bridge`、サーバーは `/root/mc-server-forge`(Forge 1.20.1-47.1.3, ゲーム port 25566, bridge HTTP port 8089)。

## 手順

1. **ビルド検証**(サーバー稼働中でも可):
   ```bash
   cd /root/Voyager/voyager/env/minecolonies-bridge && set -o pipefail && \
     JAVA_HOME=/opt/jdk-17.0.19+10 ./gradlew build -x test 2>&1 | tail -5
   ```
   `set -o pipefail` は必須。パイプ先の tail の exit code で gradle 失敗がマスクされ、古い jar を配備した事故がある。

2. **サーバー停止**: `bash /root/mc-server-forge/stop_server.sh`

3. **配備**: `bash /root/mc-server-forge/rebuild_bridge.sh`
   (再ビルド+旧 jar 削除+コピーまでやる。サーバー起動中は拒否される)

4. **起動**:
   - 検証セッション: `bash /root/mc-server-forge/start_server.sh --no-agents`(council.js / supply_bot を起動しない)
   - 通常運用: `--no-agents` なしで起動
   - 起動完了待ちは Bash の `run_in_background` + until ループで `curl -s http://localhost:8089/status` をポーリング(foreground の sleep 連鎖は harness にブロックされる)。起動には 60〜90 秒かかる。
   - コマンドを `&` で背景化した壊れたチェーンで「サーバーが落ちたまま」になった事故あり。起動系は必ず単独コマンドで。

5. **ヘルスチェック**:
   - `curl -s http://localhost:8089/status | head -c 300` が colonies JSON を返すこと
   - `grep -iE "error|exception" /root/mc-server-forge/console.log | tail -20`
     (起動直後の `Error loading blueprint: Colonial:blueprints/minecolonies/colonial` は無害 — onPlacement の getCorners が pack/path 設定前に走るだけで、後で自己修復される)
   - supply_bot が**ちょうど1プロセス**か: `pgrep -fa supply_bot.js`
     多重起動事故あり。逆に停止していると creativeresolve=false のため建設が資材待ちで全停止する(--no-agents 起動時は必要に応じて `node /root/Voyager/voyager/env/minecolonies-bridge/supply_bot.js` を背景起動)。

6. **tickrate**: サーバー再起動で 1x に戻るので、起動確認したら**必ず** `echo 'tickrate 10' > /root/mc-server-forge/cmd_pipe` で 10x に戻す(ユーザー指示 2026-07-02: シミュレーションは常に10倍速で回す)。mod 側でキック/ログインタイムアウト抑止済みなのでリアルプレイヤーがいても 10x のままでよい。

## 反映後

- 動作検証が取れたら `minecolonies-experiment` ブランチにコミットし、`origin` と `fork` の**両方**に push する。
