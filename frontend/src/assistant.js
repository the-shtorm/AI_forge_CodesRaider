import { api } from './api.js';

export async function requestAssistant(question, history, sessionId, signal, profileId) {
  const result = await api("/v1/search", { signal, body: {
    q: question, strategy: "llm", session_id: sessionId, assistant_history: history,
    context: profileId ? { profile_id: profileId } : undefined,
  } });
  const products = result.top4.flatMap((item, index) => item.role === "direct" ? [{ ...item, position: index + 1 }] : []).slice(0, 3);
  const text = result.assistant_message || (products.length
    ? "В каталоге нашлись подходящие программы. Если уточните ОС, бюджет или число устройств, смогу сузить подборку."
    : "Уточните, для какой задачи нужна программа, на какой ОС она должна работать и сколько устройств будет использоваться.");
  return { text, products, searchId: result.search_id, appliedFilters: result.applied_filters };
}
