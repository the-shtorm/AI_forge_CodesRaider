# Hierarchical Search and Curated Upsell Design

## Intent

Make every imported offer searchable while keeping the full 136,040-offer catalog usable. A broad product query should lead to a product family, a specific SKU/version/title should lead to an offer, and an ungrouped offer must remain a first-class search result. Every response should show at least four useful cards overall without presenting recommendations as genuine matches.

## Goals

- Index and retrieve offers that have neither `group_id` nor a derived hardware family.
- Distinguish a family result from an offer result in the public API and UI.
- Return a family for broad product intent and a concrete offer for SKU, exact title, or version-constrained intent.
- Keep the existing family entity page as the hierarchical place where its individual offers are shown.
- Produce 3–8 upsell cards for a matched query and 4–8 curated cards for a completely unrelated query.
- Keep `direct_count=0` and `shortfall_reason=NO_DIRECT_MATCH` when only fallback recommendations exist.
- Track family, offer, complement, and curated recommendation clicks without changing their semantic roles.

## Non-goals

- Do not flatten all variants of every family into the main result grid.
- Do not claim that a curated fallback matches the user's query.
- Do not build behavioral recommendation or popularity scoring without source metrics.
- Do not remove the existing `top4`, `more_results`, `alternatives`, or `upsell` response blocks.

## Selected approach

Use hierarchical results plus an explicit recommendation block.

- A Lucene `card_key` is always present: the family key when a family exists, otherwise `offer/<offer_id>`.
- Broad lexical/semantic retrieval remains one hit per `card_key`.
- Result cards add an explicit destination: `result_kind` (`family` or `offer`), `target_kind`, and `target_id`. Existing `offer_id` and `family_key` fields remain for compatibility and analytics.
- Family cards use the best representative offer for price and availability, then link to the family page containing all offers.
- Exact SKU, exact offer title, selected offer suggestion, or a version constraint that identifies a concrete SKU returns an offer result and links directly to it.
- Standalone offers use `family_key=offer/<id>`, `result_kind=offer`, and participate in lexical, semantic, prefix, exact-name, catalog, and recommendation retrieval.

### Alternatives considered

1. **Two independent tabs for families and offers.** Clear, but forces users to repeat the same search and complicates the existing storefront. Rejected for the first release.
2. **Flat mixed results containing every offer and family.** Simple indexing, but creates large duplicate-heavy pages for licensing catalogs. Rejected.
3. **Hierarchical family results with direct offer escape hatches.** Selected because it preserves compact browsing while making exact products reachable.

## Search and catalog behavior

1. Import assigns every offer a stable card key.
2. Lucene stores this key as both a searchable/stored field and sorted doc value; vector passages also use it.
3. Offer entities are included in the name/prefix index so standalone products can appear in suggestions and exact-title resolution.
4. SQLite continues to store both `entity_key` and nullable `family_key`; SQL uses `coalesce(family_key, entity_key)` for broad browsing.
5. Card creation receives the intended destination instead of inferring navigation from the representative offer alone.
6. Family results expose the number of eligible offers as `offer_count`; offer results expose `offer_count=1`.
7. Existing explicit filters, availability tiers, compatibility checks, and exact-SKU behavior remain authoritative.

## Upsell policy

Recommendations remain in `upsell`; they never increase `direct_count`.

For a request with direct results:

- target count: deterministic 3–8 cards;
- priority: verified complements, catalog relations, paired categories, profile-aware suggestions, then curated fallback;
- direct, alternative, and already selected family/offer keys are excluded.

For a request with no direct results:

- keep the empty direct result and its failure reason;
- target count: deterministic 4–8 cards;
- use contextual alternatives first when trustworthy, then fill from the curated pool;
- label curated cards with `role=related`, `recommendation_kind=curated_fallback`, and a neutral explanation that they are broadly useful catalog products.

The requested count and starting position in the pool are derived from a stable hash of the normalized query. The same query therefore produces a stable set, while different irrelevant queries can show different subsets. Missing, unavailable, unpriced, wrong-currency, duplicate, or filtered-out configured offers are skipped.

## Curated pool

- Store 20–30 offer IDs in `backend/src/main/resources/search/curated-offers.txt`, one ID per line with comments allowed.
- Select a broad mix from the current full feed: office software, security, backup, productivity, common peripherals, storage, networking, and practical accessories.
- Configuration order is editorial priority, not a hard guarantee of display order.
- Importing another catalog does not fail when some IDs disappear; unavailable IDs are skipped and surfaced in diagnostics.
- Tests use a synthetic curated list and do not depend on the production feed IDs.

## Frontend behavior

- A family card is marked “Семейство” and links to `kind=family&id=<family-id>`.
- An offer card is marked “Предложение”, shows its SKU/version when present, and links to `kind=offer&id=<offer-id>`.
- The recommendation section renders up to eight cards and uses “Возможно, вас заинтересует” when recommendations are not anchored to a direct product.
- A no-match page states that exact matches were not found before showing curated cards.
- Tracking keeps the `upsell` block and records the concrete `offer_id` used for a family representative.

## Compatibility and migration

- Existing response fields and blocks remain present.
- `family_key` remains a stable analytics grouping key; standalone offers use `offer/<id>`.
- The Lucene schema changes, so the application image must be rebuilt and the full catalog re-imported. Restarting against the old snapshot is insufficient.
- Existing event databases remain valid because events already store both `family_key` and `offer_id`.

## Verification

- Red/green regression: title search for an ungrouped offer returns it; the old index behavior fails this test.
- Regression: exact SKU and selected offer suggestion return the concrete offer destination.
- Regression: broad family query returns one family destination and its entity page lists the offers.
- Regression: matched queries receive 3–8 deduplicated upsells.
- Regression: irrelevant queries keep zero direct results and receive 4–8 curated, available, priced offers.
- Full Java test suite and frontend production build.
- Rebuild containers, re-import the full catalog with embeddings, and verify `Термобутылка с логотипом «Софтлайн», 500 мл.` plus `BTL-005` through the public API and browser.

