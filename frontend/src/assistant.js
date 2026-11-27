import { api } from "./api.js";
import { upsellCards } from "./upsell.js";

function chatCard({ item, block, position, anchor }) {
  const { offer_id, name, family_key, role } = item;
  return { offer_id, name, family_key, role, block, position, anchorId: anchor?.offer_id };
}

export async function requestAssistant(question, history, sessionId, signal, profileId) {
  const result = await api("/v1/search", { signal, body: {
    q: question, strategy: "llm", session_id: sessionId, assistant_history: history, assistant_card_limit: 4,
    context: profileId ? { profile_id: profileId } : undefined,
  } });
  const products = result.top4.flatMap((item, index) => item.role === "direct"
    ? [chatCard({ item, block: "top4", position: index + 1 })] : []);
  const upsell = upsellCards(result).slice(0, 4).map(chatCard);
  const text = result.assistant_message || (products.length
    ? "В каталоге нашлись подходящие программы. Если уточните ОС, бюджет или число устройств, смогу сузить подборку."
    : "Уточните, для какой задачи нужна программа, на какой ОС она должна работать и сколько устройств будет использоваться.");
  return { text, products, upsell, searchId: result.search_id, appliedFilters: result.applied_filters };
}
