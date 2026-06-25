const mineflayer = require("mineflayer");
const https = require("https");

const OPENROUTER_API_KEY = process.env.OPENROUTER_API_KEY;
const MODEL = "openai/gpt-5";
const NAMES = ["Woody", "Axel"];
const TAUNT_INTERVAL_MS = 6000;

const agents = {};
let duelOver = false;

function buildSystemPrompt(selfName, otherName) {
  return (
    `You are '${selfName}', locked in a Minecraft PvP duel to the death against '${otherName}'. ` +
    "Combat itself (chasing, swinging your sword) is handled automatically for you - you do not write any code or control movement. " +
    "Your only job each turn is to say one short, dramatic/aggressive line in Japanese reacting to the fight " +
    "(taunt, battle cry, reaction to taking damage, reaction to landing a hit, etc), based on the real [STATE] you're given " +
    "(your health, your opponent's health, distance between you). Only react to what [STATE] actually shows - " +
    "don't claim damage or kills that haven't happened.\n\n" +
    "Respond in EXACTLY this format, nothing else:\n" +
    "SAY: <one short line in natural Japanese, under 40 characters, no line breaks>\n\n" +
    "Never use English. Stay in character as a confident warrior."
  );
}

function buildState(bot, selfName, otherName) {
  const otherBot = agents[otherName].bot;
  const dist =
    bot.entity && otherBot.entity
      ? bot.entity.position.distanceTo(otherBot.entity.position)
      : -1;
  return (
    `[STATE] your health: ${bot.health.toFixed(1)}/20 | ` +
    `${otherName}'s health: ${otherBot.health.toFixed(1)}/20 | ` +
    `distance: ${dist.toFixed(1)}m`
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
  return sayMatch ? sayMatch[1].trim() : reply.replace(/\s*\n+\s*/g, " ").trim();
}

function onDeath(loserName, winnerName) {
  if (duelOver) return;
  duelOver = true;
  const winner = agents[winnerName];
  const loser = agents[loserName];
  try {
    winner.bot.pvp.stop();
  } catch (_) {}
  try {
    loser.bot.pvp.stop();
  } catch (_) {}
  winner.sayToChat(`${loserName}を倒した！俺の勝ちだ！`);
  console.log(`=== ${winnerName} WINS, ${loserName} was defeated ===`);
}

async function tauntLoop(name, otherName) {
  if (duelOver) return;
  const agent = agents[name];
  try {
    const state = buildState(agent.bot, name, otherName);
    console.log(`[${name}] ${state}`);
    const reply = await askLLM(agent, state);
    const say = parseReply(reply);
    if (say) agent.sayToChat(say.slice(0, 100));
  } catch (e) {
    console.log(`[${name}] taunt error:`, e.message);
  }
  if (!duelOver) setTimeout(() => tauntLoop(name, otherName), TAUNT_INTERVAL_MS);
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
    dead: false,
  };

  bot.once("spawn", async () => {
    const { pathfinder } = require("mineflayer-pathfinder");
    const pvpPlugin = require("mineflayer-pvp").plugin;
    bot.loadPlugin(pathfinder);
    bot.loadPlugin(pvpPlugin);

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
    // suppress any internal/library chat noise, keep only our own taunts going to real chat
    bot.chat = (msg) => console.log(`[${name}] (internal, not spoken) ${msg}`);

    console.log(`${name} spawned at`, bot.entity.position);

    setTimeout(() => {
      const sword = bot.inventory.items().find((i) => i.name.endsWith("_sword"));
      if (sword) {
        bot.equip(sword, "hand").catch((e) => console.log(`[${name}] equip failed:`, e.message));
      } else {
        console.log(`[${name}] no sword found in inventory!`);
      }
    }, 1000);

    setTimeout(() => {
      function combatTick() {
        if (duelOver) return;
        const otherBot = agents[otherName] && agents[otherName].bot;
        if (otherBot && otherBot.entity && bot.entity && !agents[name].dead) {
          if (!bot.pvp.target) {
            bot.pvp.attack(otherBot.entity);
          }
        }
        if (!duelOver) setTimeout(combatTick, 800);
      }
      combatTick();
      tauntLoop(name, otherName);
    }, delayMs);
  });

  bot.on("health", () => {
    if (bot.health <= 0 && !agents[name].dead) {
      agents[name].dead = true;
      onDeath(name, otherName);
    }
  });

  bot.on("death", () => {
    if (!agents[name].dead) {
      agents[name].dead = true;
      onDeath(name, otherName);
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

spawnAgent(NAMES[0], NAMES[1], 2500);
spawnAgent(NAMES[1], NAMES[0], 4000);
