import { useEffect, useRef } from "react";

export function recordEvent(session, type, fields, eventId = globalThis.crypto?.randomUUID?.() ?? String(Math.random())) {
  if (!session) return;
  const body = JSON.stringify([{ event_id: eventId, session_id: session, type, ts: new Date().toISOString(), ...fields }]);
  const send = () => fetch("/v1/events", { method: "POST", keepalive: true, headers: { "Content-Type": "application/json" }, body });
  send().then(response => { if (response.status >= 500) return send(); }, send).catch(() => {});
}

export function useCardImpression(session, searchId, item, block, position) {
  const element = useRef(null);
  const fields = { search_id: searchId, offer_id: item.offer_id, family_key: item.family_key,
    role: item.role, block, position };
  useEffect(() => {
    if (!session || !searchId) return;
    const observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) {
        recordEvent(session, "impression", fields, ["impression", searchId, block, position].join("/"));
        observer.disconnect();
      }
    });
    observer.observe(element.current);
    return () => observer.disconnect();
  }, [searchId, session, block, position, item.offer_id, item.family_key, item.role]);
  return { element, click: () => { if (searchId) recordEvent(session, "click", fields); } };
}
