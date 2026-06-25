const mineflayer = require("mineflayer");
const https = require("https");
const fs = require("fs");
const path = require("path");

const OPENROUTER_API_KEY = process.env.OPENROUTER_API_KEY;
const MODEL = "openai/gpt-5";

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

const history = [
  {
    role: "system",
    content:
      "You are 'companion', a friendly Minecraft player chatting and acting in-game.\n" +
      "Each user message is prefixed with a [STATE] block describing your ACTUAL current position, nearby players/blocks, inventory, health, and time of day. " +
      "Only state facts that match this [STATE] block. Never invent coordinates, items, or surroundings that aren't in it.\n\n" +
      "You can take real actions by writing JavaScript code using the Mineflayer API below. " +
      "Respond in EXACTLY this format:\n" +
      "SAY: <one short casual sentence, in the player's language, under 80 chars, no line breaks>\n" +
      "CODE:\n```javascript\nasync function act(bot) {\n  // optional. Only include this block if the player asked you to DO something.\n}\n```\n" +
      "Omit the CODE block entirely (just SAY:) for plain conversation that requires no action.\n\n" +
      "Movement safety rules: prefer moving to a SPECIFIC nearby block/position (small GoalNear/GoalGetToBlock within ~16 blocks) over open-ended wandering. " +
      "Avoid exploreUntil with large/unbounded search unless explicitly necessary, since long erratic pathing is unreliable. Keep each action short and concrete.\n\n" +
      "Available functions/skills you can call inside act(bot):\n" +
      primitivesDocs,
  },
];

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

function buildStateContext(bot) {
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

  return (
    `[STATE] your position: ${pos.x.toFixed(1)},${pos.y.toFixed(1)},${pos.z.toFixed(1)} | ` +
    `time: ${bot.time.timeOfDay < 13000 ? "day" : "night"} | ` +
    `health: ${bot.health}/20 | ` +
    `nearby players: ${nearbyPlayers.join("; ") || "none"} | ` +
    `nearby blocks (within 8m): ${[...new Set(nearbyBlocks)].join(", ") || "none"} | ` +
    `your inventory: ${inventory}`
  );
}

function askLLM(playerMessage) {
  return new Promise((resolve, reject) => {
    history.push({ role: "user", content: playerMessage });
    const payload = JSON.stringify({
      model: MODEL,
      messages: history.slice(0, 1).concat(history.slice(-10)),
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
              history.push({ role: "assistant", content: reply });
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

let busy = false;
let reconnectDelay = 2000;

process.on("uncaughtException", (err) => {
  console.log("UNCAUGHT EXCEPTION:", err && err.message);
  busy = false;
});
process.on("unhandledRejection", (err) => {
  console.log("UNHANDLED REJECTION:", err && err.message);
  busy = false;
});

function connect() {
  const bot = mineflayer.createBot({
    host: "localhost",
    port: 25565,
    username: "companion",
  });

  bot.once("spawn", async () => {
    console.log("companion spawned");
    reconnectDelay = 2000;
    const { pathfinder } = require("mineflayer-pathfinder");
    const tool = require("mineflayer-tool").plugin;
    const collectBlock = require("mineflayer-collectblock").plugin;
    bot.loadPlugin(pathfinder);
    bot.loadPlugin(tool);
    bot.loadPlugin(collectBlock);
    require(path.join(__dirname, "lib", "skillLoader")).inject(bot);

    // rate-limit outgoing chat to avoid anti-spam kicks from runaway action code
    const _rawChat = bot.chat.bind(bot);
    let lastChatTime = 0;
    let lastChatMsg = null;
    bot.chat = (msg) => {
      const now = Date.now();
      if (msg === lastChatMsg && now - lastChatTime < 3000) return;
      if (now - lastChatTime < 600) return;
      lastChatTime = now;
      lastChatMsg = msg;
      _rawChat(msg);
    };

    bot.chat("Hi! I'm companion. I can chat AND actually do things now. Ask me to fetch wood, etc!");
  });

  bot.on("chat", async (username, message) => {
    if (username === bot.username) return;
    if (["Woody", "Axel"].includes(username)) return; // ignore chatter between teammate bots
    if (message.startsWith("/")) return;
    if (busy) {
      bot.chat("Hold on, still doing the last thing...");
      return;
    }
    busy = true;
    try {
      const stateBlock = buildStateContext(bot);
      const reply = await askLLM(`${stateBlock}\n${username}: ${message}`);
      const { say, code } = parseReply(reply);
      if (say) bot.chat(say.slice(0, 200));
      if (code) {
        console.log("EXECUTING CODE:\n" + code);
        const err = await evaluateCode(bot, code);
        if (err) {
          console.log("CODE ERROR:", err);
          bot.chat(`Oops, that didn't work: ${String(err).slice(0, 100)}`);
        }
      }
    } catch (e) {
      console.log("LLM error:", e.message);
    } finally {
      busy = false;
    }
  });

  bot.on("error", (e) => console.log("ERROR", e.message));
  bot.on("kicked", (reason) => console.log("KICKED:", reason));
  bot.on("end", () => {
    console.log("companion disconnected, reconnecting in", reconnectDelay, "ms");
    busy = false;
    setTimeout(connect, reconnectDelay);
    reconnectDelay = Math.min(reconnectDelay * 2, 30000);
  });
}

connect();
