const mineflayer = require("mineflayer");
const https = require("https");
const fs = require("fs");
const path = require("path");

const OPENROUTER_API_KEY = process.env.OPENROUTER_API_KEY;
const MODEL = "openai/gpt-5";
const GOAL_LOGS = 64; // 1 full stack, combined between both agents
const MAX_TURNS_PER_AGENT = 30;
const MAX_RUNTIME_MS = 25 * 60 * 1000;

const NAMES = ["Woody", "Axel"];
const startTime = Date.now();
const sharedChatLog = [];
const agents = {};

// ---- load control primitives (same JS Voyager's action agent can use) ----
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

function buildSystemPrompt(selfName, otherName) {
  return (
    `You are '${selfName}', a Minecraft bot working as a TEAM with '${otherName}' on one shared goal: ` +
    `collect a COMBINED total of ${GOAL_LOGS} wood logs (1 full stack) between the two of you. It does not matter ` +
    `how the logs are split between you, only that the combined total reaches ${GOAL_LOGS}.\n\n` +
    "Each turn you are given a [STATE] block with your real position, nearby players/blocks, your inventory, " +
    "your log count, your teammate's log count, the combined total, and recent chat between you two. " +
    "Only state facts that match [STATE]. Never invent coordinates, items, or progress numbers.\n\n" +
    `Coordinate with ${otherName} over chat: agree on splitting up (e.g. different directions) so you don't compete ` +
    "for the same trees, and report your progress occasionally (e.g. \"6本とれた、もっと北に行く\").\n\n" +
    "IMPORTANT: Always write the SAY line in natural, casual Japanese (日本語). Never use English in the SAY line.\n\n" +
    "You can take real actions by writing JavaScript code using the Mineflayer API below. " +
    "Respond in EXACTLY this format:\n" +
    "SAY: <one short casual sentence in Japanese, under 40 characters, no line breaks>\n" +
    "CODE:\n```javascript\nasync function act(bot) {\n  // optional. Only include this block if you are taking a physical action this turn.\n}\n```\n" +
    "Omit the CODE block entirely (just SAY:) if this turn is pure coordination/chat with no action.\n\n" +
    "Movement safety rules: prefer moving to a SPECIFIC nearby block/position over open-ended wandering. " +
    "Avoid exploreUntil with large/unbounded search unless necessary. Keep each action short and concrete.\n\n" +
    `Stop condition: once [STATE] shows combined log count >= ${GOAL_LOGS}, say something like "We did it!" and ` +
    "omit the CODE block from then on.\n\n" +
    "Available functions/skills you can call inside act(bot):\n" +
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
          "(async () => {" + primitivesCode + "\n" + code + "\nawait act(bot);" + "})()"
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

function countLogs(bot) {
  if (!bot || !bot.inventory) return 0;
  return bot.inventory
    .items()
    .filter((i) => i.name.endsWith("_log"))
    .reduce((s, i) => s + i.count, 0);
}

function totalLogs() {
  return NAMES.reduce((s, n) => s + countLogs(agents[n] && agents[n].bot), 0);
}

function buildStateContext(bot, selfName, otherName) {
  const pos = bot.entity.position;
  const nearbyPlayers = Object.values(bot.players)
    .filter((p) => p.entity && p.username !== bot.username)
    .map((p) => {
      const d = p.entity.position.distanceTo(pos);
      return `${p.username} (${d.toFixed(1)}m away, at ${p.entity.position.x.toFixed(1)},${p.entity.position.y.toFixed(1)},${p.entity.position.z.toFixed(1)})`;
    });
  const nearbyBlocks = bot
    .findBlocks({
      matching: (b) => b && b.name !== "air" && b.name !== "cave_air",
      maxDistance: 8,
      count: 8,
    })
    .map((p) => bot.blockAt(p).name);
  const inventory =
    bot.inventory
      .items()
      .map((i) => `${i.name}x${i.count}`)
      .join(", ") || "empty";

  const myLogs = countLogs(bot);
  const otherBot = agents[otherName] && agents[otherName].bot;
  const otherLogs = countLogs(otherBot);
  const recentChat =
    sharedChatLog
      .slice(-6)
      .map((c) => `${c.who}: ${c.text}`)
      .join(" | ") || "none yet";

  return (
    `[STATE] you are ${selfName} | position: ${pos.x.toFixed(1)},${pos.y.toFixed(1)},${pos.z.toFixed(1)} | ` +
    `time: ${bot.time.timeOfDay < 13000 ? "day" : "night"} | health: ${bot.health}/20 | ` +
    `nearby players: ${nearbyPlayers.join("; ") || "none"} | ` +
    `nearby blocks (within 8m): ${[...new Set(nearbyBlocks)].join(", ") || "none"} | ` +
    `your inventory: ${inventory} | your logs: ${myLogs} | ${otherName}'s logs: ${otherLogs} | ` +
    `combined: ${myLogs + otherLogs}/${GOAL_LOGS} | recent chat: ${recentChat}`
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

  if (totalLogs() >= GOAL_LOGS) {
    agent.done = true;
    agent.sayToChat(`目標達成！合計${totalLogs()}本集まったよ！`);
    console.log(`[${name}] goal reached, stopping.`);
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
    function sayToChat(msg) {
      const now = Date.now();
      if (msg === lastChatMsg && now - lastChatTime < 3000) return;
      if (now - lastChatTime < 600) return;
      lastChatTime = now;
      lastChatMsg = msg;
      _rawChat(msg);
    }
    agents[name].sayToChat = sayToChat;
    // control_primitives / mineflayer-collectblock call bot.chat() directly with
    // hardcoded English status strings (e.g. "Collect finish!"). Redirect those to
    // the log instead of real game chat, so only the LLM's Japanese SAY lines are
    // actually spoken in-game.
    bot.chat = (msg) => {
      console.log(`[${name}] (internal, not spoken) ${msg}`);
    };

    console.log(`${name} spawned at`, bot.entity.position);
    setTimeout(() => turn(name), delayMs);
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

spawnAgent(NAMES[0], NAMES[1], 2000);
spawnAgent(NAMES[1], NAMES[0], 5000);
