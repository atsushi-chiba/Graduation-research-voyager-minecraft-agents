// social_observer.js - Periodically rebuilds the Phase 1 relationship graph
// and records structural changes. Observation only: no citizen actions.
const fs = require("fs");
const path = require("path");
const S = require("./social_graph.js");

const POLL_MS = parseInt(process.env.SOCIAL_POLL_MS || "60000", 10);
const EVENT_FILE = process.env.SOCIAL_EVENT_FILE ||
  path.join(__dirname, "social_graph_events.jsonl");
const PID_FILE = process.env.SOCIAL_PID_FILE ||
  path.join(__dirname, "social_observer.pid");

function appendEvent(event, file, now, gameTime) {
  const record = {
    ts: now || new Date().toISOString(),
    gameTime,
    ...event,
  };
  fs.appendFileSync(file || EVENT_FILE, JSON.stringify(record) + "\n");
  return record;
}

function reconcile(previous, colony, opts) {
  const graph = S.buildSocialGraph(colony, opts);
  return { graph, events: S.diffGraphs(previous, graph) };
}

function acquirePidfile(file) {
  const target = file || PID_FILE;
  try {
    const old = parseInt(fs.readFileSync(target, "utf8"), 10);
    if (old && !Number.isNaN(old)) {
      try {
        process.kill(old, 0);
        throw new Error(`social_observer already running (pid ${old})`);
      } catch (error) {
        if (error && error.message && error.message.startsWith("social_observer already")) throw error;
      }
    }
  } catch (error) {
    if (error && error.message && error.message.startsWith("social_observer already")) throw error;
  }
  fs.writeFileSync(target, String(process.pid));
  const cleanup = () => {
    try {
      if (fs.readFileSync(target, "utf8") === String(process.pid)) fs.unlinkSync(target);
    } catch {}
  };
  process.on("exit", cleanup);
  process.on("SIGINT", () => process.exit(0));
  process.on("SIGTERM", () => process.exit(0));
}

async function pollOnce(previous, opts) {
  const colony = await (opts && opts.fetchColony ? opts.fetchColony() : S.fetchColony());
  const result = reconcile(previous, colony, opts);
  S.saveGraph(result.graph, opts && opts.stateFile);
  for (const event of result.events) {
    appendEvent(event, opts && opts.eventFile, opts && opts.now, colony.gameTime);
  }
  return result;
}

async function main() {
  acquirePidfile();
  let previous = S.loadGraph();
  appendEvent(
    { type: "observer_started", pollMs: POLL_MS, baselinePresent: !!previous },
    EVENT_FILE, undefined, previous && previous._meta.gameTime
  );
  for (;;) {
    try {
      const result = await pollOnce(previous);
      previous = result.graph;
    } catch (error) {
      appendEvent({ type: "observer_error", message: String(error && error.message || error) });
    }
    await new Promise((resolve) => setTimeout(resolve, POLL_MS));
  }
}

module.exports = {
  POLL_MS,
  EVENT_FILE,
  PID_FILE,
  appendEvent,
  reconcile,
  acquirePidfile,
  pollOnce,
};

if (require.main === module) {
  main().catch((error) => {
    try {
      appendEvent({ type: "observer_fatal", message: String(error && error.stack || error) });
    } finally {
      process.exit(1);
    }
  });
}
