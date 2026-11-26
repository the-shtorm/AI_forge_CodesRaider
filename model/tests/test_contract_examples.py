import unittest

from app import app as encoder
from search_contract.api import app as search
from search_contract.schemas import Entity, SearchResponse


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
