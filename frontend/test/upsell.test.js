import assert from "node:assert/strict";
import test from "node:test";
import { demoSearch } from "../src/catalog.js";
import { requestAssistant } from "../src/assistant.js";
import { upsellCards } from "../src/upsell.js";

test("legacy duplicate retains its top4 event coordinates; new cards keep their upsell positions", () => {
  const response = demoSearch({ q: "P2500W" });
  const verified = response.top4[1];
  response.upsell.push({ ...verified, offer_id: "unverified", checks: [{ result: "unknown" }] },
    { ...verified, offer_id: "orphan", anchor_offer_id: "unknown" },
    { offer_id: "related", role: "related", recommendation_kind: "catalog_pick", recommendation_reason: "В каталоге" });
  assert.deepEqual(upsellCards(response).map(card => [card.item.offer_id, card.block, card.position]),
    [[verified.offer_id, "top4", 2], ["related", "upsell", 4]]);
});

test("assistant retains separate recommendations and event coordinates when top4 is full", async t => {
  const response = demoSearch({ q: "сервер" });
  assert.equal(response.top4.length, 4);
  assert.ok(response.top4.every(item => item.role === "direct"));
  t.mock.method(globalThis, "fetch", async () => Response.json(response));
  const answer = await requestAssistant("сервер", [], "session");
  assert.equal(answer.products.length, 4);
  assert.ok(answer.upsell.length > 0);
  assert.ok(answer.upsell.every((item, i) => item.block === "upsell" && item.position === i + 1
    && item.role === "related" && item.recommendation_reason));
});

test("a query without a match can show independent catalog picks, and an opt-out suppresses both blocks", () => {
  const miss = demoSearch({ q: "zzzzzzzzzz" });
  assert.equal(miss.direct_count, 0);
  assert.ok(upsellCards(miss).some(({ item }) => !item.anchor_offer_id && item.recommendation_kind === "catalog_pick"));
  const optOut = demoSearch({ q: "BM1800W без картриджей" });
  assert.deepEqual(optOut.top4.map(item => item.offer_id), ["521379"]);
  assert.deepEqual(upsellCards(optOut), []);
});
