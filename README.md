> **This repository has moved.** Active development now continues at the
> proper GitHub fork: **[atsushi-chiba/Voyager](https://github.com/atsushi-chiba/Voyager)**
> (forked directly from [MineDojo/Voyager](https://github.com/MineDojo/Voyager),
> so the fork network/attribution is preserved on GitHub). This repo is kept
> read-only for its commit/issue history.

# Voyager: An Open-Ended Embodied Agent with Large Language Models
<div align="center">

[[Website]](https://voyager.minedojo.org/)
[[Arxiv]](https://arxiv.org/abs/2305.16291)
[[PDF]](https://voyager.minedojo.org/assets/documents/voyager.pdf)
[[Tweet]](https://twitter.com/DrJimFan/status/1662115266933972993?s=20)

[![Python Version](https://img.shields.io/badge/Python-3.9-blue.svg)](https://github.com/MineDojo/Voyager)
[![GitHub license](https://img.shields.io/github/license/MineDojo/Voyager)](https://github.com/MineDojo/Voyager/blob/main/LICENSE)
______________________________________________________________________


https://github.com/MineDojo/Voyager/assets/25460983/ce29f45b-43a5-4399-8fd8-5dd105fd64f2

![](images/pull.png)


</div>

We introduce Voyager, the first LLM-powered embodied lifelong learning agent
in Minecraft that continuously explores the world, acquires diverse skills, and
makes novel discoveries without human intervention. Voyager consists of three
key components: 1) an automatic curriculum that maximizes exploration, 2) an
ever-growing skill library of executable code for storing and retrieving complex
behaviors, and 3) a new iterative prompting mechanism that incorporates environment
feedback, execution errors, and self-verification for program improvement.
Voyager interacts with GPT-4 via blackbox queries, which bypasses the need for
model parameter fine-tuning. The skills developed by Voyager are temporally
extended, interpretable, and compositional, which compounds the agent’s abilities
rapidly and alleviates catastrophic forgetting. Empirically, Voyager shows
strong in-context lifelong learning capability and exhibits exceptional proficiency
in playing Minecraft. It obtains 3.3× more unique items, travels 2.3× longer
distances, and unlocks key tech tree milestones up to 15.3× faster than prior SOTA.
Voyager is able to utilize the learned skill library in a new Minecraft world to
solve novel tasks from scratch, while other techniques struggle to generalize.

In this repo, we provide Voyager code. This codebase is under [MIT License](LICENSE).

---

# 卒業研究: マルチエージェントMinecraftデモ(このフォーク独自の内容)

このフォークは本家Voyagerに、**複数のLLMエージェントが日本語で会話しながら協力/対立してMinecraft内のタスクをこなすデモ**を追加したものです。本家のAzureログイン/単一エージェント学習ループとは別に、`voyager/env/mineflayer/` 配下に独立したNode.jsスクリプト群があり、こちらが今回の研究で実際に動かしている実装です。

詳しい設計(プロンプトの場所、LLMコードの実行方法など)は [`IMPLEMENTATION_NOTES.md`](IMPLEMENTATION_NOTES.md) を、これまでの作業履歴は [`WORK_LOG_2026-06-24.md`](WORK_LOG_2026-06-24.md) を参照してください。

## 必要なもの

- Node.js ≥ 16(本家と同じ)
- Minecraft Java版サーバー本体(`server.jar`)。バニラ/Paperどちらでも可。今回の検証は **1.19** で実施
- [OpenRouter](https://openrouter.ai/) のAPIキー(本家はOpenAI直叩きですが、このフォークはOpenRouter経由でLLMを呼ぶように改造済み。`voyager/agents/*.py` 内で `os.environ["OPENROUTER_API_KEY"]` を参照)

## セットアップ手順

### 1. リポジトリをclone

```bash
git clone git@github.com:atsushi-chiba/Graduation-research-voyager-minecraft-agents.git
cd Graduation-research-voyager-minecraft-agents
```

### 2. Node.js依存をインストール

```bash
cd voyager/env/mineflayer
npm install
cd mineflayer-collectblock
npx tsc
cd ..
```

### 3. Minecraftサーバーを用意する

公式サイトから `server.jar` を入手し、適当なディレクトリ(例: `~/mc-server/`)に置く。

```bash
mkdir -p ~/mc-server && cd ~/mc-server
# server.jar をここに配置
echo "eula=true" > eula.txt
```

`server.properties` に以下を設定(オフラインで複数のbotユーザー名を自由に使えるようにするため、また検証を地形に左右されないようにするため):

```properties
online-mode=false
level-type=minecraft\:flat
gamemode=survival
difficulty=peaceful
spawn-protection=16
```

> **注意**: `spawn-protection`範囲内(ワールド原点付近)ではOP権限のないbotのブロック設置・チェスト操作がサーバー側で無視されます。建設地やチェストは原点から十分離した場所に置いてください(過去に発生したバグ、詳細は`WORK_LOG_2026-06-24.md`参照)。

サーバーを起動:

```bash
java -Xmx2G -Xms1G -jar server.jar --nogui
```

バックグラウンドで動かしつつ後からコンソールコマンド(`/tp`など)を送りたい場合は、named pipeを使うと便利:

```bash
mkfifo cmd_pipe
java -Xmx2G -Xms1G -jar server.jar --nogui < cmd_pipe &
echo "say hello" > cmd_pipe   # 以後はこれでコンソールコマンドを送れる
```

### 4. 環境変数を設定

```bash
export OPENROUTER_API_KEY="sk-or-..."
```

`village.js`・`house_build.js`を使う場合は、建設地の座標も指定できます(`village.js`は必須、`house_build.js`は省略するとbotの現在地が自動でアンカーになる):

```bash
export ANCHOR_X=200
export ANCHOR_Y=64
export ANCHOR_Z=200
# village.js のみ追加で必要(共有チェストの座標)
export CHEST_X=205
export CHEST_Y=64
export CHEST_Z=205
```

### 5. スクリプトを実行

```bash
cd voyager/env/mineflayer
node village.js          # 4人で村づくり(木こり×2、建築家×2)
node house_build.js      # 2人で家を建てる
node lumber_team.js      # 2人で木材1スタック収集
node pvp_duel.js         # 2人でPvPデュエル
node chat_companion.js   # 人間と日本語で会話する単体コンパニオン
```

実行するとbotがMinecraftサーバーに参加するので、サーバーと同じワールドにMinecraftクライアントでログインして観戦できます(`online-mode=false`なら任意のユーザー名でログイン可)。各スクリプトのログは同名の`.log`ファイル(`village.log`など)に出力されます。

## ファイル一覧

| ファイル | 内容 |
|---|---|
| `chat_companion.js` | 人間と日本語で会話しつつ任意の行動を取れる単体コンパニオンボット |
| `lumber_team.js` | 2体協力で木材1スタック収集 |
| `pvp_duel.js` | 2体のPvP決闘 |
| `house_build.js` | 2体で家を建てる |
| `village.js` | 4体(木こり×2、建築家×2)で分業して村(家)を建てる |

## Claude Codeで作業を引き継ぐ場合

このリポジトリをcloneしたらまず以下をClaudeに伝えると、すぐに文脈を把握できます。

> `/root/Voyager/IMPLEMENTATION_NOTES.md` と `/root/Voyager/WORK_LOG_2026-06-24.md` を読んで、実装内容と経緯を把握して

その後、「次は農業(farming)エージェントをやりたい」のように残課題(`WORK_LOG_2026-06-24.md`末尾の「残課題」参照)から続きを指示すれば作業を再開できます。

---

# Installation
Voyager requires Python ≥ 3.9 and Node.js ≥ 16.13.0. We have tested on Ubuntu 20.04, Windows 11, and macOS. You need to follow the instructions below to install Voyager.

## Python Install
```
git clone https://github.com/MineDojo/Voyager
cd Voyager
pip install -e .
```

## Node.js Install
In addition to the Python dependencies, you need to install the following Node.js packages:
```
cd voyager/env/mineflayer
npm install -g npx
npm install
cd mineflayer-collectblock
npx tsc
cd ..
npm install
```

## Minecraft Instance Install

Voyager depends on Minecraft game. You need to install Minecraft game and set up a Minecraft instance.

Follow the instructions in [Minecraft Login Tutorial](installation/minecraft_instance_install.md) to set up your Minecraft Instance.

## Fabric Mods Install

You need to install fabric mods to support all the features in Voyager. Remember to use the correct Fabric version of all the mods. 

Follow the instructions in [Fabric Mods Install](installation/fabric_mods_install.md) to install the mods.

# Getting Started
Voyager uses OpenAI's GPT-4 as the language model. You need to have an OpenAI API key to use Voyager. You can get one from [here](https://platform.openai.com/account/api-keys).

After the installation process, you can run Voyager by:
```python
from voyager import Voyager

# You can also use mc_port instead of azure_login, but azure_login is highly recommended
azure_login = {
    "client_id": "YOUR_CLIENT_ID",
    "redirect_url": "https://127.0.0.1/auth-response",
    "secret_value": "[OPTIONAL] YOUR_SECRET_VALUE",
    "version": "fabric-loader-0.14.18-1.19", # the version Voyager is tested on
}
openai_api_key = "YOUR_API_KEY"

voyager = Voyager(
    azure_login=azure_login,
    openai_api_key=openai_api_key,
)

# start lifelong learning
voyager.learn()
```

* If you are running with `Azure Login` for the first time, it will ask you to follow the command line instruction to generate a config file.
* For `Azure Login`, you also need to select the world and open the world to LAN by yourself. After you run `voyager.learn()` the game will pop up soon, you need to:
  1. Select `Singleplayer` and press `Create New World`.
  2. Set Game Mode to `Creative` and Difficulty to `Peaceful`.
  3. After the world is created, press `Esc` key and press `Open to LAN`.
  4. Select `Allow cheats: ON` and press `Start LAN World`. You will see the bot join the world soon. 

# Resume from a checkpoint during learning

If you stop the learning process and want to resume from a checkpoint later, you can instantiate Voyager by:
```python
from voyager import Voyager

voyager = Voyager(
    azure_login=azure_login,
    openai_api_key=openai_api_key,
    ckpt_dir="YOUR_CKPT_DIR",
    resume=True,
)
```

# Run Voyager for a specific task with a learned skill library

If you want to run Voyager for a specific task with a learned skill library, you should first pass the skill library directory to Voyager:
```python
from voyager import Voyager

# First instantiate Voyager with skill_library_dir.
voyager = Voyager(
    azure_login=azure_login,
    openai_api_key=openai_api_key,
    skill_library_dir="./skill_library/trial1", # Load a learned skill library.
    ckpt_dir="YOUR_CKPT_DIR", # Feel free to use a new dir. Do not use the same dir as skill library because new events will still be recorded to ckpt_dir. 
    resume=False, # Do not resume from a skill library because this is not learning.
)
```
Then, you can run task decomposition. Notice: Occasionally, the task decomposition may not be logical. If you notice the printed sub-goals are flawed, you can rerun the decomposition.
```python
# Run task decomposition
task = "YOUR TASK" # e.g. "Craft a diamond pickaxe"
sub_goals = voyager.decompose_task(task=task)
```
Finally, you can run the sub-goals with the learned skill library:
```python
voyager.inference(sub_goals=sub_goals)
```

For all valid skill libraries, see [Learned Skill Libraries](skill_library/README.md).

# FAQ
If you have any questions, please check our [FAQ](FAQ.md) first before opening an issue.

# Paper and Citation

If you find our work useful, please consider citing us! 

```bibtex
@article{wang2023voyager,
  title   = {Voyager: An Open-Ended Embodied Agent with Large Language Models},
  author  = {Guanzhi Wang and Yuqi Xie and Yunfan Jiang and Ajay Mandlekar and Chaowei Xiao and Yuke Zhu and Linxi Fan and Anima Anandkumar},
  year    = {2023},
  journal = {arXiv preprint arXiv: Arxiv-2305.16291}
}
```

Disclaimer: This project is strictly for research purposes, and not an official product from NVIDIA.
