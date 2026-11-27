// A verified card can appear in both blocks. Use its coordinates in the recommendation block.
export function upsellCards(result) {
  const top = result?.top4 ?? [];
  const direct = [...top.filter(item => item.role === "direct"), ...(result?.more_results ?? [])];
  const anchors = new Map(direct.map(item => [item.offer_id, item]));
  const group = item => item.family_key ?? `offer/${item.offer_id}`;
  const seen = new Set([...direct, ...(result?.alternatives ?? [])].map(group));
  const cards = [];
  for (const [block, items] of [["upsell", result?.upsell ?? []], ["top4", top]]) {
    items.forEach((item, index) => {
      if (seen.has(group(item))) return;
      const anchor = anchors.get(item.anchor_offer_id);
      if (item.role === "complement") {
        if (!anchor || !item.checks?.length || !item.checks.every(check => check.result === "pass")) return;
      } else if (block !== "upsell" || item.role !== "related" || !item.recommendation_reason
          || (item.anchor_offer_id && !anchor)) return;
      cards.push({ item, anchor, block, position: index + 1 });
      seen.add(group(item));
    });
  }
  return cards.slice(0, 8);
}
