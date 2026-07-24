// Offline tests for Phase 2 dynamic state. No bridge access.
const assert = require("assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const D = require("./social_dynamics.js");

function graph(nodes, edges) {
  return {
    _meta: { colonyId: 1 },
    nodes: Object.fromEntries(nodes.map((n) => [String(n.citizenId), n])),
    edges: edges || {},
  };
}

function personas(records) {
  return { personas: Object.fromEntries(records.map((p) => [String(p.citizenId), p])) };
}

function persona(citizenId, loyalty, liked, disliked) {
  return {
    citizenId,
    segments: {
      politics: { loyalty },
      jobPreference: { liked: liked || [], disliked: disliked || [] },
    },
  };
}

let passed = 0;
function test(label, fn) {
  fn();
  passed++;
  console.log(`PASS ${label}`);
}

test("initial state uses persona loyalty and neutral dynamic values", () => {
  const g = graph([{ citizenId: 1, name: "A" }]);
  const state = D.reconcileState(null, g, personas([persona(1, 0.8)]));
  assert.deepStrictEqual(state.citizens["1"], {
    citizenId: 1,
    name: "A",
    active: true,
    fear: 0,
    stress: 0,
    satisfaction: 0.5,
    actualLoyalty: 0.8,
    lastEventGameTime: null,
  });
});

test("reconcile preserves dynamics and historical relations", () => {
  const edge = { a: 1, b: 2, sources: ["coworker"] };
  const first = D.reconcileState(null, graph([
    { citizenId: 1, name: "A" },
    { citizenId: 2, name: "B" },
  ], { "1:2": edge }), null);
  first.citizens["1"].satisfaction = 0.9;
  first.relations["1:2"].trust = 0.75;
  const second = D.reconcileState(first, graph([
    { citizenId: 1, name: "A" },
  ]), null);
  assert.strictEqual(second.citizens["1"].satisfaction, 0.9);
  assert.strictEqual(second.citizens["2"].active, false);
  assert.strictEqual(second.relations["1:2"].trust, 0.75);
  assert.strictEqual(second.relations["1:2"].structurallyActive, false);
});

test("liked and disliked job changes update satisfaction and loyalty", () => {
  const ps = personas([persona(1, 0.5, ["farmer"], ["miner"])]);
  const state = D.reconcileState(null, graph([{ citizenId: 1, name: "A" }]), ps);
  const liked = D.applyEvent(
    state, { type: "job_changed", citizenId: 1, to: "farmer" }, ps, 100
  );
  assert.strictEqual(liked.preference, "liked");
  assert.strictEqual(state.citizens["1"].satisfaction, 0.6);
  assert.strictEqual(state.citizens["1"].actualLoyalty, 0.52);
  const disliked = D.applyEvent(
    state, { type: "job_changed", citizenId: 1, to: "miner" }, ps, 200
  );
  assert.strictEqual(disliked.preference, "disliked");
  assert.strictEqual(state.citizens["1"].satisfaction, 0.45);
  assert.strictEqual(state.citizens["1"].actualLoyalty, 0.49);
  assert.strictEqual(state.citizens["1"].lastEventGameTime, 200);
});

test("citizen removal is inactivity, not an unconfirmed death", () => {
  const state = D.reconcileState(null, graph([{ citizenId: 1, name: "A" }]), null);
  D.applyEvent(state, { type: "citizen_removed", citizenId: 1 }, null, 123);
  assert.strictEqual(state.citizens["1"].active, false);
  assert.ok(!Object.hasOwn(state.citizens["1"], "deceased"));
});

test("atomic save/load round trip", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "social-dynamics-test-"));
  const file = path.join(dir, "state.json");
  const state = D.reconcileState(null, graph([{ citizenId: 1, name: "A" }]), null);
  D.saveState(state, file);
  assert.ok(!fs.existsSync(`${file}.tmp`));
  assert.deepStrictEqual(D.loadState(file), state);
  fs.rmSync(dir, { recursive: true, force: true });
});

console.log(`\nALL PASS (${passed} tests)`);
