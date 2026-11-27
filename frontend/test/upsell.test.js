import assert from "node:assert/strict";
import test from "node:test";
import { requestAssistant } from "../src/assistant.js";
import { cardTarget, entityHref } from "../src/api.js";
import { upsellCards } from "../src/upsell.js";

const direct = { offer_id: "printer", name: "Принтер", role: "direct" };
const verified = {
  offer_id: "cartridge", name: "Картридж", role: "complement", anchor_offer_id: "printer",
  checks: [{ result: "pass" }],
};
const related = {
  offer_id: "related", name: "Дополнительный товар", role: "related",
  recommendation_kind: "catalog_pick", recommendation_reason: "В каталоге",
};

test("duplicate recommendations use their upsell event coordinates", () => {
  const response = { top4: [direct, verified], upsell: [
    verified, { ...verified, offer_id: "unverified", checks: [{ result: "unknown" }] },
    { ...verified, offer_id: "orphan", anchor_offer_id: "unknown" }, related,
  ] };
  assert.deepEqual(upsellCards(response).map(card => [card.item.offer_id, card.block, card.position]),
    [[verified.offer_id, "upsell", 1], ["related", "upsell", 4]]);
});

test("family navigation uses the family ID and preserves the representative in the card", () => {
  const family = { ...direct, family_key: "family/acme/plus", target_kind: "family", target_id: "acme/plus" };
  assert.equal(entityHref(cardTarget(family)), "/?kind=family&id=acme%2Fplus");
  assert.equal(family.offer_id, "printer");
  assert.equal(entityHref(cardTarget(direct)), "/?kind=offer&id=printer");
});

test("assistant retains separate recommendations and event coordinates when top4 is full", async t => {
  const response = { top4: [direct, ...[1, 2, 3].map(id => ({ ...direct, offer_id: String(id) }))], upsell: [
    verified, { ...related, recommendation_kind: "paired_category", anchor_offer_id: "printer" },
  ] };
  t.mock.method(globalThis, "fetch", async (_url, options) => {
    assert.equal(JSON.parse(options.body).assistant_card_limit, 4);
    return Response.json(response);
  });
  const answer = await requestAssistant("сервер", [], "session");
  assert.equal(answer.products.length, 4);
  assert.deepEqual(answer.upsell.map(item => [item.offer_id, item.block, item.position, item.role, item.anchorId]),
    [["cartridge", "upsell", 1, "complement", "printer"], ["related", "upsell", 2, "related", "printer"]]);
});

test("a query without a match can show independent catalog picks; an empty block stays empty", () => {
  const miss = { top4: [], upsell: [related] };
  assert.ok(upsellCards(miss).some(({ item }) => !item.anchor_offer_id && item.recommendation_kind === "catalog_pick"));
  assert.deepEqual(upsellCards({ top4: [direct], upsell: [] }), []);
});

test("curated recommendations use at most eight distinct groups", () => {
  const curated = Array.from({ length: 10 }, (_, index) => ({ ...related, offer_id: `offer${index}`,
    family_key: `family/${Math.max(1, index)}`, recommendation_kind: "curated_fallback" }));
  const cards = upsellCards({ top4: [], upsell: curated });
  assert.deepEqual(cards.map(card => card.position), [1, 3, 4, 5, 6, 7, 8, 9]);
});
