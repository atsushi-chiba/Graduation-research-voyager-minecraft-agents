const mineflayer = require("mineflayer");
const https = require("https");
const fs = require("fs");
const path = require("path");

const OPENROUTER_API_KEY = process.env.OPENROUTER_API_KEY;
const MODEL = "openai/gpt-5";
const MAX_TURNS_PER_AGENT = 30;
const MAX_RUNTIME_MS = 30 * 60 * 1000;
const FOOTPRINT = 5; // NxN footprint
const WALL_HEIGHT = 3;
const BLOCK_NAME = "oak_planks";

// name -> role ("lumberjack" or "builder")
const ROLES = {
  Woody: "lumberjack",
  Axel: "lumberjack",
  Daisy: "builder",
  Pico: "builder",
};
const NAMES = Object.keys(ROLES);
const BUILDERS = NAMES.filter((n) => ROLES[n] === "builder");
const LUMBERJACKS = NAMES.filter((n) => ROLES[n] === "lumberjack");

const startTime = Date.now();
const sharedChatLog = [];
const agents = {};
let ANCHOR = null;
let CHEST_POS = null;
let chestBusy = false; // mutex: only one bot may have the shared chest open at a time
let BLUEPRINT = null;

const PRIMITIVES_DIR = path.join(__dirname, "..", "..", "control_primitives");
const primitivesCode = fs
  .readdirSync(PRIMITIVES_DIR)
  .filter((f) => f.endsWith(".js"))
  .map((f) => fs.readFileSync(path.join(PRIMITIVES_DIR, f), "utf8"))
  .join("\n\n");

const CONTEXT_DIR = path.join(__dirname, "..", "..", "control_primitives_context");
const primitivesDocs = fs
  .readdirSync(CONTEXT_DIR)
  .filter((f) => f.endsWith(".js"))
  .map((f) => fs.readFileSync(path.join(CONTEXT_DIR, f), "utf8"))
  .join("\n\n");

// Custom helper exposed inside the eval sandbox. It reads module-level
// BLUEPRINT/CHEST_POS via normal JS closure scoping (direct eval shares the
// calling function's scope chain).
const villagePrimitivesCode = `
async function depositLogsToChest(bot, itemsObj) {
  while (chestBusy) { await new Promise((r) => setTimeout(r, 500)); }
  chestBusy = true;
  try {
    await depositItemIntoChest(bot, CHEST_POS, itemsObj);
  } finally {
    chestBusy = false;
  }
}

async function withdrawFromChest(bot, itemsObj) {
  while (chestBusy) { await new Promise((r) => setTimeout(r, 500)); }
  chestBusy = true;
  try {
    await getItemFromChest(bot, CHEST_POS, itemsObj);
  } finally {
    chestBusy = false;
  }
}

async function buildAssigned(bot, maxBlocks) {
  maxBlocks = maxBlocks || 5;
  const mine = BLUEPRINT.filter((b) => b.owner === bot.username && !b.done);
  let placedCount = 0;
  for (const item of mine) {
    if (placedCount >= maxBlocks) break;
    const targetVec = new Vec3(item.x, item.y, item.z);
    let block = bot.blockAt(targetVec);
    if (block && block.name === item.block) {
      item.done = true;
      continue;
    }
    // if something else (debris, another player's build, leftover block) is
    // sitting on the target spot, clear it ourselves before trying to place
    if (block && block.name !== "air" && block.name !== item.block) {
      try {
        await bot.dig(block);
      } catch (e) {}
      block = bot.blockAt(targetVec);
    }

    const dx = bot.entity.position.x - (item.x + 0.5);
    const dz = bot.entity.position.z - (item.z + 0.5);
    const horizDist = Math.sqrt(dx * dx + dz * dz);
    if (horizDist < 1.5) {
      try {
        const stepX = Math.floor(bot.entity.position.x + (dx === 0 ? 2 : Math.sign(dx) * 2));
        const stepZ = Math.floor(bot.entity.position.z + (dz === 0 ? 2 : Math.sign(dz) * 2));
        await bot.pathfinder.goto(new GoalNear(stepX, Math.floor(bot.entity.position.y), stepZ, 1));
      } catch (e) {}
    }
    try {
      await placeItem(bot, item.block, targetVec);
    } catch (e) {}
    // wait a moment so client-side prediction settles into the real,
    // server-confirmed block state before we trust what we see
    try {
      await bot.waitForTicks(10);
    } catch (e) {}
    block = bot.blockAt(targetVec);
    if (block && block.name === item.block) {
      item.done = true;
      placedCount++;
    }
  }
}
`;

function generateBlueprint(anchor) {
  const list = [];
  const N = FOOTPRINT;
  for (let y = 0; y < WALL_HEIGHT; y++) {
    for (let x = 0; x < N; x++) {
      for (let z = 0; z < N; z++) {
        const isPerimeter = x === 0 || x === N - 1 || z === 0 || z === N - 1;
        if (!isPerimeter) continue;
        if (x === 2 && z === 0 && y <= 1) continue; // doorway gap
        list.push({ x: anchor.x + x, y: anchor.y + y, z: anchor.z + z, block: BLOCK_NAME });
      }
    }
  }
  for (let x = 0; x < N; x++) {
    for (let z = 0; z < N; z++) {
      list.push({ x: anchor.x + x, y: anchor.y + WALL_HEIGHT, z: anchor.z + z, block: BLOCK_NAME });
    }
  }
  list.forEach((item, i) => {
    item.owner = BUILDERS[i % BUILDERS.length];
    item.done = false;
  });
  return list;
}

function isPlacedNow(item) {
  // Never trust a cached "done" flag alone - a bot's own placeItem call can
  // optimistically/locally believe a placement succeeded even when the server
  // silently rejected it (e.g. obstruction, desync). Always re-verify against
  // a bot that's actually nearby (real chunk data), and only report "done" if
  // a live check confirms it. If no bot is currently close enough to verify,
  // fall back to the last known cached value instead of assuming success.
  let sawIt = false;
  let confirmed = false;
  for (const name of NAMES) {
    const bot = agents[name] && agents[name].bot;
    if (!bot || !bot.entity) continue;
    const pos = new (require("vec3").Vec3)(item.x, item.y, item.z);
    if (bot.entity.position.distanceTo(pos) > 24) continue;
    sawIt = true;
    const block = bot.blockAt(pos);
    if (block && block.name === item.block) {
      confirmed = true;
      break;
    }
  }
  if (sawIt) {
    item.done = confirmed;
    return confirmed;
  }
  return item.done;
}

function remainingFor(username) {
  if (!BLUEPRINT) return "?";
  return BLUEPRINT.filter((b) => b.owner === username && !isPlacedNow(b)).length;
}

function totalRemaining() {
  if (!BLUEPRINT) return "?";
  return BLUEPRINT.filter((b) => !isPlacedNow(b)).length;
}

function buildSystemPrompt(selfName) {
  const role = ROLES[selfName];
  const teammates = NAMES.filter((n) => n !== selfName);
  const common =
    `You are '${selfName}', part of a 4-person village-building team (${NAMES.join(", ")}) ` +
    `with two roles: lumberjacks (${LUMBERJACKS.join(", ")}) chop wood and deposit logs into a shared chest; ` +
    `builders (${BUILDERS.join(", ")}) withdraw logs from the chest, craft them into ${BLOCK_NAME}, ` +
    "and place them according to a pre-assigned house blueprint.\n\n" +
    "There is a shared chest used by all 4 of you, so only ONE of you can access it at a time - ALWAYS use " +
    "`await depositLogsToChest(bot, {oak_log: N})` or `await withdrawFromChest(bot, {oak_log: N})` " +
    "(never call depositItemIntoChest/getItemFromChest directly) - these automatically wait their turn and avoid " +
    "conflicts. N should match what's actually in your inventory / what you need (check [STATE]).\n\n" +
    `Talk to your teammates in Japanese to coordinate and narrate progress.\n\n` +
    "IMPORTANT: Always write the SAY line in natural, casual Japanese. Never use English in the SAY line.\n\n" +
    "Respond in EXACTLY this format:\n" +
    "SAY: <one short casual sentence in Japanese, under 40 characters, no line breaks>\n" +
    "CODE:\n```javascript\nasync function act(bot) {\n  // your action this turn\n}\n```\n" +
    "Omit the CODE block only if there's truly nothing to do this turn.\n\n";

  if (role === "lumberjack") {
    return (
      common +
      "Your job: find and chop any kind of log (oak_log, jungle_log, birch_log, etc) with mineBlock(bot, name, count), " +
      "then walk to CHEST_POS and call `await depositLogsToChest(bot, {<log_name>: <count you have>});` " +
      "Repeat. Coordinate with your fellow lumberjack to search different directions so you don't compete for the same trees.\n\n" +
      "Other available functions/skills:\n" +
      primitivesDocs
    );
  }
  return (
    common +
    "Your job each turn: if you're low on oak_planks, first withdraw logs from the chest with " +
    "`await withdrawFromChest(bot, {oak_log: 16});` then craft them into planks with " +
    "`await craftItem(bot, 'oak_planks', <number_of_logs>);` (this needs no crafting table). " +
    "Once you have planks, call `await buildAssigned(bot, 5);` to place up to 5 of your assigned house blocks - " +
    "it automatically finds your next unplaced positions, you don't need coordinates.\n\n" +
    "Other available functions/skills:\n" +
    primitivesDocs
  );
}

function evaluateCode(bot, code) {
  return new Promise((resolve) => {
    const mcData = require("minecraft-data")(bot.version);
    const { Movements, goals } = require("mineflayer-pathfinder");
    const {
      Goal,
      GoalBlock,
      GoalNear,
      GoalXZ,
      GoalNearXZ,
      GoalY,
      GoalGetToBlock,
      GoalLookAtBlock,
      GoalBreakBlock,
      GoalCompositeAny,
      GoalCompositeAll,
      GoalInvert,
      GoalFollow,
      GoalPlaceBlock,
    } = goals;
    const { Vec3 } = require("vec3");
    const movements = new Movements(bot, mcData);
    bot.pathfinder.setMovements(movements);

    let _craftItemFailCount = 0;
    let _killMobFailCount = 0;
    let _mineBlockFailCount = 0;
    let _placeItemFailCount = 0;
    let _smeltItemFailCount = 0;

    let settled = false;
    const safetyTimeout = setTimeout(() => {
      if (!settled) {
        settled = true;
        try {
          bot.pathfinder.setGoal(null);
        } catch (_) {}
        resolve("Action timed out after 45s and was stopped.");
      }
    }, 45000);

    (async () => {
      try {
        await eval(
          "(async () => {" +
            primitivesCode +
            "\n" +
            villagePrimitivesCode +
            "\n" +
            code +
            "\nawait act(bot);" +
            "})()"
        );
        if (!settled) {
          settled = true;
          clearTimeout(safetyTimeout);
          resolve(null);
        }
      } catch (err) {
        if (!settled) {
          settled = true;
          clearTimeout(safetyTimeout);
          resolve(err.message || String(err));
        }
      }
    })();
  });
}

function buildStateContext(bot, selfName) {
  const pos = bot.entity.position;
  const inventory =
    bot.inventory
      .items()
      .map((i) => `${i.name}x${i.count}`)
      .join(", ") || "empty";
  const recentChat =
    sharedChatLog
      .slice(-6)
      .map((c) => `${c.who}: ${c.text}`)
      .join(" | ") || "none yet";
  const role = ROLES[selfName];

  let progress;
  if (role === "builder") {
    progress = `your remaining assigned blocks: ${remainingFor(selfName)} | house total remaining: ${totalRemaining()}`;
  } else {
    progress = `house total remaining: ${totalRemaining()}`;
  }

  return (
    `[STATE] you are ${selfName} (${role}) | position: ${pos.x.toFixed(1)},${pos.y.toFixed(1)},${pos.z.toFixed(1)} | ` +
    `chest at: ${CHEST_POS.x},${CHEST_POS.y},${CHEST_POS.z} | your inventory: ${inventory} | ${progress} | ` +
    `recent chat: ${recentChat}`
  );
}

function askLLM(agent, userContent) {
  return new Promise((resolve, reject) => {
    agent.history.push({ role: "user", content: userContent });
    const payload = JSON.stringify({
      model: MODEL,
      messages: agent.history.slice(0, 1).concat(agent.history.slice(-10)),
    });
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
        res.on("data", (chunk) => (data += chunk));
        res.on("end", () => {
          try {
            const parsed = JSON.parse(data);
            const reply = parsed.choices?.[0]?.message?.content?.trim();
            if (reply) {
              agent.history.push({ role: "assistant", content: reply });
              resolve(reply);
            } else {
              reject(new Error("No reply: " + data));
            }
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

function parseReply(reply) {
  const sayMatch = reply.match(/SAY:\s*(.+)/);
  const codeMatch = reply.match(/```javascript\n([\s\S]*?)```/);
  const say = sayMatch ? sayMatch[1].trim() : reply.replace(/\s*\n+\s*/g, " ").trim();
  const code = codeMatch ? codeMatch[1].trim() : null;
  return { say, code };
}

async function turn(name) {
  const agent = agents[name];
  if (!agent || agent.done) return;

  if (BLUEPRINT && totalRemaining() === 0) {
    agent.done = true;
    agent.sayToChat("村が完成した！やったね！");
    console.log(`[${name}] village complete, stopping.`);
    return;
  }
  if (agent.turns >= MAX_TURNS_PER_AGENT || Date.now() - startTime > MAX_RUNTIME_MS) {
    agent.done = true;
    agent.sayToChat("ちょっと休憩するわ、ここで一旦終わるね");
    console.log(`[${name}] hit turn/time limit, stopping.`);
    return;
  }
  agent.turns++;
  agent.busy = true;
  try {
    const state = buildStateContext(agent.bot, name);
    console.log(`[${name}] turn ${agent.turns} state:`, state);
    const reply = await askLLM(agent, `${state}\nWhat do you do next?`);
    const { say, code } = parseReply(reply);
    if (say) agent.sayToChat(say.slice(0, 200));
    if (code) {
      console.log(`[${name}] EXECUTING CODE:\n` + code);
      const err = await evaluateCode(agent.bot, code);
      if (err) {
        console.log(`[${name}] CODE ERROR:`, err);
      }
    } else {
      console.log(`[${name}] no code this turn`);
    }
  } catch (e) {
    console.log(`[${name}] turn error:`, e.message);
  } finally {
    agent.busy = false;
  }
  if (!agent.done) {
    setTimeout(() => turn(name), 4500);
  }
}

function spawnAgent(name, delayMs) {
  const bot = mineflayer.createBot({
    host: "localhost",
    port: 25565,
    username: name,
  });

  agents[name] = {
    bot,
    history: [{ role: "system", content: buildSystemPrompt(name) }],
    busy: false,
    turns: 0,
    done: false,
  };

  bot.once("spawn", async () => {
    const { pathfinder } = require("mineflayer-pathfinder");
    const tool = require("mineflayer-tool").plugin;
    const collectBlock = require("mineflayer-collectblock").plugin;
    bot.loadPlugin(pathfinder);
    bot.loadPlugin(tool);
    bot.loadPlugin(collectBlock);
    require(path.join(__dirname, "lib", "skillLoader")).inject(bot);

    const _rawChat = bot.chat.bind(bot);
    let lastChatTime = 0;
    let lastChatMsg = null;
    agents[name].sayToChat = (msg) => {
      const now = Date.now();
      if (msg === lastChatMsg && now - lastChatTime < 3000) return;
      if (now - lastChatTime < 600) return;
      lastChatTime = now;
      lastChatMsg = msg;
      _rawChat(msg);
    };
    bot.chat = (msg) => console.log(`[${name}] (internal, not spoken) ${msg}`);

    console.log(`${name} (${ROLES[name]}) spawned at`, bot.entity.position);

    setTimeout(() => {
      if (!ANCHOR) {
        ANCHOR = {
          x: parseInt(process.env.ANCHOR_X, 10),
          y: parseInt(process.env.ANCHOR_Y, 10),
          z: parseInt(process.env.ANCHOR_Z, 10),
        };
        CHEST_POS = new (require("vec3").Vec3)(
          parseInt(process.env.CHEST_X, 10),
          parseInt(process.env.CHEST_Y, 10),
          parseInt(process.env.CHEST_Z, 10)
        );
        BLUEPRINT = generateBlueprint(ANCHOR);
        const alreadyDone = BLUEPRINT.filter((b) => isPlacedNow(b)).length;
        console.log(
          "ANCHOR", ANCHOR, "CHEST_POS", CHEST_POS, "blueprint size", BLUEPRINT.length,
          "already placed:", alreadyDone
        );
      }
      setTimeout(() => turn(name), 500);
    }, delayMs);
  });

  bot.on("chat", (username, message) => {
    if (NAMES.includes(username)) {
      sharedChatLog.push({ who: username, text: message, t: Date.now() });
    }
  });

  bot.on("error", (e) => console.log(`[${name}] ERROR`, e.message));
  bot.on("kicked", (reason) => console.log(`[${name}] KICKED:`, reason));
  bot.on("end", () => console.log(`[${name}] disconnected`));
}

process.on("uncaughtException", (err) => {
  console.log("UNCAUGHT EXCEPTION:", err && err.message);
});
process.on("unhandledRejection", (err) => {
  console.log("UNHANDLED REJECTION:", err && err.message);
});

NAMES.forEach((name, i) => spawnAgent(name, 3000 + i * 500));
