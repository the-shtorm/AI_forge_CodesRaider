import { api, newId } from "./api.js";

export function cardEvent(type, item, block, position, searchId) {
  return { type, search_id: searchId, offer_id: item.offer_id, family_key: item.family_key, role: item.role, block, position };
}

export function trackEvents(session, events) {
  if (!session || !events.length) return;
  const ts = new Date().toISOString();
  const batch = events.map(event => ({ ...event, event_id: newId(), session_id: session, ts, source: "demo" }));
  const send = () => api("/v1/events", { body: batch, keepalive: true });
  send().catch(error => {
    if (error.status >= 400 && error.status < 500) return;
    setTimeout(() => send().catch(() => {}), 700);
  });
}

export function defaultAnalyticsPeriod(now = new Date()) {
  return { from: new Date(now - 7 * 24 * 60 * 60 * 1000).toISOString(), to: now.toISOString() };
}
