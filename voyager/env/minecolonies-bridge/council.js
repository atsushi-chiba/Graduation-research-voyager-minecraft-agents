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
const MAX_CYCLES = 30;
const TURN_DELAY_MS = 2000;

const ANCHOR = { x: 10, y: -60, z: 10 };

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
    fs.writeFileSync(CMD_PIPE, `say ${name}: ${safe}\n`);
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

ゲームを直接見ることはできず、毎ターンJSONのステータスだけが渡されます。実際の建築はMineColonies自身の建築家NPCがやるので、あなたの仕事は「どこに何を建てるか」「資材供給の指示」「市民を増やすかどうか」を決めることだけです。

返答フォーマット(厳守、JSON以外の文章は書かない):
{"say":"<日本語で一言、40文字以内、他の統治者への発言や状況へのコメント>","action":{"action":"<action名>", ...パラメータ}}

actionの種類:
- {"action":"place","x":<int>,"y":<int>,"z":<int>,"block":"<block_id>"} ← 下の対応表のblock_idをそのまま使うこと
- {"action":"found","x":<int>,"y":<int>,"z":<int>,"name":"<colony name>"}
- {"action":"spawnCitizen","colonyId":${COLONY_ID}}
- {"action":"requestBuild","x":<int>,"y":<int>,"z":<int>}
- {"action":"giveToCitizen","colonyId":${COLONY_ID},"citizenId":<int>,"item":"minecraft:<item_id>","count":<int>}
- {"action":"resolveRequest","x":<int>,"y":<int>,"z":<int>,"citizenId":<int>}
- {"action":"wait"}

建物対応表(Colonialパック・block_idとblueprintパスの正確な一覧。これ以外のblock_idは存在しないので使わないこと):
${buildingTable()}

ルール:
- 最初にtown hallを置いてfoundするまで他のactionは無効。
- 置いたら必ずrequestBuildを呼ぶ(呼ばないと永遠に着工しない)。
- 道具は石/木製のみ(鉄以上は低レベル市民が使えない)。
- 他の統治者の直前の発言や行動を踏まえて、被らないように調整すること。
- アンカー座標(${ANCHOR.x},${ANCHOR.y},${ANCHOR.z})付近に建てる。

重要な仕組み(必ず守ること):
- 「Builder」の職業を持つ市民は、それぞれ自分専用のBuilder's Hut(blockhutbuilder)が無いと一切働けない。1つのBuilder's Hutに対して実際に作業できる建築家は1人だけ。
- つまりBuilder職の市民がN人いるなら、blockhutbuilderもN棟必要(各自のレベルが低いうちは、それぞれが受けられる工事もレベル1までに限られる)。
- 同時に複数の建物を並行して建てたいなら、まずBuilder's Huts を複数棟 置いてrequestBuildし、手の空いている建築家(citizens配列のjob:"builder"で、実際に動いていなさそうな人)に割り当たるようにすること。
- Builder's Hut自体が無い/全員埋まっている状態で他の建物にrequestBuildしても、誰も着工できず止まったままになる。状況確認のため、定期的にopenRequestsで進捗を確認すること。`;
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

  const match = reply.match(/\{[\s\S]*\}/);
  if (!match) {
    console.log(`[${gov.name}] no JSON in reply:`, reply);
    return;
  }
  let parsed;
  try {
    parsed = JSON.parse(match[0]);
  } catch (e) {
    console.log(`[${gov.name}] JSON parse failed:`, e.message, reply);
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
  let context = `名前: ${citizen.name}, 職業: ${citizen.job}`;
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
      // We don't know which building a citizen works at from /status alone,
      // so just use the colony center as a best-effort guess for open
      // requests (works for town hall / builder's hut tests; refine later
      // with a dedicated endpoint that maps citizen -> work building).
      await citizenVoiceTurn(citizen, { x: colony.x, y: colony.y, z: colony.z });
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
