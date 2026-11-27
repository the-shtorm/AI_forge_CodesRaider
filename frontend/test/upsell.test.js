import assert from "node:assert/strict";
import test from "node:test";
import { requestAssistant } from "../src/assistant.js";
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

test("legacy duplicate retains its top4 event coordinates; new cards keep their upsell positions", () => {
  const response = { top4: [direct, verified], upsell: [
    verified, { ...verified, offer_id: "unverified", checks: [{ result: "unknown" }] },
    { ...verified, offer_id: "orphan", anchor_offer_id: "unknown" }, related,
  ] };
  assert.deepEqual(upsellCards(response).map(card => [card.item.offer_id, card.block, card.position]),
    [[verified.offer_id, "top4", 2], ["related", "upsell", 4]]);
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
