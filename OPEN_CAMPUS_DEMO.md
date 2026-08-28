# オープンキャンパス展示手順（2026-08-30）

## ブランチの使い分け

- `codex/open-campus-pre-persona-2026-08-30`: 最優先。既存commit `4c9a7fc`をそのまま指す無変更の復元点。
- `codex/open-campus-demo-fallback-2026-08-30`: 別PCのパス差と起動プロセス回収へ対応する最小fallback。研究機能は追加していない。

最初は必ず無変更ブランチを試す。Bridgeのビルド、起動、`/ping`のいずれかが環境差で失敗した場合だけfallbackへ切り替える。

## 必要な既存環境

- Minecraft 1.20.1
- Forge runtime `1.20.1-47.1.30`（実験機の実稼働版）。BridgeのGradleビルド対象は`47.1.3`で、依存範囲はForge 47系
- MineColonies `1.20.1-1.1.1231`
- Structurize `1.20.1-1.0.816`
- Domum Ornamentum `1.20.1-1.0.296`
- JDK 17、Bash、curl、Git。Node.jsはhealth summary表示に使用するが、ペルソナ関連daemonは起動しない
- 実験機と同等のForge server、EULA同意済み設定、MineColonies world

VoyagerBridgeは`displayTest=IGNORE_ALL_VERSION`なのでクライアントへの同梱は不要。クライアント側にはForge、MineColoniesとその依存modをserverと同じversionで用意する。

world、Minecraft本体、Forge、MineColonies jarはこのGitブランチに含まれない。研究室PCで既存環境として配置する。

## cloneと無変更ブランチの確認

```bash
git clone https://github.com/atsushi-chiba/Graduation-research-voyager-minecraft-agents.git
cd Graduation-research-voyager-minecraft-agents
git checkout codex/open-campus-pre-persona-2026-08-30
git rev-parse HEAD HEAD^{tree}
```

期待値:

```text
4c9a7fc2d28fb3bd72b709d3ba4325eb47efc431
157bbe7b1bab7bcf62e58d8e0b8cd30b094e781e
```

## 無変更ブランチを最初に試す

無変更版はcompile-time依存jarを`/root/mc-server-forge/mods`から読む。研究室PCも同じ配置なら次でビルドする。

```bash
cd voyager/env/minecolonies-bridge
JAVA_HOME=/path/to/jdk17 ./gradlew build -x test
```

server停止中に、既存Bridge JARをserver外へコピーしてから、生成物`build/libs/voyagerbridge-0.1.0.jar`をForge serverの`mods/`へ配置する。既存の`start_server.sh`をagentなしで起動する。

```bash
FORCELOAD_CX=501 FORCELOAD_CZ=-319 FORCELOAD_R=80 \
  bash /root/mc-server-forge/start_server.sh --no-agents
curl -sS http://localhost:8089/ping
curl -sS http://localhost:8089/status
```

起動コマンドの終了直後にJavaも終了する実行環境では、次のfallbackへ切り替える。

## fallbackへの切替

serverを完全に停止してから切り替える。dirtyな作業ツリーで行わない。

```bash
git checkout codex/open-campus-demo-fallback-2026-08-30
export DEMO_SERVER_DIR=/path/to/forge-server
export DEMO_MODS_DIR="$DEMO_SERVER_DIR/mods"
export DEMO_JAVA_HOME=/path/to/jdk17
export DEMO_GAME_HOST=研究室PCのIPアドレス
```

実験world `NormalActual`を使う場合の既知座標:

```bash
export DEMO_FORCELOAD_CX=501
export DEMO_FORCELOAD_CZ=-319
export DEMO_FORCELOAD_R=80
export DEMO_FORCELOAD_MIN_X=393
export DEMO_FORCELOAD_MIN_Z=-434
export DEMO_FORCELOAD_MAX_X=654
export DEMO_FORCELOAD_MAX_Z=-210
```

別worldでは、そのコロニーの中心と全建物境界へ置き換える。矩形は256チャンク以内にする。

```bash
./demo_preflight.sh
./demo_start.sh
./demo_healthcheck.sh
```

`demo_start.sh`はJDK 17でビルドし、既存Bridge JARを`$DEMO_SERVER_DIR/open-campus-demo-backups/`へ退避して配備する。Forgeは`--no-agents`かつ独立sessionで起動し、Bridge ready後にadaptive tickrateを有効化する。停止だけして再実行した場合も、最初に記録した元Bridgeのbackupを保持する。`--restore`完了後の次回起動では、その時点のBridgeを新たにbackupする。

## 正常表示例

```text
{"status":"ok"}
OK   colony=NormalActual id=1 citizens=35 buildings=68
OK   exactly one Forge process: 12345
OK   persona/social/supply/council daemons are not running
READY Minecraft client: 研究室PCのIPアドレス:25566
```

Minecraftクライアントの「マルチプレイ」から`研究室PCのIPアドレス:25566`へ接続する。serverと同じMinecraft/Forge/MineColonies依存versionを使う。

## 5〜10分の展示台本

1. Minecraftでコロニー全景と働く市民を1〜2分見せる。これはMineColonies標準AIの生活・勤務であると説明する。
2. terminalで`curl -sS http://localhost:8089/ping`を実行し、Bridgeが稼働していることを示す。
3. `./demo_healthcheck.sh`を実行し、コロニー名、市民数、建物数を示す。
4. `curl -sS http://localhost:8089/status`の先頭部分を見せ、市民・建物をHTTPで観測できると説明する。
5. world資産を変えない安全操作として、tickrateを一時1倍へ切り替え、APIで差を確認する。

   ```bash
   curl -sS http://localhost:8089/tickrate
   curl -sS -X POST 'http://localhost:8089/tickrate?multiplier=1'
   curl -sS http://localhost:8089/tickrate
   curl -sS -X POST 'http://localhost:8089/tickrate?auto=true'
   ```

6. Minecraft画面へ戻り、市民が通常AIで動き続けることを示す。最後にadaptiveへ戻ったことを`GET /tickrate`で確認する。

## よくある失敗と復旧

| 症状 | 分類・対処 |
|---|---|
| GradleがMineColonies jarを見つけない | パス差。fallbackで`DEMO_MODS_DIR`を設定する |
| Java versionエラー | `DEMO_JAVA_HOME`をJDK 17へ設定する |
| 起動完了直後にJavaが消える | 親processによる回収。fallbackの`demo_start.sh`を使う |
| Bridge JARの配備先が違う | `DEMO_MODS_DIR`または`DEMO_DEPLOY_JAR`を確認する |
| `8089`が使用中 | 既存Bridge/Forgeの二重起動を停止する。大規模なport変更は展示直前に行わない |
| `/ping`失敗、Forgeは動く | `open-campus-demo-start.log`とserver consoleを確認し、mod versionと配置を確認する |
| `/status`が空 | MineColonies worldの`level-name`、world配置、colony有無を確認する |
| クライアント接続失敗 | game port `25566`、firewall、client/serverのForge・MineColonies依存versionを確認する |
| Restaurant sitting positionエラー | 既知のblueprint問題。Bridge起動失敗ではない |

Bridge操作が失敗してもMinecraft/MineColonies単体の通常動作を見せる。HTTPも失敗した場合は、この文書の正常表示例と事前に個人情報を含まないよう保存した画面を説明に使う。Forge自体が起動しない場合に備え、展示前にコロニー全景、`/ping`、healthcheck結果のスクリーンショットを研究室PCで取得しておく。

## 2026-08-29の実験機検証

- 無変更版とfallback版の両方でJDK 17ビルド成功。
- Forge `1.20.1-47.1.30`、MineColonies `1.20.1-1.1.1231`の既存worldで`/ping`成功。
- `/status`で`NormalActual`、35市民、67建物を取得。
- Forgeは1プロセス、展示対象外daemonは0プロセス。
- tickrateの`auto → 1倍固定 → auto`切替と復元を確認。
- `demo_start.sh`の独立起動、`demo_stop.sh --restore`の正常停止・JAR hash一致を確認。
- MinecraftクライアントGUIからの実接続とスクリーンショット取得はこの作業環境では未検証。研究室PCで展示前に確認する。

## 停止と元Bridgeへの復元

通常停止:

```bash
./demo_stop.sh
```

停止後に、`demo_start.sh`が退避した元Bridge JARへ戻す:

```bash
./demo_stop.sh --restore
```

`--restore`は記録されたbackupが指定backup directory内にある場合だけ上書き復元する。元JARが存在しなかった環境では自動削除せず、対象パスを表示する。worldやserver logはGitへ追加しない。

## 展示対象外

ペルソナ、appraisal、感情、動的trust、gratitude、resentment、obligation、市民間援助、社会情報伝播、LLM社会判断は研究中で係数や評価条件が未固定のため展示しない。`persona_daemon.js`、`social_observer.js`、`social_help_daemon.js`、`social_information_daemon.js`、`supply_bot.js`、`council.js`は起動しない。
