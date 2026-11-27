const SESSION_KEY = "softline-search-session-v1";
export const SESSION_IDLE_MS = 30 * 60 * 1000;
export const entityKinds = ["offer", "family", "vendor", "category", "collection"];
export const targetLabels = {
  offer: "Предложение", family: "Семейство", vendor: "Производитель", category: "Категория", collection: "Подборка",
};
const textFields = ["category_id", "vendor_id", "platform", "version", "license_type", "currency"];
const numberFields = ["term_months", "device_count", "seat_count", "max_price_minor"];
let activeSession;

export function newId() {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export function sessionId(now = Date.now()) {
  if (!activeSession) {
    try { activeSession = JSON.parse(sessionStorage.getItem(SESSION_KEY)); } catch { /* Browser storage is optional. */ }
  }
  const current = typeof activeSession?.id === "string" && now >= activeSession.at && now - activeSession.at < SESSION_IDLE_MS;
  activeSession = { id: current ? activeSession.id : newId(), at: now };
  try { sessionStorage.setItem(SESSION_KEY, JSON.stringify(activeSession)); } catch { /* Keep the in-memory session. */ }
  return activeSession.id;
}

export function eventSession(origin, now = Date.now()) {
  if (origin?.assistant && now >= origin.at && now - origin.at < SESSION_IDLE_MS) return origin.session;
  return sessionId(now);
}

export function filterParams(filters = {}) {
  const params = new URLSearchParams();
  for (const key of [...textFields, ...numberFields, "available_only"]) {
    const value = filters[key];
    if (value != null && value !== "" && value !== false) params.set(key, String(value));
  }
  return params;
}

export function filtersFromParams(params) {
  const filters = {};
  for (const key of textFields) {
    const value = params.get(key)?.trim();
    if (value) filters[key] = value;
  }
  for (const key of numberFields) {
    const raw = params.get(key);
    const value = Number(raw);
    if (raw && Number.isSafeInteger(value) && value >= (key === "max_price_minor" ? 0 : 1)) filters[key] = value;
  }
  if (params.get("available_only") === "true") filters.available_only = true;
  return filters;
}

export function entityHref(target, filters) {
  const params = filterParams(filters);
  params.set("kind", target.kind);
  params.set("id", target.id);
  return `/?${params}`;
}

export function cardTarget(item) {
  return { kind: item.target_kind ?? "offer", id: item.target_id ?? item.offer_id };
}

export function safeLink(url) {
  if (typeof url !== "string" || /[\s\\]/u.test(url)) return undefined;
  return url.startsWith("/") && !url.startsWith("//") ? url : undefined;
}

// Evidence can come from an external manufacturer's website or from the catalog.
export function sourceLink(url) {
  if (typeof url !== "string" || /[\s\\\u0000-\u001f\u007f]/u.test(url)) return undefined;
  if (url.startsWith("/")) return safeLink(url);
  if (!/^https:\/\/[^/]/i.test(url)) return undefined;
  try {
    const parsed = new URL(url);
    return parsed.username || parsed.password ? undefined : parsed.href;
  } catch {
    return undefined;
  }
}

const errors = {
  UNKNOWN_TARGET: "Эта подсказка устарела. Выберите обновлённую подсказку или повторите поиск.",
  ENTITY_NOT_FOUND: "Карточка отсутствует в текущем каталоге.",
  CARD_NOT_FOUND: "Для этой карточки нет сохранённых данных поиска.",
  CATALOG_UNAVAILABLE: "Каталог временно недоступен.",
  EVENT_STORE_UNAVAILABLE: "Журнал событий временно недоступен.",
  CONTRACT_ONLY: "Запущен справочный стенд API. Для работы нужен обработчик Spring Boot.",
};

export async function api(path, { body, ...options } = {}) {
  let response;
  try {
    response = await fetch(path, body === undefined ? options : {
      ...options, method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    });
  } catch (failure) {
    if (options.signal?.aborted) throw failure;
    throw new Error("Нет связи с сервером. Попробуйте ещё раз.", { cause: failure });
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    const code = problem?.code;
    const message = errors[code] ?? (response.status === 400 && typeof problem?.detail === "string"
      ? problem.detail : response.status === 404 || response.status >= 500
        ? "Обработчик временно недоступен." : "Не удалось выполнить запрос. Попробуйте ещё раз.");
    throw Object.assign(new Error(message), { code, status: response.status });
  }
  if (response.status === 204) return null;
  return response.json();
}

export function getEntity(target, filters, signal, origin) {
  const params = filterParams(filters);
  if (target.kind === "offer" && origin?.searchId && eventSession(origin) === origin.session) {
    params.set("origin_search_id", origin.searchId);
    params.set("origin_session_id", origin.session);
  }
  return api(`/v1/entities/${encodeURIComponent(target.kind)}/${encodeURIComponent(target.id)}?${params}`, { signal });
}
