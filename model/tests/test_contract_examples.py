import unittest

from app import app as encoder
from search_contract.api import app as search
from search_contract.schemas import Entity, SearchRequest, SearchResponse, Suggestion


def response_examples(app, path, method="get"):
    responses = app.openapi()["paths"][path][method]["responses"]
    return responses["200"]["content"]["application/json"]["examples"]


class PublishedExamplesTest(unittest.TestCase):
    def test_search_examples_keep_required_nulls_and_card_links(self):
        for name, example in response_examples(search, "/v1/search", "post").items():
            with self.subTest(name=name):
                result = SearchResponse.model_validate(example["value"])
                families = {item.family_key for item in result.top4}
                direct_ids = {item.offer_id for item in result.top4 if item.role == "direct"}
                self.assertEqual(len(families), len(result.top4))
                self.assertEqual(result.shortfall_reason is None, len(result.top4) == 4)
                for item in result.top4:
                    if item.role == "complement":
                        self.assertIn(item.anchor_offer_id, direct_ids)

    def test_backup_navigation_uses_one_collection_across_operations(self):
        op = search.openapi()["paths"]["/v1/search"]["post"]
        requests = op["requestBody"]["content"]["application/json"]["examples"]
        selected = SearchRequest.model_validate(requests["selected_backup"]["value"])
        free_text = SearchRequest.model_validate(requests["backup"]["value"])
        suggest = response_examples(search, "/v1/suggest")["backup"]["value"]
        suggestion = Suggestion.model_validate(suggest[0])
        result = SearchResponse.model_validate(response_examples(search, "/v1/search", "post")["backup"]["value"])
        entity = Entity.model_validate(response_examples(search, "/v1/entities/{kind}/{id}")["backup"]["value"])

        self.assertIsNone(free_text.selected_target)
        for kind, target_id in (
            (suggestion.kind, suggestion.target_id),
            (selected.selected_target.kind, selected.selected_target.target_id),
            (result.primary_target.kind, result.primary_target.id),
            (entity.kind, entity.id),
        ):
            self.assertEqual((kind, target_id), ("collection", "cyberprotect-1033"))
        self.assertEqual(result.primary_target.url, entity.url)
        self.assertEqual(result.applied_filters.vendor_id, "cyberprotect")
        self.assertEqual(result.applied_filters.category_id, "1033")
        self.assertEqual(result.direct_count, 4)
        self.assertEqual({c.family_key for c in result.top4 if c.role == "direct"},
                         {"family/173614", "family/173615", "family/442025", "family/444870"})
        self.assertEqual({(c.offer_id, c.url) for c in result.top4},
                         {(c.id, c.url) for c in entity.children if c.kind == "offer"})

    def test_upsell_has_its_own_limit_and_separates_verified_pairs_from_catalog_picks(self):
        responses = response_examples(search, "/v1/search", "post")
        pantum = SearchResponse.model_validate(responses["pantum"]["value"])
        self.assertEqual([c.offer_id for c in pantum.upsell], [c.offer_id for c in pantum.top4 if c.role == "complement"])
        result = SearchResponse.model_validate(responses["absent"]["value"])
        self.assertEqual((result.direct_count, result.top4), (0, []))
        self.assertEqual(result.upsell[0].recommendation_kind, "catalog_pick")
        self.assertTrue(result.upsell[0].recommendation_reason)
        for invalid in (dict(responses["absent"]["value"], upsell=result.upsell * 5),
                        dict(responses["absent"]["value"], top4=result.upsell)):
            with self.assertRaises(ValueError):
                SearchResponse.model_validate(invalid)

    def test_absent_model_keeps_the_analog_outside_top4(self):
        example = response_examples(search, "/v1/search", "post")["analog"]
        result = SearchResponse.model_validate(example["value"])
        self.assertEqual((result.direct_count, result.top4, result.shortfall_reason), (0, [], "NO_DIRECT_MATCH"))
        self.assertEqual([c.offer_id for c in result.alternatives], ["363882"])
        self.assertIn("P2507", result.alternatives[0].differences[0])

    def test_reserve_keeps_null_price_and_sku(self):
        example = response_examples(search, "/v1/entities/{kind}/{id}")["reserve"]
        item = Entity.model_validate(example["value"]).offers[0]
        self.assertEqual(item.offer_tier, "reserve")
        self.assertIsNone(item.price_minor)
        self.assertIsNone(item.sku)

    def test_embedding_example_has_declared_dimension(self):
        example = response_examples(encoder, "/internal/v1/embeddings", "post")["unit"]["value"]
        self.assertEqual(example["dimension"], 384)
        self.assertEqual(len(example["vectors"][0]), 384)
