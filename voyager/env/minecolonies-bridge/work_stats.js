// work_stats.js - samples /status repeatedly and reports, per staffed
// building, how often its workers were actually observed "working".
// A single snapshot lies (commute/sleep/leisure moments), so this takes
// SAMPLES snapshots INTERVAL_MS apart and aggregates. Buildings whose
// workers were never working across the window are the "dead workplaces"
// worth investigating (missing animals/config/demand - see colony-diag).
//
// Usage: node work_stats.js [samples] [intervalMs]
const http = require("http");

const SAMPLES = parseInt(process.argv[2] || "12", 10);
const INTERVAL_MS = parseInt(process.argv[3] || "20000", 10);

function get(path) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: "localhost", port: 8089, path, method: "GET" },
      (res) => {
        let d = "";
        res.on("data", (c) => (d += c));
        res.on("end", () => resolve(d));
      }
    );
    req.on("error", reject);
    req.end();
  });
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Buildings with no worker role - not "workplaces" at all.
const NO_WORKER = new Set([
  "blockhutcitizen", "blockhuttownhall", "blockhutwarehouse", "blockhuttavern",
  "blockpostbox", "blockhutgraveyard", "blockhutmysticalsite",
]);

async function main() {
  // key: "type@x,z" -> { type, pos, workerSamples, workingSamples, statuses: {status: n}, citizens:Set }
  const acc = new Map();
  for (let s = 0; s < SAMPLES; s++) {
    const colonies = JSON.parse(await get("/status"));
    for (const colony of colonies) {
      const byId = new Map(colony.citizens.map((c) => [c.id, c]));
      for (const b of colony.buildings) {
        if (NO_WORKER.has(b.type) || !b.operational || !(b.workers || []).length) continue;
        const key = `${b.type}@${b.x},${b.z}`;
        if (!acc.has(key)) {
          acc.set(key, { type: b.type, pos: `${b.x},${b.y},${b.z}`, level: b.level,
            workerSamples: 0, workingSamples: 0, statuses: {}, citizens: new Set() });
        }
        const a = acc.get(key);
        for (const id of b.workers) {
          const c = byId.get(id);
          if (!c) continue;
          a.workerSamples++;
          a.citizens.add(id);
          a.statuses[c.jobStatus] = (a.statuses[c.jobStatus] || 0) + 1;
          if (c.jobStatus === "working") a.workingSamples++;
        }
      }
    }
    if (s < SAMPLES - 1) await sleep(INTERVAL_MS);
    process.stderr.write(`sample ${s + 1}/${SAMPLES}\r`);
  }

  const rows = [...acc.values()].map((a) => ({
    ...a,
    ratio: a.workerSamples ? a.workingSamples / a.workerSamples : 0,
  }));
  rows.sort((x, y) => x.ratio - y.ratio || x.type.localeCompare(y.type));

  console.log(`\nwork activity over ${SAMPLES} samples x ${INTERVAL_MS / 1000}s`);
  const dead = rows.filter((r) => r.ratio === 0);
  const low = rows.filter((r) => r.ratio > 0 && r.ratio < 0.2);
  console.log(`workplaces: ${rows.length} | never-working: ${dead.length} | low(<20%): ${low.length}\n`);
  for (const r of [...dead, ...low]) {
    const st = Object.entries(r.statuses).sort((a, b) => b[1] - a[1])
      .map(([k, v]) => `${k}:${v}`).join(" ");
    console.log(
      `${(r.ratio * 100).toFixed(0).padStart(3)}% ${r.type.replace("blockhut", "").padEnd(14)}` +
      ` lv${r.level} @(${r.pos}) workers[${[...r.citizens].join(",")}] ${st}`
    );
  }
}

main().catch((e) => { console.error(e); process.exit(1); });
