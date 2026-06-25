const mineflayer = require("mineflayer");
const bot = mineflayer.createBot({ host: "localhost", port: 25565, username: "tester12" });
let digTargetPos = null;

bot.on("itemDrop", (entity) => {
  if (digTargetPos) {
    const center = digTargetPos.offset(0.5, 0.5, 0.5);
    const dist = entity.position.distanceTo(center);
    console.log("DBG itemDrop pos:", entity.position, "block center:", center, "dist:", dist, "PASSES 0.5 FILTER:", dist <= 0.5);
  }
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
  digTargetPos = target;
  console.log("DBG target:", target, "dist from bot:", bot.entity.position.distanceTo(target));

  await bot.pathfinder.goto(new goals.GoalLookAtBlock(target, bot.world));
  console.log("DBG after goto, pos:", bot.entity.position, "dist to target:", bot.entity.position.distanceTo(target));
  await bot.dig(bot.blockAt(target));
  console.log("DBG dig done, waiting for itemDrop events...");
  await bot.waitForTicks(40);
  console.log("DBG inventory:", bot.inventory.items().map(i=>i.name));
  process.exit(0);
});
