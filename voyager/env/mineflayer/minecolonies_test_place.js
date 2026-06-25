// Minimal experiment: can a plain mineflayer bot place a MineColonies hut
// block (the Town Hall) just like a normal block placement, with no GUI
// interaction? If so, building *placement* (where the colony goes) can be
// driven by the same `placeItem`-style primitives Voyager already uses,
// while the colony's own Builder NPC does the actual construction.
const mineflayer = require("mineflayer");
const { Vec3 } = require("vec3");

const HOST = "localhost";
const PORT = 25566; // the MineColonies/Forge server, separate from the vanilla one on 25565
const USERNAME = "Woody";
const HUT_ITEM = "minecolonies:blockhuttownhall";

const bot = mineflayer.createBot({ host: HOST, port: PORT, username: USERNAME });

bot.on("error", (e) => console.log("ERROR", e.message));
bot.on("kicked", (r) => console.log("KICKED", r));

bot.once("spawn", async () => {
  console.log("spawned at", bot.entity.position);

  const { pathfinder } = require("mineflayer-pathfinder");
  bot.loadPlugin(pathfinder);

  // give the bot a town hall hut item via the server console (this process
  // has no in-game admin rights of its own, so the giving step happens
  // outside the bot via the server's stdin console).
  console.log(`Waiting for ${HUT_ITEM} to appear in inventory...`);
  const gotItem = await waitForItem(HUT_ITEM, 30000);
  if (!gotItem) {
    console.log("FAILED: item never arrived, did you run the /give command?");
    return;
  }
  console.log("Got item, inventory:", bot.inventory.items().map((i) => i.name));

  const pos = bot.entity.position.floor().offset(3, 0, 3);
  console.log("Attempting to place hut block at", pos);

  try {
    const item = bot.inventory.items().find((i) => i.name.includes("townhall") || i.name.includes("town_hall"));
    await bot.equip(item, "hand");
    const refBlock = bot.blockAt(pos.offset(0, -1, 0));
    await bot.placeBlock(refBlock, new Vec3(0, 1, 0));
    console.log("placeBlock call returned without throwing");
  } catch (e) {
    console.log("PLACE ERROR:", e.message);
  }

  await bot.waitForTicks(20);
  const placed = bot.blockAt(pos);
  console.log("Block now at target pos:", placed ? placed.name : "null");
});

function waitForItem(name, timeoutMs) {
  return new Promise((resolve) => {
    const start = Date.now();
    const check = () => {
      const has = bot.inventory.items().some((i) => i.name.includes("town_hall") || i.name.includes("townhall") || i.name === name);
      if (has) return resolve(true);
      if (Date.now() - start > timeoutMs) return resolve(false);
      setTimeout(check, 1000);
    };
    check();
  });
}
