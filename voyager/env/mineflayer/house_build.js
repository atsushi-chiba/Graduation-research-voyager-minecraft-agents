const mineflayer = require("mineflayer");
const https = require("https");
const fs = require("fs");
const path = require("path");

const OPENROUTER_API_KEY = process.env.OPENROUTER_API_KEY;
const MODEL = "openai/gpt-5";
const NAMES = ["Woody", "Axel"];
const MAX_TURNS_PER_AGENT = 25;
const MAX_RUNTIME_MS = 25 * 60 * 1000;
const FOOTPRINT = 5; // NxN footprint
const WALL_HEIGHT = 3; // layers of walls before the roof
const BLOCK_NAME = "oak_planks";

const startTime = Date.now();
const sharedChatLog = [];
const agents = {};
let BLUEPRINT = null;
let ANCHOR = null;

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

// Custom helper exposed inside the eval sandbox (alongside the official control
// primitives). It reads the module-level BLUEPRINT/Vec3 via normal JS closure
// scoping, since `eval()` inside evaluateCode() shares that function's scope chain.
const housePrimitivesCode = `
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
    // if we're standing too close to (or on top of) the target column, the bot's own
    // hitbox can silently block the placement; step a couple blocks away first.
    const dx = bot.entity.position.x - (item.x + 0.5);
    const dz = bot.entity.position.z - (item.z + 0.5);
    const horizDist = Math.sqrt(dx * dx + dz * dz);
    if (horizDist < 1.5) {
      try {
        const stepX = Math.floor(bot.entity.position.x + (dx === 0 ? 2 : Math.sign(dx) * 2));
        const stepZ = Math.floor(bot.entity.position.z + (dz === 0 ? 2 : Math.sign(dz) * 2));
        await bot.pathfinder.goto(new GoalNear(stepX, Math.floor(bot.entity.position.y), stepZ, 1));
      } catch (e) {
        // ignore, try placing anyway
      }
    }
    try {
      await placeItem(bot, item.block, targetVec);
    } catch (e) {
      // ignore, retry next turn
    }
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
        // leave a 1-wide, 2-tall doorway gap on the south wall (z === 0) at x === 2
        if (x === 2 && z === 0 && y <= 1) continue;
        list.push({ x: anchor.x + x, y: anchor.y + y, z: anchor.z + z, block: BLOCK_NAME });
      }
    }
  }
  // flat roof
  for (let x = 0; x < N; x++) {
    for (let z = 0; z < N; z++) {
      list.push({ x: anchor.x + x, y: anchor.y + WALL_HEIGHT, z: anchor.z + z, block: BLOCK_NAME });
    }
  }
  list.forEach((item, i) => {
    item.owner = NAMES[i % 2];
    item.done = false;
  });
  return list;
}

function isPlacedNow(item) {
  if (item.done) return true;
  // check via whichever bot is actually nearby (closer = more likely to have that
  // chunk loaded); checking a hardcoded bot that's far away can wrongly report
  // "not placed" forever even after the block is really there.
  for (const name of NAMES) {
    const bot = agents[name] && agents[name].bot;
    if (!bot || !bot.entity) continue;
    const pos = new (require("vec3").Vec3)(item.x, item.y, item.z);
    const dist = bot.entity.position.distanceTo(pos);
    if (dist > 32) continue;
    const block = bot.blockAt(pos);
    if (block && block.name === item.block) {
      item.done = true;
      return true;
    }
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

function buildSystemPrompt(selfName, otherName) {
  return (
    `You are '${selfName}', working as a TEAM with '${otherName}' to build a small house together out of ${BLOCK_NAME}. ` +
    "A blueprint already exists and blocks are pre-assigned between you two - you don't need to invent positions. " +
    "Each turn, just call `await buildAssigned(bot, 5);` inside your CODE block - it automatically finds YOUR next " +
    "unplaced assigned blocks and places them (up to 5 per turn). You don't need any other arguments or logic.\n\n" +
    "Each turn you get a [STATE] block with your real position, inventory, your remaining assigned block count, " +
    `${otherName}'s remaining count, total remaining for the whole house, and recent chat. Only state facts that match [STATE].\n\n` +
    `Talk to ${otherName} in Japanese to coordinate and narrate progress (e.g. "壁できてきた、あと10個！").\n\n` +
    "IMPORTANT: Always write the SAY line in natural, casual Japanese. Never use English in the SAY line.\n\n" +
    "Respond in EXACTLY this format:\n" +
    "SAY: <one short casual sentence in Japanese, under 40 characters, no line breaks>\n" +
    "CODE:\n```javascript\nasync function act(bot) {\n  await buildAssigned(bot, 5);\n}\n```\n" +
    "Only omit the CODE block if [STATE] shows your own remaining count is already 0.\n\n" +
    'Stop condition: once [STATE] shows total remaining for the house == 0, say something like "完成した！" and omit the CODE block from then on.\n\n' +
    "Other available functions/skills you can call inside act(bot) if ever needed:\n" +
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
            housePrimitivesCode +
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

function buildStateContext(bot, selfName, otherName) {
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

  return (
    `[STATE] you are ${selfName} | position: ${pos.x.toFixed(1)},${pos.y.toFixed(1)},${pos.z.toFixed(1)} | ` +
    `your inventory: ${inventory} | your remaining: ${remainingFor(selfName)} | ` +
    `${otherName}'s remaining: ${remainingFor(otherName)} | total remaining: ${totalRemaining()} | ` +
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
  const otherName = NAMES.find((n) => n !== name);

  if (BLUEPRINT && totalRemaining() === 0) {
    agent.done = true;
    agent.sayToChat("完成した！やったね！");
    console.log(`[${name}] house complete, stopping.`);
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
    const state = buildStateContext(agent.bot, name, otherName);
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
    setTimeout(() => turn(name), 4000);
  }
}

function spawnAgent(name, otherName, delayMs) {
  const bot = mineflayer.createBot({
    host: "localhost",
    port: 25565,
    username: name,
  });

  agents[name] = {
    bot,
    history: [{ role: "system", content: buildSystemPrompt(name, otherName) }],
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

    console.log(`${name} spawned at`, bot.entity.position);

    // give time for an external teleport (console `/tp`) to land both bots on
    // the build site before computing the anchor point
    setTimeout(() => {
      if (!ANCHOR) {
        if (process.env.ANCHOR_X !== undefined) {
          ANCHOR = {
            x: parseInt(process.env.ANCHOR_X, 10),
            y: parseInt(process.env.ANCHOR_Y, 10),
            z: parseInt(process.env.ANCHOR_Z, 10),
          };
        } else {
          const pos = bot.entity.position;
          ANCHOR = { x: Math.floor(pos.x), y: Math.floor(pos.y), z: Math.floor(pos.z) };
        }
        BLUEPRINT = generateBlueprint(ANCHOR);
        const alreadyDone = BLUEPRINT.filter((b) => isPlacedNow(b)).length;
        console.log(
          "ANCHOR set to", ANCHOR, "blueprint size", BLUEPRINT.length,
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

spawnAgent(NAMES[0], NAMES[1], 3000);
spawnAgent(NAMES[1], NAMES[0], 3500);
