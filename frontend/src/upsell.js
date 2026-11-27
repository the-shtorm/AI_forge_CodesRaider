// A verified card can appear in both blocks for older clients. Keep one tracking location.
export function upsellCards(result) {
  const top = result?.top4 ?? [];
  const anchors = new Map(top.filter(item => item.role === "direct").map(item => [item.offer_id, item]));
  const seen = new Set([...anchors.keys(), ...(result?.more_results ?? []).map(item => item.offer_id),
    ...(result?.alternatives ?? []).map(item => item.offer_id)]);
  const cards = [];
  for (const [block, items] of [["top4", top], ["upsell", result?.upsell ?? []]]) {
    items.forEach((item, index) => {
      if (seen.has(item.offer_id)) return;
      const anchor = anchors.get(item.anchor_offer_id);
      if (item.role === "complement") {
        if (!anchor || !item.checks?.length || !item.checks.every(check => check.result === "pass")) return;
      } else if (block !== "upsell" || item.role !== "related" || !item.recommendation_reason
          || (item.anchor_offer_id && !anchor)) return;
      cards.push({ item, anchor, block, position: index + 1 });
      seen.add(item.offer_id);
    });
  }
  return cards;
}
