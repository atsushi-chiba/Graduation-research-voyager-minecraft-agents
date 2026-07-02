# 進捗報告：JarvisVLAロールアウト基盤の修復

## 1. 課題の背景と目的

JarvisVLAのロールアウト評価（`jarvisvla/evaluate/evaluate.py`）を`minestudio`環境で動かそうとしたところ、

```
from minestudio.models.shell.gui_agent import GUIWorker
ModuleNotFoundError
```

で起動できない状態だった。`GUIWorker`はVLM（vLLMサーバー経由）の出力をMinecraftのGUI操作（インベントリ・クラフト台・かまどの開閉、マウス移動、アイテムのドラッグ等）に変換する中核クラスで、これが無いと`craft`/`smelt`系タスクのロールアウトが一切実行できない。

本セッションの目的は、ロールアウトが最後まで完走する状態まで基盤を修復し、複数タスク種別（討伐・採掘・クラフト・精錬）で動作確認することだった。

## 2. 実施した内容

### 2.1 GUIWorker欠落の原因調査

PyPI公開済みの`minestudio`全バージョン（1.0.0〜1.1.6）を調査したが、`models.shell`というモジュールはどのバージョンにも存在しないことを確認した。一方、リポジトリ内の`jarvisvla/evaluate/env_helper/gui_agent.py`に**実装そのものは既に存在**しており、`craft_agent.py`（`CraftWorker`）がこれを継承する形で書かれていた。

つまり今回の問題は「実装が無い」のではなく、「`minestudio.models.shell.gui_agent`という固定importパスにファイルを配置する手順が抜けていた」ことが原因だった。`smelt_agent.py`も同様に`minestudio.models.shell.craft_agent`から`CraftWorker`をimportする設計になっていた。

### 2.2 インストールスクリプトの追加

import パスを変更せずに済むよう、インストール済みパッケージ側にファイルを配置するスクリプトを追加した。

```
scripts/setup/install_minestudio_shell.sh
```

- `minestudio/models/shell/__init__.py` を作成
- `jarvisvla/evaluate/env_helper/gui_agent.py` → `minestudio/models/shell/gui_agent.py` にコピー
- `jarvisvla/evaluate/env_helper/craft_agent.py` → `minestudio/models/shell/craft_agent.py` にコピー

これにより`pip install -e .`後、誰でも同じ手順で再現可能になった。

### 2.3 MineStudio APIとの不整合の修正

GUIWorkerのimportが通った後、実際にロールアウトを走らせる過程で2件のAPI不整合が見つかった。

| # | 箇所 | 問題 | 修正 |
|---|---|---|---|
| 1 | `InitInventoryCallback` 呼び出し | 旧版の`inventory_distraction_level`/`equip_distraction_level`引数を渡していたが、現行MineStudioでは単一の`distraction_level`引数に統一されており`TypeError`で即落ち | `evaluate.py`・`craft_agent.py`・`smelt_agent.py`の呼び出しを`distraction_level=`に統一 |
| 2 | `CommandsCallback` | `after_reset()`がコマンド0件でも`sim._wrap_obs_info(obs, info)`を無条件実行し、既にラップ済みのobsを再ラップして`KeyError: 'pov'`になる（`command`設定の無いタスク全般で発生） | `evaluate.py`で`command`が空の場合は`CommandsCallback`自体を追加しないように変更 |

### 2.4 ヘッドレス環境対応

このマシンには`DISPLAY`が設定されておらず、Minecraft（Malmo）プロセスへのミッション送信が`reply=None`で失敗する状態だった。`xvfb-run`は導入済みだったため、各ロールアウトスクリプトに「`DISPLAY`未設定時は自動で`xvfb-run -a`を付与する」ガードを追加した（`rollout-kill.sh` / `rollout-mine.sh` / `rollout-gui.sh` / `rollout_coord.sh`）。

## 3. テスト結果

4タスク種別で動作確認を行った（vLLMサーバー: `CraftJarvis/JarvisVLA-Qwen2-VL-7B`、port 11000）。

| タスク | worker | 主目的 | 結果 |
|---|---|---|---|
| `kill/kill_zombie` | なし | importとロールアウト一周の確認 | ✅ 50フレームで完走 |
| `mine/mine_stone` | `mine` | コマンド無しタスクでの`CommandsCallback`修正確認 | ✅ 50フレームで完走 |
| `craft/craft_crafting_table` | `craft` | `GUIWorker`/`CraftWorker`の実動作確認 | ✅ 50フレームで完走 |
| `smelt/iron_ingot` | `smelt` | `open_furnace_wo_recipe()`を含む重い処理の確認 | ✅ 基盤的には完走（後述） |

`smelt/iron_ingot`は当初`--max-frames 400`・`--history-num 4`・`--instruction-type recipe`（クラフト系タスク向けの想定設定）で実行したが、**鉄インゴットの精錬自体は400ステップ以内に完了しなかった**（reward未発火、早期break無し）。基盤（GUIWorker・各種コールバック）は最後までエラーなく動作したため、これはインフラのバグではなく、与えたステップ数の中でモデルが精錬タスクをやり切れなかったという結果。

## 4. 未解決の課題

- **`iron_ingot`タスクの未完走**: 400ステップでは精錬完了に届かなかった。ステップ数を増やす（800〜1000程度）か、`temperature`・`instruction_type`の調整余地がある。
- **`can't set up init inventory`警告**: `inventory_distraction_level: "zero"`設定時に毎回出力される。致命的ではないが原因未調査。
- **`rollout-mine.sh`/`rollout-gui.sh`の設定が古い**: `base_url`（port 9012）と`model_local_path`（`mc-vla-qwen2-vl-7b-...`）がこの環境には存在しない値のまま。今回は直接`evaluate.py`を正しい値で叩いて検証した。
- **`rollout_coord.sh`が参照する`collect/collect_log`タスクが未定義**: `jarvisvla/evaluate/config`に存在せず、現状のまま実行すると失敗する。
- **`craft_agent.py`内のレシピパス解決ロジックの脆さ**: `cur_path.find('minestudio')`という文字列マッチでルートパスを決めており、`minestudio/models/shell/`配下に置かれた場合のみ正しく動く設計（今回の配置で機能する）。今後ファイル配置を変えると静かに壊れる可能性がある。

## 5. 今後の計画

### 短期
1. `rollout-mine.sh`/`rollout-gui.sh`の`base_url`・`model_local_path`をこの環境用に更新する
2. `rollout_coord.sh`の`collect/collect_log`タスクconfigを追加する、または既存タスクに差し替える
3. `iron_ingot`をより長いステップ数（800〜1000）で再テストし、精錬完了するか確認する

### 中期
4. `can't set up init inventory`警告の原因調査
5. `craft_agent.py`のレシピパス解決をファイル位置に依存しない実装へ改善

## 6. 変更ファイル一覧（コミット）

| commit | 内容 |
|---|---|
| `c461637` | `minestudio.models.shell`のインストールスクリプト追加 |
| `13801c8` | `InitInventoryCallback`呼び出しを現行APIに修正 |
| `c8e6125` | コマンド無しタスクで`CommandsCallback`が`obs`を壊す不具合を修正 |
| `e82940b` | `DISPLAY`未設定時に各ロールアウトスクリプトが`xvfb-run`を自動使用するよう修正 |
