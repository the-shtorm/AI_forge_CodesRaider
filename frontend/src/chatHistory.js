export const CHAT_EXPIRATION = 7 * 24 * 60 * 60 * 1000;
export const CHAT_LIMIT = 3;

export function pruneChats(chats, now = Date.now()) {
  if (!Array.isArray(chats)) return [];
  return chats.filter(chat => typeof chat?.id === "string"
    && Number.isFinite(chat.updatedAt) && chat.updatedAt > now - CHAT_EXPIRATION
    && chat.updatedAt <= now
    && Array.isArray(chat.messages) && chat.messages.every(message => ["user", "assistant"].includes(message?.role) && typeof message.text === "string")
    && chat.messages.some(message => message.role === "user"))
    .sort((a, b) => b.updatedAt - a.updatedAt)
    .slice(0, CHAT_LIMIT);
}

export function loadChats(key) {
  try {
    return pruneChats(JSON.parse(window.localStorage.getItem(key)));
  } catch {
    return [];
  }
}

export function saveChats(key, chats) {
  try {
    const active = pruneChats(chats);
    const stored = window.localStorage.getItem(key);
    if (!active.length) {
      if (stored !== null) window.localStorage.removeItem(key);
      return;
    }
    const serialized = JSON.stringify(active);
    if (stored !== serialized) window.localStorage.setItem(key, serialized);
  } catch {
    // The current page remains usable if the browser blocks storage.
  }
}

export function appendMessage(chats, id, message, now = Date.now()) {
  const existing = chats.find(chat => chat.id === id);
  if (!existing && message.role !== "user") return chats;
  const next = { id, updatedAt: now, messages: [...(existing?.messages ?? []), message] };
  return pruneChats([next, ...chats.filter(chat => chat.id !== id)], now);
}
