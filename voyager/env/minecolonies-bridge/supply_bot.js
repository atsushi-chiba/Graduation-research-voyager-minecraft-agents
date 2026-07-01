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

async function loop() {
  let cycle = 0;
  while (true) {
    cycle++;
    try {
      const colonies = await getStatus();
      let totalResolved = 0;

      for (const colony of colonies) {
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
                  const isToolItem = /_(pickaxe|axe|shovel|hoe|sword|bow|crossbow|fishing_rod)$/.test(req.item);
                  const maxMatch = !isToolItem && req.description && req.description.match(/\d+-(\d+)/);
                  const giveCount = isToolItem ? 1 : (maxMatch ? parseInt(maxMatch[1]) : Math.max(req.count, 64));
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
