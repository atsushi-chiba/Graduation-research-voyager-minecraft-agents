// Multi-agent MineColonies "council" experiment, in the spirit of the
// original Voyager scripts (village.js etc.) where several LLM personas
// talk to each other in Japanese while doing a task - except here nobody
// connects to Minecraft as a bot (mineflayer can't, see README.md). Instead:
//   - A handful of GOVERNOR personas take turns deciding colony actions
//     (place/found/requestBuild/giveToCitizen/...) via the Voyager Bridge
//     HTTP API, and chat about their reasoning.
//   - Each real MineColonies citizen periodically gets a short, in-character
//     line of dialogue generated from their actual game state (job, open
//     requests) - flavor only, no game action.
// Both kinds of lines are broadcast into the real Minecraft chat via the
// server console (so a human watching in-game sees the whole conversation),
// by writing "say <name>: <message>" into the server's cmd_pipe.
const https = require("https");
const http = require("http");
const fs = require("fs");
const path = require("path");

// Load building registry once at startup and pre-render as a compact table
// so every governor's system prompt has the exact block IDs and blueprint
// paths without guessing.
const BUILDING_REGISTRY = JSON.parse(
  fs.readFileSync(path.join(__dirname, "building_registry.json"), "utf8")
);
function buildingTable() {
  const rows = Object.entries(BUILDING_REGISTRY)
    .filter(([, v]) => v.blueprint !== null)
    .map(([key, v]) => `${v.block}|${v.blueprint}|${v.job}|${v.role}`);
  return (
    "block_id|blueprint_path(Colonial)|職業名|役割\n" +
    rows.join("\n")
  );
}

const OPENROUTER_API_KEY = process.env.OPENROUTER_API_KEY;
// A fast/cheap model matters a lot more here than raw reasoning quality -
// the real-time game (no /tick speedup available on vanilla 1.20.1) sets
// the pace either way, so a slow model just means the chat lags behind
// what's actually happening rather than the colony progressing faster.
const MODEL = "anthropic/claude-haiku-4.5";
const BRIDGE_HOST = "localhost";
const BRIDGE_PORT = 8089;
const CMD_PIPE = "/root/mc-server-forge/cmd_pipe";
const COLONY_ID = 1;
const MAX_CYCLES = 300;
const TURN_DELAY_MS = 2000;

const ANCHOR = { x: 200, y: -60, z: 200 };

const GOVERNORS = [
  {
    name: "Aldric",
    role: "都市計画担当",
    personality: "実利主義で、効率と拡張を最優先する。慎重派の同僚にイライラしがち。",
  },
  {
    name: "Mira",
    role: "民政担当",
    personality: "市民の暮らしや資材不足を気にかける。慎重で、無理な拡張には異議を唱える。",
  },
];

const sharedChatLog = [];

function sayInGame(name, message) {
  const safe = String(message).replace(/"/g, "'").slice(0, 200);
  sharedChatLog.push({ who: name, text: safe, t: Date.now() });
  try {
    // O_NONBLOCK: if the server isn't reading from cmd_pipe (no reader on the
    // FIFO), writeFileSync blocks forever. Non-blocking open throws ENXIO
    // immediately instead, so the council loop doesn't hang.
    const fd = fs.openSync(CMD_PIPE, fs.constants.O_WRONLY | fs.constants.O_NONBLOCK);
    fs.writeSync(fd, `say ${name}: ${safe}\n`);
    fs.closeSync(fd);
  } catch (e) {
    console.log(`[chat write failed] ${name}: ${safe} (${e.message})`);
  }
  console.log(`[CHAT] ${name}: ${safe}`);
}

function httpRequest(method, path) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: BRIDGE_HOST, port: BRIDGE_PORT, path, method }, (res) => {
      let data = "";
      res.on("data", (c) => (data += c));
      res.on("end", () => resolve({ status: res.statusCode, body: data }));
    });
    req.on("error", reject);
    req.end();
  });
}

// Scan for the first syntactically complete JSON object in text.
// The greedy /\{[\s\S]*\}/ regex breaks when the LLM appends explanation
// text after the JSON (common with markdown code fences + reasoning notes)
// because it stretches to the last } in the entire string.
function extractFirstJSON(text) {
  // Strip markdown code fence if present: ```json ... ``` or ``` ... ```
  const fence = text.match(/```(?:json)?\s*([\s\S]*?)```/);
  if (fence) return fence[1].trim();
  // Otherwise find the first { and walk braces to find its matching }.
  const start = text.indexOf("{");
  if (start === -1) return null;
  let depth = 0, inString = false, escape = false;
  for (let i = start; i < text.length; i++) {
    const ch = text[i];
    if (escape) { escape = false; continue; }
    if (ch === "\\" && inString) { escape = true; continue; }
    if (ch === '"') { inString = !inString; continue; }
    if (inString) continue;
    if (ch === "{") depth++;
    else if (ch === "}") { if (--depth === 0) return text.slice(start, i + 1); }
  }
  return null;
}

function askLLM(model, messages) {
  return new Promise((resolve, reject) => {
    const payload = JSON.stringify({ model, messages });
    const req = https.request(
      {
        hostname: "openrouter.ai",
        path: "/api/v1/chat/completions",
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${OPENROUTER_API_KEY}`,
          "Content-Length": Buffer.byteLength(payload),
        },
      },
      (res) => {
        let data = "";
        res.on("data", (c) => (data += c));
        res.on("end", () => {
          try {
            const parsed = JSON.parse(data);
            const reply = parsed.choices?.[0]?.message?.content?.trim();
            if (reply) resolve(reply);
            else reject(new Error("No reply: " + data));
          } catch (e) {
            reject(e);
          }
        });
      }
    );
    req.on("error", reject);
    req.write(payload);
    req.end();
  });
}

async function getStatus() {
  const res = await httpRequest("GET", "/status");
  return JSON.parse(res.body);
}

async function getOpenRequests(x, y, z, citizenId) {
  const res = await httpRequest("GET", `/openRequests?x=${x}&y=${y}&z=${z}&citizenId=${citizenId}`);
  try {
    return JSON.parse(res.body);
  } catch {
    return [];
  }
}

// ---------- Governor (council) turn ----------

function buildGovernorSystemPrompt(gov) {
  const others = GOVERNORS.filter((g) => g.name !== gov.name).map((g) => g.name).join(", ");
  return `あなたはMinecraft MineColoniesの植民地を運営する統治者会議の一員、${gov.name}(${gov.role})です。性格: ${gov.personality}
他の統治者: ${others}。彼らと日本語で会話しながら、合議で方針を決めます。

ゲームを直接見ることはできず、毎ターンJSONのステータスだけが渡されます。実際の建築はMineColonies自身の建築家NPCがやるので、あなたの仕事は「どこに何を建てるか」「市民を増やすかどうか」を決めることだけです。

資材・物資の供給はコロニーのNPCが自律的にやる仕組みです。例えば、農夫が食料を作り、木こりが木材を切り、配達員が倉庫から各建物へ配給します。あなたが直接アイテムを渡す必要はありません。「資材が足りない」なら、それを生産・供給できる建物(農場・木こり小屋・倉庫・配達スタンド等)を建てることが解決策です。

返答フォーマット(厳守、JSON以外の文章は書かない):
{"say":"<日本語で一言、40文字以内、他の統治者への発言や状況へのコメント>","action":{"action":"<action名>", ...パラメータ}}

actionの種類:
- {"action":"place","x":<int>,"y":<int>,"z":<int>,"block":"<block_id>"} ← 下の対応表のblock_idをそのまま使うこと
- {"action":"found","x":<int>,"y":<int>,"z":<int>,"name":"<colony name>"}
- {"action":"spawnCitizen","colonyId":${COLONY_ID}}
- {"action":"requestBuild","x":<int>,"y":<int>,"z":<int>}
- {"action":"resolveRequest","x":<int>,"y":<int>,"z":<int>,"citizenId":<int>}
- {"action":"giveToCitizen","colonyId":${COLONY_ID},"citizenId":<int>,"item":"minecraft:<item_id>","count":<int>}
- {"action":"wait"}

建物対応表(Colonialパック・block_idとblueprintパスの正確な一覧。これ以外のblock_idは存在しないので使わないこと):
${buildingTable()}

ルール:
- 最初にtown hallを置いてfoundするまで他のactionは無効。
- 置いたら必ずrequestBuildを呼ぶ(呼ばないと永遠に着工しない)。
- 他の統治者の直前の発言や行動を踏まえて、被らないように調整すること。
- アンカー座標(${ANCHOR.x},${ANCHOR.y},${ANCHOR.z})付近に建てる。

建物配置ルール(厳守):
各建物には以下の実寸(X×Z幅、建物の一端からもう一端まで)があるため、隣同士の配置時は双方のサイズを考慮して最低5ブロックの隙間を確保すること:
  townhall:38×33  tavern:22×20  warehouse:20×24  farm:25×20  mine:13×22
  fisher:22×16  hospital:16×17  restaurant:17×17  forester:11×19
  citizen:13×15  guardtower:11×10  builder:22×11  courier:4×2

推奨配置座標(アンカー200,64,200基準、5ブロック間隔計算済み):
  Row1(z=200): townhall→(200,-60,200)  builder#1→(243,-60,200)  citizen#1→(270,-60,200)  citizen#2→(288,-60,200)
  Row2(z=238): tavern→(200,-60,238)  warehouse→(227,-60,238)  courier→(252,-60,238)  fisher→(261,-60,238)  forester→(288,-60,238)
  Row3(z=267): mine→(200,-60,267)  restaurant→(218,-60,267)  farm→(240,-60,267)  guardtower→(270,-60,267)  builder#2→(286,-60,267)

- 建物はy=-60で置く(このワールドの地表レベル)。
- /placeがERROR(既存建物の範囲と重複)を返したら次の推奨座標を使うこと。絶対に同じ座標で再試行しない。
- 推奨座標以外に配置する場合は、上の実寸を使って隣接建物から5ブロック以上の隙間を計算すること。

資材供給について(重要):
- supply_bot.js が並走しており、全市民のオープンリクエストを自動で解決し続けている。資材不足は自動解決されるので、あなたは建設計画に専念してよい。
- 道具は建物レベルで使える上限が決まる(lv0-1→石製まで, lv2→鉄製まで, lv3→ダイヤまで)。supply_botが自動対応するので手動で渡す必要は原則ない。
- builderがstuckの場合のみ、/openRequestsで確認した要求アイテムをそのまま giveToCitizen で渡すこと。

進行戦略(この順番を守ること):
1. town hallを置いてfound → town hallはまだrequestBuildしない(builderが必要なため)
2. spawnCitizenで市民を8人スポーン(builderになる人員を確保するため)
3. builder hutを8棟置いてそれぞれrequestBuild → 市民がbuilderに自動割り当てされ自分のhutを建設し始める
4. 全builderのBuilder's HutがoperationalになったらrequestBuild for town hall
5. 住居(blockhutcitizen)・食料系(blockhutfisherman, blockhutfarmer)・倉庫(blockhutwarehouse)+配達(blockhutdeliveryman)を優先して建設
6. waitは「今サイクルで何もすることがない」時だけ使う。常に何か行動できることを探すこと。

重要な仕組み(必ず守ること):
- Builder's Hut(blockhutbuilder)のレベルが建設できる建物の上限を決める。
  - レベル0: 自分のBuilder's Hutのみ建設可(town hall含む他の建物へのrequestBuildはエラーになる)
  - レベル1以上: そのレベルまでの建物を建設可能
- 【重要】最初に市民をspawnCitizenしてから builder hutを置くこと。市民がいないと誰もbuilderにならず建設が進まない。
- まず全てのBuilder's HutにrequestBuildを呼んでレベル1にする。それが完了してから他の建物へrequestBuildする。
- 研究ゲート建物(sawmill, blacksmith等)はUniversityでの研究完了前にrequestBuildするとエラーになる。researchUnlocked配列で解除済みかを確認してから呼ぶこと。
- 1つのBuilder's Hutに対して実際に作業できる建築家は1人だけ。Builder職の市民がN人いるならblockhutbuilderもN棟必要。

ステータスJSONの読み方:
- buildings[i].operational: true = 建物が完成して稼働中(level>=1かつpending=false)。false = まだ建設中か未着工
- buildings[i].pending: true = 作業オーダーあり(建設中)。false = 未着工 または 完成済み
- buildings[i].inTerritory: true = コロニー領域内(市民が作業可能)。false = 領域外(builderが割り当てられても実際には動かない)
- buildings[i].workers: その建物に割り当てられている市民IDのリスト
- citizens[j].jobStatus: "idle"/"working"/"stuck" - 市民の作業状態
- citizens[j].workBuilding: 市民が割り当てられている建物の座標とレベル(nullなら未割り当て)
- researchUnlocked: 解除済み研究ゲート建物のリスト(この中にある建物だけrequestBuild可能)
- 「配達員(deliveryman)」は配達スタンド(blockhutdeliveryman)が必要。建物が無い場合、自動割り当てで職が付いても実際には働けない。

コロニー領域について(重要):
- コロニーはタウンホール周囲の初期サイズ(初期設定: 64ブロック半径)のチャンクを管理している。
- inTerritory=falseの建物を置いてしまった場合、builderは割り当てられるが実際には動かない(意味がない)。
- /placeの結果に "WARNING: position is outside colony claimed territory" が含まれていたら、その座標はコロニー領域外なので、より中心に近い座標に変更すること。タウンホールのレベルが上がれば領域は拡大する。`;

}

async function governorTurn(gov, history, status) {
  const userMsg = `[STATE] anchor ${ANCHOR.x},${ANCHOR.y},${ANCHOR.z}\ncolonies: ${JSON.stringify(status)}\n直近の会話: ${sharedChatLog
    .slice(-8)
    .map((c) => `${c.who}: ${c.text}`)
    .join(" | ")}\n次の行動は?`;
  history.push({ role: "user", content: userMsg });

  let reply;
  try {
    reply = await askLLM(MODEL, history.slice(0, 1).concat(history.slice(-12)));
  } catch (e) {
    console.log(`[${gov.name}] LLM error:`, e.message);
    return;
  }
  history.push({ role: "assistant", content: reply });

  const jsonStr = extractFirstJSON(reply);
  if (!jsonStr) {
    console.log(`[${gov.name}] no JSON in reply:`, reply.slice(0, 200));
    return;
  }
  let parsed;
  try {
    parsed = JSON.parse(jsonStr);
  } catch (e) {
    console.log(`[${gov.name}] JSON parse failed:`, e.message, jsonStr.slice(0, 200));
    return;
  }

  if (parsed.say) sayInGame(gov.name, parsed.say);

  if (parsed.action && parsed.action.action) {
    try {
      const res = await runGovernorAction(parsed.action);
      console.log(`[${gov.name}] action ${parsed.action.action} ->`, res.status, res.body);
    } catch (e) {
      console.log(`[${gov.name}] action failed:`, e.message);
    }
  }
}

async function runGovernorAction(action) {
  switch (action.action) {
    case "place":
      return httpRequest("POST", `/place?x=${action.x}&y=${action.y}&z=${action.z}&block=${encodeURIComponent(action.block)}`);
    case "found":
      return httpRequest("POST", `/found?x=${action.x}&y=${action.y}&z=${action.z}&name=${encodeURIComponent(action.name || "VoyagerColony")}`);
    case "spawnCitizen":
      return httpRequest("POST", `/spawnCitizen?colonyId=${action.colonyId || COLONY_ID}`);
    case "requestBuild":
      return httpRequest("POST", `/requestBuild?x=${action.x}&y=${action.y}&z=${action.z}`);
    case "giveToCitizen":
      return httpRequest(
        "POST",
        `/giveToCitizen?colonyId=${action.colonyId || COLONY_ID}&citizenId=${action.citizenId}&item=${encodeURIComponent(action.item)}&count=${action.count}`
      );
    case "resolveRequest":
      return httpRequest("POST", `/resolveRequest?x=${action.x}&y=${action.y}&z=${action.z}&citizenId=${action.citizenId}`);
    case "wait":
      return { status: 200, body: '{"result":"waited"}' };
    default:
      throw new Error("Unknown action: " + JSON.stringify(action));
  }
}

// ---------- Citizen voice ----------

// Build job descriptions once: schematic_name -> English description from lang file
function jobDescriptions() {
  return Object.entries(BUILDING_REGISTRY)
    .filter(([, v]) => v.desc_en)
    .map(([key, v]) => `${v.job}(${key}): ${v.desc_en.slice(0, 120)}`)
    .join("\n");
}

const CITIZEN_VOICE_PROMPT = `あなたはMinecraft MineColoniesの植民地に住む市民です。与えられた自分の本当の状態(職業・必要な資材など)に基づいて、その状況に合った短い日本語のセリフを一言だけ生成してください。
- 40文字以内、セリフのみ(説明や記号は不要)
- 職業の仕事内容を踏まえたリアルな発言にすること(例: 木こりなら「また大木を切り倒すぞ」、農夫なら「今日の収穫は良さそうだ」)
- 無職なら不安や期待を、Builderで資材待ちなら愚痴や催促を
- 同じようなセリフを繰り返さない

職業の説明(参考):
${jobDescriptions()}`;

async function citizenVoiceTurn(citizen, building) {
  let context = `名前: ${citizen.name}, 職業: ${citizen.job}, 状態: ${citizen.jobStatus || "不明"}`;
  // Look up English job description for richer context
  const jobEntry = Object.values(BUILDING_REGISTRY).find((v) => {
    const key = Object.entries(BUILDING_REGISTRY).find(([, vv]) => vv === v)?.[0];
    return citizen.job && (key === citizen.job || v.job === citizen.job);
  });
  if (jobEntry?.desc_en) context += `, 仕事内容: ${jobEntry.desc_en.slice(0, 100)}`;
  if (building) {
    const requests = await getOpenRequests(building.x, building.y, building.z, citizen.id);
    if (requests.length > 0) {
      context += `, 未解決の要求: ${requests.map((r) => r.description).join(", ")}`;
    } else {
      context += `, 未解決の要求なし`;
    }
  }
  let line;
  try {
    line = await askLLM(MODEL, [
      { role: "system", content: CITIZEN_VOICE_PROMPT },
      { role: "user", content: context },
    ]);
  } catch (e) {
    console.log(`[citizen voice error] ${citizen.name}:`, e.message);
    return;
  }
  sayInGame(citizen.name, line.replace(/^["']|["']$/g, ""));
}

// ---------- Main loop ----------

async function main() {
  const histories = {};
  GOVERNORS.forEach((g) => {
    histories[g.name] = [{ role: "system", content: buildGovernorSystemPrompt(g) }];
  });

  for (let cycle = 1; cycle <= MAX_CYCLES; cycle++) {
    console.log(`\n=== cycle ${cycle} ===`);
    const status = await getStatus();

    // One citizen speaks per cycle, picked round-robin from whichever
    // colony already exists.
    const colony = status[0];
    if (colony && colony.citizens && colony.citizens.length > 0) {
      const citizen = colony.citizens[(cycle - 1) % colony.citizens.length];
      await citizenVoiceTurn(citizen, citizen.workBuilding);
      await sleep(TURN_DELAY_MS);
    }

    // Each governor takes one turn per cycle.
    for (const gov of GOVERNORS) {
      const freshStatus = await getStatus();
      await governorTurn(gov, histories[gov.name], freshStatus);
      await sleep(TURN_DELAY_MS);
    }
  }
  console.log("\nReached max cycles, stopping.");
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

main().catch((e) => console.error("FATAL", e));
