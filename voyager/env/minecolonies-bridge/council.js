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
const http = require("http");
const fs = require("fs");
const path = require("path");

// Load building registry once at startup and pre-render as a compact table
// so every governor's system prompt has the exact block IDs and blueprint
// paths without guessing.
const BUILDING_REGISTRY = JSON.parse(
  fs.readFileSync(path.join(__dirname, "building_registry.json"), "utf8")
);
// Per-building upgrade-effect knowledge (what actually improves: capacity,
// throughput, slots, unlocks). Small models can't infer this, so it is
// embedded directly into the candidate labels the governor picks from.
const BUILDING_KNOWLEDGE = JSON.parse(
  fs.readFileSync(path.join(__dirname, "building_knowledge.json"), "utf8")
);
function upgradeEffect(buildingType) {
  const key = String(buildingType).replace(/^blockhut/, "");
  const know = BUILDING_KNOWLEDGE[key];
  return know && know.upgrade ? know.upgrade : "";
}
function buildingTable() {
  const rows = Object.entries(BUILDING_REGISTRY)
    .filter(([, v]) => v.blueprint !== null)
    .map(([key, v]) => `${v.block}|${v.blueprint}|${v.job}|${v.role}`);
  return (
    "block_id|blueprint_path(Colonial)|職業名|役割\n" +
    rows.join("\n")
  );
}

// LLM backend: local ollama on the lab server (OpenAI-compatible
// /v1/chat/completions, no auth, no usage cost). Switched from OpenRouter
// (2026-07-03) after its credit balance ran out - even the ~19k-token input
// prompt alone exceeded the remaining allowance.
const LLM_HOST = process.env.LLM_HOST || "192.168.15.150";
const LLM_PORT = parseInt(process.env.LLM_PORT || "11434", 10);
const MODEL = process.env.LLM_MODEL || "gemma4:e4b";
const BRIDGE_HOST = "localhost";
const BRIDGE_PORT = 8089;
const CMD_PIPE = "/root/mc-server-forge/cmd_pipe";
const COLONY_ID = 1;
// Infinity = resident daemon. The 300-cycle cap predates the colony_watch
// supervisor; with the cap, council exited every ~75min and the watch's
// auto-restart fired a notification each time. Now a council death is an
// actual anomaly worth reporting, not scheduled churn.
const MAX_CYCLES = Infinity;
const TURN_DELAY_MS = 2000;
// With the local ollama backend there is no per-token cost, so the old
// economy mode (60s cycles, citizen voice 1-in-3) is relaxed: the cycle pace
// now just tracks how fast the colony state actually changes.
const CYCLE_DELAY_MS = 15000;
const CITIZEN_VOICE_EVERY = 1;

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

// Structured-output schema for governor turns, enforced by ollama's
// schema-constrained decoding. gemma-class models can't reliably construct
// free-form action JSON (they invent action names and coordinates), so the
// governor picks a numbered choice from a menu that buildCandidates()
// derives from the live /status - every candidate already carries exact,
// valid parameters.
const GOVERNOR_REPLY_SCHEMA = {
  type: "object",
  properties: {
    say: { type: "string" },
    choice: { type: "integer" },
  },
  required: ["say", "choice"],
};

// Residence lvN sleeps N citizens, the tavern sleeps 4.
function housingCapacity(colony) {
  return (colony.buildings || []).reduce((cap, b) => {
    if (!b.operational) return cap;
    if (b.type === "blockhutcitizen") return cap + b.level;
    if (b.type === "blockhuttavern") return cap + 4;
    return cap;
  }, 0);
}

// Map of buildingKey -> {research, level} for researches blocked ONLY by an
// unmet building requirement (from /research "blocked"). Lets the menu tell
// the governor "upgrading this also unlocks research X".
async function getResearchNeeds() {
  try {
    const res = await httpRequest("GET", `/research?colonyId=${COLONY_ID}`);
    if (res.status !== 200) return {};
    const d = JSON.parse(res.body);
    const map = {};
    for (const blk of d.blocked || []) {
      for (const need of blk.requirements || []) {
        if (need.met || !need.building) continue;
        const key = need.building.split(":").pop().replace(/^blockhut/, "");
        const research = blk.id.split("/").pop();
        if (!map[key] || need.level < map[key].level) {
          map[key] = { research, level: need.level };
        }
      }
    }
    return map;
  } catch {
    return {};
  }
}

// Enumerate every action that makes sense against the current status.
// Index 0 is always wait so an out-of-range choice degrades to a no-op.
function buildCandidates(status, researchNeeds = {}) {
  const candidates = [{ label: "wait(様子見。建設中で他にやることがない時のみ)", action: { action: "wait" } }];
  const colony = status[0];
  if (colony) {
    // Only offer the spawn cheat while there are free beds - gemma otherwise
    // picks it every single turn and the population runs away past housing.
    const housing = housingCapacity(colony);
    const pop = (colony.citizens || []).length;
    if (pop < housing) {
      candidates.push({
        label: `spawnCitizen(市民を1人追加。住居容量 ${housing} に空きあり)`,
        action: { action: "spawnCitizen", colonyId: COLONY_ID },
      });
    }
    // A building can only be upgraded to a level some operational builder hut
    // already has (the hut itself may self-upgrade one level ahead). Doomed
    // upgrade candidates are filtered out entirely - the model otherwise keeps
    // picking them and collecting level-gate errors.
    const buildings = colony.buildings || [];
    const maxBuilderLevel = Math.max(
      0,
      ...buildings.filter((b) => b.type === "blockhutbuilder" && b.operational).map((b) => b.level)
    );
    for (const b of buildings) {
      if (b.pending || !b.inTerritory) continue;
      if (!b.operational) {
        candidates.push({
          label: `requestBuild ${b.type} @(${b.x},${b.y},${b.z}) 未着工→着工させる(重要)`,
          action: { action: "requestBuild", x: b.x, y: b.y, z: b.z },
        });
      } else if (b.level < (b.maxLevel ?? 5) && (b.type === "blockhutbuilder" || b.level + 1 <= maxBuilderLevel)) {
        // maxLevel comes from /status (building.getMaxBuildingLevel()) - e.g.
        // Colonial tavern caps at 3, postbox at 1; offering those upgrades
        // wasted mayor turns on silent no-ops.
        // level 5 is the MineColonies max; requestUpgrade silently no-ops there
        const effect = upgradeEffect(b.type);
        const key = b.type.replace(/^blockhut/, "");
        const rn = researchNeeds[key];
        const unlock = rn && b.level < rn.level
          ? `(さらに lv${rn.level} で研究「${rn.research}」が解禁される)` : "";
        candidates.push({
          label: `requestBuild ${b.type} @(${b.x},${b.y},${b.z}) lv${b.level}→lv${b.level + 1}にアップグレード${effect ? "(効果: " + effect + ")" : ""}${unlock}`,
          action: { action: "requestBuild", x: b.x, y: b.y, z: b.z },
        });
      }
    }
    if (pop > housing) {
      candidates.push({
        label: `placeNext minecolonies:blockhutcitizen(住居の新設。市民${pop}人>容量${housing}人なので最優先級)`,
        action: { action: "placeNext", block: "minecolonies:blockhutcitizen" },
      });
    }
  }
  const existingTypes = new Set(
    ((status[0] || {}).buildings || []).map((b) => String(b.type).replace(/^blockhut/, ""))
  );
  for (const [regKey, v] of Object.entries(BUILDING_REGISTRY)) {
    if (v.blueprint === null) continue;
    if (v.block === "minecolonies:blockhuttownhall") continue; // manual bootstrap only
    const rn = researchNeeds[regKey];
    const unlock = rn && !existingTypes.has(regKey)
      ? `(建てると研究「${rn.research}」の解禁に近づく)` : "";
    candidates.push({
      label: `placeNext ${v.block}(${v.job || "-"}: ${(v.role || "").slice(0, 40)}) 新設を配置${unlock}`,
      action: { action: "placeNext", block: v.block },
    });
  }
  return candidates;
}

function askLLM(model, messages, { format = null } = {}) {
  return new Promise((resolve, reject) => {
    // ollama native /api/chat, not the OpenAI-compatible /v1 endpoint:
    // - think:false is required - gemma4 otherwise burns the whole token
    //   budget on a "reasoning" field and returns empty content.
    // - format: JSON schema (or "json") for structured turns. Citizen voice
    //   is free-form text and must NOT set it.
    // num_predict caps runaway generations; replies are one short JSON object.
    const body = { model, messages, stream: false, think: false, options: { num_predict: 1000 } };
    if (format) body.format = format;
    const payload = JSON.stringify(body);
    const req = http.request(
      {
        host: LLM_HOST,
        port: LLM_PORT,
        path: "/api/chat",
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(payload),
        },
      },
      (res) => {
        let data = "";
        res.on("data", (c) => (data += c));
        res.on("end", () => {
          try {
            const parsed = JSON.parse(data);
            const reply = parsed.message?.content?.trim();
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
{"say":"<日本語で一言、40文字以内、他の統治者への発言や状況へのコメント>","choice":<番号>}

毎ターン、[ACTIONS] として「いま実行可能なアクション」の番号付きリストが渡される。その中から1つ選び、choice にその番号(整数)を入れること。リストにないことは実行できない。

ルール:
- sayは必ず日本語で40文字以内。長い分析を書かない。
- 他の統治者の直前の発言や行動を踏まえて、被らないように調整すること。
- placeNextで配置した建物は「未着工」のまま。[ACTIONS]に「未着工→着工させる」のrequestBuildが出ていたら、原則それを最優先で選ぶこと(着工しないと永遠に建たない)。
- placeNextがERRORを返したら、その建物タイプが入る空きが今のコロニー領域にないということ。blockhutguardtower(衛兵塔)を新設して領域を拡張するか、townhallをアップグレードする。

資材供給について(重要):
- supply_bot.js が並走しており、全市民のオープンリクエストを自動で解決し続けている。資材不足は自動解決されるので、あなたは建設計画に専念してよい。
- 道具は建物レベルで使える上限が決まる(lv0-1→石製まで, lv2→鉄製まで, lv3→ダイヤまで)。supply_botが自動対応するので手動で渡す必要は原則ない。
- builderがstuckの場合のみ、/openRequestsで確認した要求アイテムをそのまま giveToCitizen で渡すこと。

進行戦略(現在は中期フェーズ。毎ターン [HINT] と status を読み、以下を上から順にチェックして最初に該当したものを選ぶ):

A. [ACTIONS]に「未着工→着工させる」がある → 必ずそれを選ぶ(最優先。着工しないと永遠に建たない)
B. 市民数が住居容量を超えている([HINT]に表示) → 住居(blockhutcitizen)を新設、または既存住居をアップグレード(lvNの住居はN人収容)。宿なしの市民が出るので常に最優先級。
C. lv2のbuilder hutがまだ1棟もない → builder hutをアップグレード(builder hutのレベル = 他の建物をそのレベルまで上げられる上限。全アップグレードの前提)
D. 生活基盤の強化 → 食料(farm/cook)、資材(lumberjack/sawmill)、物流(warehouse/deliveryman)の新設やアップグレード
E. university新設 → 研究でhospital等の上位建物が解禁される
F. 上記に該当なし → 重要施設(townhall・warehouse・住居)のアップグレード。townhallのレベルアップはコロニー領域も広げる。

【重要ルール】
- 1ターンに1アクションのみ。
- 直前のターンで placeNext や requestBuild がERRORを返していたら、同じ選択を繰り返さないこと([RESULT]にエラー理由が出る)。
- placeNextがERRORの時は領域不足 → blockhutguardtower(衛兵塔)を新設するとその周囲のチャンクがコロニー領域に加わり、領域を拡張できる。
- 建物のアップグレードが「needs a Builder's Hut at level N」エラーになったら、先にbuilder hutをアップグレードする。
- pending=trueの建物が多い時はwait。builderが同時に処理できる案件は hut 数分だけ。
- 他の統治者と同じアクションを連続で選ばない(被り防止)。
- waitは「建設中で何もできない」時のみ。必ず理由をsayで共有。

重要な仕組み(必ず守ること):
- Builder's Hut(blockhutbuilder)のレベルが建設・アップグレードできる建物のレベル上限を決める。lv2建物が欲しければ先にbuilder hutをlv2にする。
- 研究ゲート建物(hospital, sawmill, blacksmith等)はUniversityでの研究完了前にrequestBuildするとエラーになる。researchUnlocked配列で解除済みかを確認してから呼ぶこと。placeNext自体は通ってしまうので、requestBuildがエラーになったら建物を放置せずwaitに切り替えること。
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
  const candidates = buildCandidates(status);
  const menu = candidates.map((c, i) => `${i}: ${c.label}`).join("\n");
  // Pre-computed hints: small models don't reliably derive these from the
  // raw status JSON, and the housing deficit drives the top-priority rule.
  const colony = status[0];
  let hint = "";
  if (colony) {
    const cap = housingCapacity(colony);
    const pop = (colony.citizens || []).length;
    const pending = (colony.buildings || []).filter((b) => b.pending).length;
    hint = `\n[HINT] 市民${pop}人/住居容量${cap}人${pop > cap ? ` → 住居が${pop - cap}人分不足!住居の新設/アップグレードを優先` : "(充足)"}、建設中(pending)${pending}件`;
  }
  const userMsg = `[STATE] anchor ${ANCHOR.x},${ANCHOR.y},${ANCHOR.z}\ncolonies: ${JSON.stringify(status)}${hint}\n直近の会話: ${sharedChatLog
    .slice(-8)
    .map((c) => `${c.who}: ${c.text}`)
    .join(" | ")}\n[ACTIONS] 次の行動を1つ選び {"say":"<日本語で40文字以内の短い一言>","choice":<番号>} で答えること。sayに分析や長文を書かない:\n${menu}`;
  history.push({ role: "user", content: userMsg });

  let reply;
  try {
    reply = await askLLM(MODEL, history.slice(0, 1).concat(history.slice(-12)), { format: GOVERNOR_REPLY_SCHEMA });
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

  if (parsed.say) sayInGame(gov.name, String(parsed.say).slice(0, 60));
  else console.log(`[${gov.name}] reply missing say:`, jsonStr.slice(0, 200));

  const idx =
    Number.isInteger(parsed.choice) && parsed.choice >= 0 && parsed.choice < candidates.length
      ? parsed.choice
      : 0;
  const chosen = candidates[idx];
  try {
    const res = await runGovernorAction(chosen.action);
    console.log(`[${gov.name}] choice ${idx} (${chosen.label}) ->`, res.status, res.body);
    // Feed the outcome back into this governor's history - otherwise errors
    // (level gates, no space, research gates) are invisible and the model
    // keeps repeating the same failing choice.
    history.push({ role: "user", content: `[RESULT] ${chosen.label} -> ${res.status} ${String(res.body).slice(0, 150)}` });
  } catch (e) {
    console.log(`[${gov.name}] action failed:`, e.message);
  }
}

async function runGovernorAction(action) {
  switch (action.action) {
    case "placeNext":
      return httpRequest("POST", `/placeNext?block=${encodeURIComponent(action.block)}&colonyId=${action.colonyId || COLONY_ID}`);
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
    if (
      colony && colony.citizens && colony.citizens.length > 0 &&
      (cycle - 1) % CITIZEN_VOICE_EVERY === 0
    ) {
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

    await sleep(CYCLE_DELAY_MS);
  }
  console.log("\nReached max cycles, stopping.");
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

module.exports = { askLLM, buildGovernorSystemPrompt, extractFirstJSON, buildCandidates, GOVERNORS, MODEL, GOVERNOR_REPLY_SCHEMA };

if (require.main === module) {
  main().catch((e) => console.error("FATAL", e));
}
