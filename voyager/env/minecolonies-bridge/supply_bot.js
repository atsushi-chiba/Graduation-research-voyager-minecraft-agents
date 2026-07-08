// supply_bot.js - Cheat-mode material supplier.
// Runs alongside council.js and automatically resolves every open citizen
// request so builders are never blocked waiting for materials.
//
// Key design: /resolveRequest alone only changes request state (OVERRULED) but
// does NOT put items in the citizen's inventory. The builder AI checks its own
// inventory and will re-issue the same request if the item isn't there, causing
// an infinite resolve loop. The correct sequence is:
//   1. /giveToCitizen  — physically deliver the item (builder AI detects it)
//   2. /resolveRequest — close the request state so MineColonies stops retrying
//
// Textured (Domum Ornamentum framed-block) requests are skipped - those
// require raw materials in the citizen's inventory first.
const http = require("http");

const BRIDGE_HOST = "localhost";
const BRIDGE_PORT = 8089;
const POLL_INTERVAL_MS = 6000;
const RESOLVE_DELAY_MS = 300;

function httpRequest(method, path) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: BRIDGE_HOST, port: BRIDGE_PORT, path, method },
      (res) => {
        let data = "";
        res.on("data", (c) => (data += c));
        res.on("end", () => resolve({ status: res.statusCode, body: data }));
      }
    );
    req.on("error", reject);
    req.end();
  });
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

async function getStatus() {
  const res = await httpRequest("GET", "/status");
  return JSON.parse(res.body);
}

async function getOpenRequests(x, y, z, citizenId) {
  const res = await httpRequest(
    "GET",
    `/openRequests?x=${x}&y=${y}&z=${z}&citizenId=${citizenId}`
  );
  try {
    return JSON.parse(res.body);
  } catch {
    return [];
  }
}

async function giveToCitizen(colonyId, citizenId, item, count) {
  return httpRequest(
    "POST",
    `/giveToCitizen?colonyId=${colonyId}&citizenId=${citizenId}&item=${encodeURIComponent(item)}&count=${count}`
  );
}

async function resolveRequest(x, y, z, citizenId) {
  return httpRequest(
    "POST",
    `/resolveRequest?x=${x}&y=${y}&z=${z}&citizenId=${citizenId}`
  );
}

// citizenId -> disease id we already delivered cure items for. Cleared when
// the citizen is healthy again so a new infection triggers a fresh delivery.
const curesDelivered = new Map();

// The game runs at 10x (POST /tickrate, standing rule) but this bot lives in
// real time. Anything that models an in-game process (hunger drain, cooldowns
// meant to span "a while for the citizen") must be divided by the multiplier,
// or the bot falls behind the game tenfold - the 10min feed cooldown at 10x
// meant citizens starved and trekked to the restaurant between feeds, which
// also caused the constant door-flapping foot traffic.
const TICK_MULTIPLIER = 10;

// Hungry citizens walk off to hunt for food (CHECK_FOR_FOOD / SEARCH_RESTAURANT)
// instead of working. Feed them before they get there. Citizens eat from
// their own inventory once saturation drops, so a small stack lasts a while.
// Two constraints learned the hard way:
// - canEatLevel: nutrition >= home level + 1 for lv3+ homes (bread(5) is
//   inedible in lv5 residences) - every item here is nutrition >= 7.
// - Food history: once a citizen's history is full, getBestFoodForCitizen
//   REFUSES all inventory food if diversity (distinct foods) or quality
//   (count of IMinecoloniesFoodItem meals) is below the home-level
//   requirement, and sends them trekking to the restaurant instead - fatal
//   for far-away workplaces at 10x where night interrupts the trip
//   (citizens 16/26 starvation, 2026-07-06). Rotating MineColonies dinners
//   (tier-3 ItemFood, no saturation nerf) keeps both stats satisfied.
const FEED_BELOW_SATURATION = 8;
// All tier-3 nutrition-9 ItemFood. Depth matters: the diversity requirement
// is homeLevel (lv5 home -> MORE than 5 distinct foods in history), so a
// 4-item rotation deadlocked once every item had been eaten recently
// (citizen 16, 2026-07-06 second starvation). 8 distinct tier-3 meals keep
// diversity above every home level's bar, and a never-eaten minecolonies
// food is always accepted immediately, which breaks existing deadlocks.
const FEED_ITEMS = [
  "minecolonies:steak_dinner",
  "minecolonies:fish_dinner",
  "minecolonies:schnitzel",
  "minecolonies:ramen",
  "minecolonies:sushi_roll",
  "minecolonies:tacos",
  "minecolonies:borscht",
  "minecolonies:hand_pie",
];
// Tier-3 meals restore ~7.5 saturation each (no vanilla nerf); 4 is already
// 30 saturation vs the 20 cap. Keeping the stack small stops uneaten food
// from packing inventories (which is what blocked cure deliveries on 07-04).
const FEED_ITEM_COUNT = 4;
// Couriers (and builders commuting to frontier sites) sprint all day at 10x
// and burn through 4 meals before the next cooldown - their saturation
// oscillated 0 <-> 4 while everyone else sat near 20 (2026-07-06 audit).
const HIGH_DRAIN_JOBS = new Set(["deliveryman", "builder"]);
const HIGH_DRAIN_COUNT = 8;
const feedRotation = new Map(); // citizenId -> next index into FEED_ITEMS
const FEED_COOLDOWN_MS = (10 * 60 * 1000) / TICK_MULTIPLIER;
const lastFed = new Map(); // citizenId -> timestamp of last delivery

async function feedHungryCitizens(colony, cycle) {
  let fed = 0;
  for (const citizen of colony.citizens || []) {
    if (typeof citizen.saturation !== "number") continue;
    if (citizen.saturation >= FEED_BELOW_SATURATION) continue;
    // Sick citizens can't reach the EATING state (CitizenAI checks SICK
    // first), so bread only piles up until the inventory is full - which
    // then makes the cure delivery bounce and the citizen stays sick
    // forever (the 2026-07-04 23-sick-citizens incident). Cure first,
    // feed after.
    if (citizen.sick) continue;
    const last = lastFed.get(citizen.id) || 0;
    if (Date.now() - last < FEED_COOLDOWN_MS) continue;
    const idx = feedRotation.get(citizen.id) || 0;
    const item = FEED_ITEMS[idx % FEED_ITEMS.length];
    feedRotation.set(citizen.id, idx + 1);
    const count = HIGH_DRAIN_JOBS.has(citizen.job) ? HIGH_DRAIN_COUNT : FEED_ITEM_COUNT;
    await giveToCitizen(colony.id, citizen.id, item, count);
    lastFed.set(citizen.id, Date.now());
    console.log(
      `[supply #${cycle}] fed citizen ${citizen.id} (saturation ${citizen.saturation}): ${count}x ${item}`
    );
    fed++;
    await sleep(RESOLVE_DELAY_MS);
  }
  return fed;
}

// Builders request materials one item type at a time as construction reaches
// them, which costs a request->deliver round-trip (one poll cycle) per item
// type. The hut itself tracks the whole remaining bill of materials, and the
// builder takes from the hut's racks before filing requests - so bulk-filling
// the racks via /fillBuilderResources removes the ping-pong. Tools/armor are
// not in that list and still flow through the request loop below.
async function fillBuilderHuts(colony, cycle) {
  let filled = 0;
  for (const building of colony.buildings || []) {
    if (building.type !== "blockhutbuilder") continue;
    try {
      const res = await httpRequest(
        "POST",
        `/fillBuilderResources?x=${building.x}&y=${building.y}&z=${building.z}`
      );
      if (res.status !== 200) continue;
      const given = JSON.parse(res.body).filter((i) => i.given > 0);
      if (given.length > 0) {
        console.log(
          `[supply #${cycle}] filled builder hut (${building.x},${building.y},${building.z}): ` +
            given.map((i) => `${i.given}x ${i.item}`).join(", ")
        );
        filled += given.length;
      }
    } catch {
      // transient - retry next cycle
    }
  }
  return filled;
}

// Crafters only produce what they have been TAUGHT (normally via the crafting
// GUI). crafter_recipes.json is a priority-ordered teach list per building
// type; recipe slots scale as 2^buildingLevel (x research), so we teach from
// the top and simply stop when the building refuses (capacity full) - after
// the next level-up or RECIPES research the loop picks up where it left off.
// /teachRecipe is idempotent ("already taught" is a success).
const CRAFTER_RECIPES = require("./crafter_recipes.json");

async function teachCrafterRecipes(colony, cycle) {
  if (cycle % 10 !== 5) return 0; // same cadence as autoResearch, offset phase
  let taught = 0;
  for (const building of colony.buildings || []) {
    const list = CRAFTER_RECIPES[building.type];
    if (!list || !building.operational || building.level < 1) continue;
    for (const r of list) {
      const inputs = encodeURIComponent(r.inputs);
      const out = encodeURIComponent(r.output);
      let res;
      try {
        res = await httpRequest(
          "POST",
          `/teachRecipe?x=${building.x}&y=${building.y}&z=${building.z}` +
            `&output=${out}&outputCount=${r.count || 1}&inputs=${inputs}&grid=${r.grid || 3}`
        );
      } catch {
        break; // transient - retry next round
      }
      if (res.status !== 200) break; // capacity full (or incompatible) - stop here for now
      if ((res.body || "").includes("taught ")) {
        console.log(`[supply #${cycle}] ${building.type}@(${building.x},${building.z}): ${res.body.slice(0, 120)}`);
        taught++;
      }
      await sleep(RESOLVE_DELAY_MS);
    }
  }
  return taught;
}

// Research progresses on its own once started (the university researcher
// works it down), but finished research frees slots and unlocks children -
// periodically ask the bridge to start whatever is startable next. Item
// costs are cheated (creative path); progression order stays vanilla.
async function autoResearch(colony, cycle) {
  if (cycle % 10 !== 1) return 0;
  try {
    const res = await httpRequest("POST", `/autoResearch?colonyId=${colony.id}`);
    if (res.status !== 200) return 0; // e.g. no university yet
    const d = JSON.parse(res.body);
    if (d.started && d.started.length > 0) {
      console.log(
        `[supply #${cycle}] started research: ${d.started.join(", ")} (${d.inProgress}/${d.slots} slots)`
      );
      return d.started.length;
    }
  } catch {
    // transient - retry next round
  }
  return 0;
}

// Keep the restaurant's racks stocked with every menu food so the cook can
// serve arrivals immediately (the built-in MinimumStock pipeline is too slow
// at 10x and citizens loiter at the restaurant waiting to be fed).
async function stockRestaurants(colony, cycle) {
  let stocked = 0;
  for (const building of colony.buildings || []) {
    if (building.type !== "blockhutcook" || !building.operational) continue;
    try {
      const res = await httpRequest(
        "POST",
        `/stockRestaurant?x=${building.x}&y=${building.y}&z=${building.z}&countPerItem=32`
      );
      if (res.status !== 200) continue;
      const given = JSON.parse(res.body).filter((i) => i.given > 0);
      if (given.length > 0) {
        console.log(
          `[supply #${cycle}] stocked restaurant (${building.x},${building.y},${building.z}): ` +
            given.map((i) => `${i.given}x ${i.item}`).join(", ")
        );
        stocked += given.length;
      }
    } catch {
      // transient - retry next cycle
    }
  }
  return stocked;
}

// Sick citizens don't file requests - their EntityAISickTask walks to a
// hospital (which this colony doesn't have) and otherwise waits forever.
// The same AI self-cures (APPLY_CURE) as soon as every cure item of the
// disease is in the citizen's own inventory, so delivering the items via
// /giveToCitizen is a full treatment.
async function deliverCure(colony, citizen) {
  let complete = true;
  for (const cure of citizen.cureItems) {
    if (!cure.item || cure.count <= 0) continue;
    const res = await giveToCitizen(colony.id, citizen.id, cure.item, cure.count);
    const m = /gave (\d+)\/(\d+)/.exec(res.body || "");
    if (!m || m[1] !== m[2]) complete = false;
    await sleep(RESOLVE_DELAY_MS);
  }
  return complete;
}

async function treatSickCitizens(colony, cycle) {
  let treated = 0;
  for (const citizen of colony.citizens || []) {
    if (!citizen.sick) {
      curesDelivered.delete(citizen.id);
      continue;
    }
    if (!citizen.cureItems || citizen.cureItems.length === 0) continue;
    if (curesDelivered.get(citizen.id) === citizen.disease) continue;
    // /giveToCitizen answers "gave X/Y" - X < Y means the inventory was
    // full and the cure is incomplete, which the sick AI treats as no cure
    // at all. Clear the inventory (cheap, everything is cheat-supplied)
    // and retry once; only a fully landed delivery counts as treated.
    let complete = await deliverCure(colony, citizen);
    if (!complete) {
      await httpRequest(
        "POST",
        `/clearCitizenInventory?colonyId=${colony.id}&citizenId=${citizen.id}`
      );
      complete = await deliverCure(colony, citizen);
    }
    if (!complete) {
      console.log(
        `[supply #${cycle}] cure delivery incomplete for citizen ${citizen.id} (${citizen.disease}), will retry`
      );
      continue;
    }
    curesDelivered.set(citizen.id, citizen.disease);
    console.log(
      `[supply #${cycle}] treated citizen ${citizen.id} (${citizen.disease}): ` +
        citizen.cureItems.map((c) => `${c.count}x ${c.item}`).join(", ")
    );
    treated++;
  }
  return treated;
}

async function loop() {
  let cycle = 0;
  while (true) {
    cycle++;
    try {
      const colonies = await getStatus();
      let totalResolved = 0;

      for (const colony of colonies) {
        totalResolved += await treatSickCitizens(colony, cycle);
        totalResolved += await feedHungryCitizens(colony, cycle);
        totalResolved += await stockRestaurants(colony, cycle);
        totalResolved += await autoResearch(colony, cycle);
        totalResolved += await teachCrafterRecipes(colony, cycle);
        totalResolved += await fillBuilderHuts(colony, cycle);
        for (const building of colony.buildings) {
          if (building.workers.length === 0) continue;

          for (const citizenId of building.workers) {
            let requests;
            try {
              requests = await getOpenRequests(
                building.x,
                building.y,
                building.z,
                citizenId
              );
            } catch {
              continue;
            }

            for (const req of requests) {
              if (!req.item || req.count <= 0) continue;
              try {
                if (req.textured && req.materials && req.materials.length > 0) {
                  // Textured (Domum Ornamentum framed-block): give each raw material
                  // to the citizen first, then resolveRequest will consume them and
                  // fulfill the request via the equivalent-exchange logic.
                  for (const mat of req.materials) {
                    if (!mat.item || mat.count <= 0) continue;
                    await giveToCitizen(colony.id, citizenId, mat.item, mat.count);
                    await sleep(RESOLVE_DELAY_MS);
                  }
                  const res = await resolveRequest(building.x, building.y, building.z, citizenId);
                  if (res.status === 200) {
                    console.log(
                      `[supply #${cycle}] resolved textured ${req.item} for citizen ${citizenId}: ${req.description}`
                    );
                    totalResolved++;
                  } else {
                    console.log(`[supply #${cycle}] textured resolve failed: ${res.body}`);
                  }
                } else if (!req.textured) {
                  // Plain material request: give item then close the request.
                  // Give exactly what the request asks for (the old 64-item floor
                  // that avoided re-request round-trips is obsolete now that
                  // fillBuilderResources bulk-stocks the hut racks, and it flooded
                  // citizens with e.g. 64x polished diorite for a 1-block request).
                  const isToolItem = /_(pickaxe|axe|shovel|hoe|sword|bow|crossbow|fishing_rod)$/.test(req.item);
                  const maxMatch = !isToolItem && req.description && req.description.match(/\d+-(\d+)/);
                  const giveCount = isToolItem ? 1 : (maxMatch ? parseInt(maxMatch[1]) : Math.max(req.count, 1));
                  await giveToCitizen(colony.id, citizenId, req.item, giveCount);
                  const res = await resolveRequest(building.x, building.y, building.z, citizenId);
                  if (res.status === 200) {
                    console.log(
                      `[supply #${cycle}] gave ${giveCount}x ${req.item} to citizen ${citizenId} @ (${building.x},${building.y},${building.z}): ${req.description}`
                    );
                    totalResolved++;
                  }
                }
              } catch {
                // ignore transient errors
              }
              await sleep(RESOLVE_DELAY_MS);
            }
          }
        }
      }

      if (totalResolved > 0) {
        console.log(`[supply #${cycle}] resolved ${totalResolved} request(s)`);
      }
    } catch (e) {
      console.log(`[supply #${cycle}] error:`, e.message);
    }

    await sleep(POLL_INTERVAL_MS);
  }
}

console.log("[supply_bot] starting - auto-resolving all open citizen requests");
loop().catch((e) => console.error("FATAL", e));
