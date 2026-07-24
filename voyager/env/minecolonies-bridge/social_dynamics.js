// social_dynamics.js - Phase 2 dynamic citizen/relationship state.
//
// Structural graph snapshots are disposable and rebuilt from /status.
// Dynamic values live here so graph rebuilds never reset accumulated values.
const fs = require("fs");
const path = require("path");

const DYNAMICS_FILE = process.env.SOCIAL_DYNAMICS_FILE ||
  path.join(__dirname, "social_dynamics.json");

function clamp01(value) {
  return Math.max(0, Math.min(1, value));
}

function round3(value) {
  return Math.round(clamp01(value) * 1000) / 1000;
}

function emptyState(graph) {
  return {
    _meta: {
      version: 1,
      phase: 2,
      colonyId: graph && graph._meta.colonyId,
      updatedAt: null,
    },
    citizens: {},
    relations: {},
  };
}

function personaFor(personas, citizenId) {
  if (!personas) return null;
  const records = personas.personas || personas;
  return records[String(citizenId)] || null;
}

function initialLoyalty(persona) {
  const value = persona && persona.segments && persona.segments.politics &&
    persona.segments.politics.loyalty;
  return typeof value === "number" ? round3(value) : 0.5;
}

function ensureCitizen(state, node, personas) {
  const key = String(node.citizenId);
  if (!state.citizens[key]) {
    state.citizens[key] = {
      citizenId: node.citizenId,
      name: node.name,
      active: true,
      fear: 0,
      stress: 0,
      satisfaction: 0.5,
      actualLoyalty: initialLoyalty(personaFor(personas, node.citizenId)),
      nutritionBand: node.nutritionBand || "unknown",
      sick: !!node.sick,
      disease: node.disease || null,
      lastEventGameTime: null,
    };
  } else {
    state.citizens[key].name = node.name;
    state.citizens[key].active = true;
    state.citizens[key].nutritionBand = node.nutritionBand || "unknown";
    state.citizens[key].sick = !!node.sick;
    state.citizens[key].disease = node.disease || null;
  }
  return state.citizens[key];
}

function ensureRelation(state, edge) {
  const key = edge.a < edge.b ? `${edge.a}:${edge.b}` : `${edge.b}:${edge.a}`;
  if (!state.relations[key]) {
    state.relations[key] = {
      a: Math.min(edge.a, edge.b),
      b: Math.max(edge.a, edge.b),
      structurallyActive: true,
      sources: (edge.sources || []).slice(),
      trust: 0.5,
      affinity: 0.5,
      debt: 0,
      lastEventGameTime: null,
    };
  } else {
    state.relations[key].structurallyActive = true;
    state.relations[key].sources = (edge.sources || []).slice();
  }
  return state.relations[key];
}

// Reconcile authoritative membership and structural edges while preserving
// dynamic values and historical relations.
function reconcileState(existing, graph, personas) {
  const state = existing || emptyState(graph);
  state._meta = state._meta || emptyState(graph)._meta;
  state.citizens = state.citizens || {};
  state.relations = state.relations || {};
  const activeIds = new Set();
  for (const node of Object.values(graph.nodes || {})) {
    activeIds.add(String(node.citizenId));
    ensureCitizen(state, node, personas);
  }
  for (const [id, citizen] of Object.entries(state.citizens)) {
    if (!activeIds.has(id)) citizen.active = false;
  }
  const activeEdges = new Set();
  for (const [key, edge] of Object.entries(graph.edges || {})) {
    activeEdges.add(key);
    ensureRelation(state, edge);
  }
  for (const [key, relation] of Object.entries(state.relations)) {
    if (!activeEdges.has(key)) relation.structurallyActive = false;
  }
  state._meta.colonyId = graph._meta.colonyId;
  return state;
}

function jobPreferenceEffect(persona, job) {
  const pref = persona && persona.segments && persona.segments.jobPreference;
  if (!pref || !job) return { satisfaction: 0, loyalty: 0, preference: "neutral" };
  if ((pref.liked || []).includes(job)) {
    return { satisfaction: 0.1, loyalty: 0.02, preference: "liked" };
  }
  if ((pref.disliked || []).includes(job)) {
    return { satisfaction: -0.15, loyalty: -0.03, preference: "disliked" };
  }
  return { satisfaction: 0, loyalty: 0, preference: "neutral" };
}

function nutritionSeverity(band) {
  return band === "starving" ? 2 : band === "hungry" ? 1 : 0;
}

// Apply one observer event deterministically. Missing from one graph poll is
// inactivity, not death; P1 retains its separate three-poll confirmation.
function applyEvent(state, event, personas, gameTime) {
  const citizen = Number.isInteger(event.citizenId)
    ? state.citizens[String(event.citizenId)] : null;
  const effect = { type: event.type, citizenId: event.citizenId || null };
  if (event.type === "citizen_added" && citizen) {
    citizen.active = true;
  } else if (event.type === "citizen_removed" && citizen) {
    citizen.active = false;
  } else if (event.type === "job_changed" && citizen) {
    const delta = jobPreferenceEffect(personaFor(personas, event.citizenId), event.to);
    citizen.satisfaction = round3(citizen.satisfaction + delta.satisfaction);
    citizen.actualLoyalty = round3(citizen.actualLoyalty + delta.loyalty);
    citizen.lastEventGameTime = gameTime == null ? null : gameTime;
    effect.preference = delta.preference;
    effect.satisfactionDelta = delta.satisfaction;
    effect.loyaltyDelta = delta.loyalty;
  } else if (event.type === "nutrition_changed" && citizen) {
    const severityDelta = nutritionSeverity(event.to) - nutritionSeverity(event.from);
    const stressDelta = 0.1 * severityDelta;
    const satisfactionDelta = -0.05 * severityDelta;
    citizen.stress = round3(citizen.stress + stressDelta);
    citizen.satisfaction = round3(citizen.satisfaction + satisfactionDelta);
    citizen.nutritionBand = event.to;
    citizen.lastEventGameTime = gameTime == null ? null : gameTime;
    effect.stressDelta = stressDelta;
    effect.satisfactionDelta = satisfactionDelta;
  } else if (event.type === "sickness_started" && citizen) {
    citizen.stress = round3(citizen.stress + 0.15);
    citizen.satisfaction = round3(citizen.satisfaction - 0.1);
    citizen.sick = true;
    citizen.disease = event.disease || null;
    citizen.lastEventGameTime = gameTime == null ? null : gameTime;
    effect.stressDelta = 0.15;
    effect.satisfactionDelta = -0.1;
  } else if (event.type === "recovered" && citizen) {
    citizen.stress = round3(citizen.stress - 0.1);
    citizen.satisfaction = round3(citizen.satisfaction + 0.05);
    citizen.sick = false;
    citizen.disease = null;
    citizen.lastEventGameTime = gameTime == null ? null : gameTime;
    effect.stressDelta = -0.1;
    effect.satisfactionDelta = 0.05;
  } else if (event.type === "disease_changed" && citizen) {
    citizen.disease = event.to || null;
    citizen.lastEventGameTime = gameTime == null ? null : gameTime;
  } else if (event.type === "edge_added") {
    ensureRelation(state, event);
  } else if (event.type === "edge_removed") {
    const relation = state.relations[event.edge];
    if (relation) relation.structurallyActive = false;
  } else if (event.type === "edge_sources_changed") {
    const relation = state.relations[event.edge];
    if (relation) relation.sources = (event.to || []).slice();
  }
  return effect;
}

function loadState(file) {
  const target = file || DYNAMICS_FILE;
  if (!fs.existsSync(target)) return null;
  return JSON.parse(fs.readFileSync(target, "utf8"));
}

function saveState(state, file) {
  const target = file || DYNAMICS_FILE;
  state._meta.updatedAt = new Date().toISOString();
  const tmp = `${target}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(state, null, 2));
  fs.renameSync(tmp, target);
}

module.exports = {
  DYNAMICS_FILE,
  emptyState,
  initialLoyalty,
  reconcileState,
  jobPreferenceEffect,
  nutritionSeverity,
  applyEvent,
  loadState,
  saveState,
};
