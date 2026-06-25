const mineflayer = require("mineflayer");
const bot = mineflayer.createBot({ host: "localhost", port: 25565, username: "tester13" });

bot.on("itemDrop", (entity) => {
  console.log("RAW itemDrop:", entity.name, entity.position);
});

bot.once("spawn", async () => {
  const { pathfinder, Movements, goals } = require("mineflayer-pathfinder");
  bot.loadPlugin(pathfinder);
  const mcData = require("minecraft-data")(bot.version);
  bot.pathfinder.setMovements(new Movements(bot, mcData));
  await bot.waitForTicks(20);

  const blockByName = mcData.blocksByName["oak_log"];
  const blocks = bot.findBlocks({ matching: [blockByName.id], maxDistance: 32, count: 1024 });
  if (blocks.length === 0) { console.log("no blocks found"); process.exit(0); }
  const target = blocks[0];
  console.log("DBG target:", target, "name before:", bot.blockAt(target).name);

  await bot.pathfinder.goto(new goals.GoalLookAtBlock(target, bot.world));
  console.log("DBG after goto, pos:", bot.entity.position, "dist:", bot.entity.position.distanceTo(target));
  try {
    await bot.dig(bot.blockAt(target));
    console.log("DBG dig() resolved");
  } catch(e) {
    console.log("DBG dig() THREW:", e.message);
  }
  console.log("DBG block name after dig:", bot.blockAt(target).name);
  await bot.waitForTicks(40);
  console.log("DBG inventory:", bot.inventory.items().map(i=>i.name));
  console.log("DBG block name final:", bot.blockAt(target).name);
  process.exit(0);
});
