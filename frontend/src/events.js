import { api, newId, SESSION_IDLE_MS } from "./api.js";

const demoKey = "softline-demo-analytics-v1";
const DAY = 24 * 60 * 60 * 1000;
const MAX_LOG = 500;
const metric = (numerator, denominator) => ({ numerator, denominator, value: denominator ? numerator / denominator : null });

function readDemoLog() {
  try {
    const log = JSON.parse(localStorage.getItem(demoKey));
    if (Array.isArray(log)) return log;
  } catch { /* Preview works even when storage is blocked. */ }
  return [];
}

function appendDemoLog(entries) {
  try { localStorage.setItem(demoKey, JSON.stringify([...readDemoLog(), ...entries].slice(-MAX_LOG))); }
  catch { /* Analytics storage must not interrupt the storefront. */ }
}

export function trackDemoSearch(result, q, session) {
  appendDemoLog([{
    type: "search", source: "demo", session_id: session, search_id: result.search_id,
    index_version: result.index_version, direct_count: result.direct_count,
    filtered: Object.values(result.applied_filters ?? {}).some(value => value != null && value !== false && value !== ""),
    q, ts: new Date().toISOString(),
  }]);
}

export function cardEvent(type, item, block, position, searchId) {
  return { type, search_id: searchId, offer_id: item.offer_id, family_key: item.family_key, role: item.role, block, position };
}

export function trackEvents(demo, session, events) {
  if (!session || !events.length) return;
  const batch = events.map(event => ({ ...event, event_id: newId(), session_id: session, ts: new Date().toISOString(), source: "demo" }));
  if (demo) {
    appendDemoLog(batch);
    return;
  }
  api("/v1/events", { body: batch, keepalive: true }).catch(error => {
    if ((error.status >= 400 && error.status < 500) || error.status === 501) return;
    setTimeout(() => api("/v1/events", { body: batch, keepalive: true }).catch(() => {}), 700);
  });
}

function seedLog(now) {
  const ts = new Date(now - 1.5 * SESSION_IDLE_MS).toISOString();
  const later = new Date(now - SESSION_IDLE_MS).toISOString();
  const queries = ["сервер", "Кибер Бэкап", "мтс", "Pantum", "мдаемон", "неизвестная модель"];
  const searches = queries.map((q, index) => ({
    type: "search", q, source: "seed", ts, session_id: `seed-session-${index}`, search_id: `seed-search-${index}`,
    index_version: "demo-2026-11-25", direct_count: index < 4 ? 3 : 0,
  }));
  return [...searches, { type: "click", source: "seed", ts, session_id: "seed-session-0", offer_id: "438239", search_id: "seed-search-0" },
    { type: "order", source: "seed", ts: later, session_id: "seed-session-0", offer_id: "438239", search_id: "seed-search-0" }];
}

export function demoAnalytics(period, now = Date.now()) {
  const selectedSources = period.source ? [period.source] : ["seed", "demo"];
  const start = Date.parse(period.from);
  const end = Date.parse(period.to);
  const log = [...seedLog(now), ...readDemoLog()].filter(item => selectedSources.includes(item?.source));
  const searches = log.filter(item => item.type === "search" && Date.parse(item.ts) >= start && Date.parse(item.ts) < end
    && (!period.index_version || item.index_version === period.index_version));
  const searchIds = new Set(searches.map(item => item.search_id));
  const key = item => `${item.source}/${item.session_id}`;
  const sessions = new Set(searches.map(key));
  const failures = searches.filter(item => item.direct_count === 0);
  const failedAt = new Map();
  for (const item of failures) failedAt.set(key(item), Math.max(failedAt.get(key(item)) ?? 0, Date.parse(item.ts)));
  const latestSessionEvent = new Map();
  const laterSuccess = new Set();
  const converted = new Set();
  for (const item of log) {
    const session = key(item);
    const ts = Date.parse(item.ts);
    latestSessionEvent.set(session, Math.max(latestSessionEvent.get(session) ?? 0, ts));
    if ((item.type === "click" || item.type === "order") && ts > (failedAt.get(session) ?? Infinity)) laterSuccess.add(session);
    if (item.type === "order" && searchIds.has(item.search_id)) converted.add(session);
  }
  const ended = [...failedAt.keys()].filter(session => now - latestSessionEvent.get(session) >= SESSION_IDLE_MS);
  const groups = new Map();
  for (const item of failures) {
    const reason = item.filtered ? "filter_conflict" : item.source === "seed" && item.q === "мдаемон" ? "missing_synonym" : "unknown";
    const group = groups.get(reason) ?? { reason, count: 0, query_examples: [] };
    group.count += 1;
    if (group.query_examples.length < 4 && !group.query_examples.includes(item.q)) group.query_examples.push(item.q);
    groups.set(reason, group);
  }
  return {
    period, zero_result_rate: metric(failures.length, searches.length),
    abandonment_rate: metric(ended.filter(session => !laterSuccess.has(session)).length, ended.length),
    conversion_rate: metric(converted.size, sessions.size),
    failures: [...groups.values()].map(item => ({ ...item, action: item.reason === "filter_conflict" ? "Проверить сочетания фильтров." : "Сверить формулировки с каталогом и словарём синонимов." })),
  };
}

export function defaultAnalyticsPeriod(now = new Date()) {
  return { from: new Date(now - 7 * DAY).toISOString(), to: now.toISOString() };
}
