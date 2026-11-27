"""Exercise the encoder's HTTP contract without loading model weights."""

import asyncio
import unittest
from threading import Event
from unittest.mock import Mock

import httpx

from app import create_app
from encoder import PROFILE_ID

ENDPOINT = "/internal/v1/embeddings"


def unit_vector(axis=0):
    vector = [0.0] * 384
    vector[axis] = 1.0
    return vector


def valid_batch(texts):
    return [unit_vector(i) for i in range(len(texts))]


def payload(texts=("Запрос",), *, kind="query", profile=PROFILE_ID):
    return {"input_type": kind, "profile_id": profile, "texts": list(texts)}


class EncoderAPITest(unittest.IsolatedAsyncioTestCase):
    async def open_client(self, loader, **settings):
        app = create_app(loader=loader, **settings)
        await self.enterAsyncContext(app.router.lifespan_context(app))
        return await self.enterAsyncContext(
            httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")
        )

    async def await_ready(self, client):
        async with asyncio.timeout(5):
            while True:
                response = await client.get("/ready")
                if response.status_code == 200:
                    self.assertEqual(
                        response.json(), {"dimension": 384, "ready": True, "profile_id": PROFILE_ID}
                    )
                    return
                self.assert_problem(response, 503, "MODEL_NOT_READY")
                await asyncio.sleep(0.01)

    async def ready_encoder(self, **settings):
        encoder = Mock()
        encoder.encode.side_effect = valid_batch
        client = await self.open_client(lambda: encoder, **settings)
        await self.await_ready(client)
        return client, encoder

    def assert_problem(self, response, status, code):
        self.assertEqual(response.status_code, status, response.text)
        self.assertEqual(response.headers["content-type"].split(";")[0], "application/problem+json")
        self.assertEqual(response.json()["status"], status)
        self.assertEqual(response.json()["code"], code)

    async def test_warmup_precedes_readiness_while_process_stays_live(self):
        warming, release = Event(), Event()

        def warmup(texts):
            warming.set()
            if not release.wait(5):
                raise TimeoutError("Test did not release the warmup")
            return valid_batch(texts)

        encoder = Mock()
        encoder.encode.side_effect = warmup
        loader = Mock(return_value=encoder)
        client = await self.open_client(loader)
        try:
            self.assertTrue(await asyncio.to_thread(warming.wait, 3))
            self.assertEqual((await client.get("/health")).json(), {"status": "up"})
            self.assert_problem(await client.get("/ready"), 503, "MODEL_NOT_READY")
            self.assert_problem(await client.post(ENDPOINT, json=payload()), 503, "MODEL_NOT_READY")
        finally:
            release.set()
        await self.await_ready(client)
        loader.assert_called_once()
        self.assertEqual(encoder.encode.call_count, 1)

    async def test_prefixes_order_and_largest_import_batch(self):
        client, encoder = await self.ready_encoder()
        query = "Astra Linux 1.8 — вернуть файлы?"
        response = await client.post(ENDPOINT, json=payload([query]))
        self.assertEqual(response.status_code, 200, response.text)
        encoder.encode.assert_called_with(["query: " + query])
        already_prefixed = "query: " + query
        response = await client.post(ENDPOINT, json=payload([already_prefixed]))
        self.assertEqual(response.status_code, 200, response.text)
        encoder.encode.assert_called_with([already_prefixed])
        texts = [f"Товар №{i}: CSP 5.0" for i in range(32)]
        response = await client.post(ENDPOINT, json=payload(texts, kind="passage"))
        self.assertEqual(response.status_code, 200, response.text)
        encoder.encode.assert_called_with(["passage: " + text for text in texts])
        self.assertEqual(
            response.json(),
            {"dimension": 384, "profile_id": PROFILE_ID, "vectors": valid_batch(texts)},
        )

    async def test_invalid_bodies_and_stale_profile_do_not_reach_encoder(self):
        client, encoder = await self.ready_encoder()
        encoder.encode.reset_mock()
        for body in [
            payload([]),
            payload(["a", "b"]),
            payload([" "]),
            payload(["valid", "\t"], kind="passage"),
            payload([], kind="passage"),
            payload(["a"] * 33, kind="passage"),
            payload(kind="unknown"),
            payload(profile=" "),
            {"input_type": "query", "texts": ["a"]},
        ]:
            with self.subTest(body=body):
                self.assert_problem(await client.post(ENDPOINT, json=body), 422, "INVALID_REQUEST")
        self.assert_problem(
            await client.post(ENDPOINT, json=payload(profile="old-index-profile")),
            409,
            "PROFILE_MISMATCH",
        )
        encoder.encode.assert_not_called()

    async def test_failed_load_keeps_process_available(self):
        attempted = Event()

        def missing_weights():
            attempted.set()
            raise OSError("Missing cached weights")

        client = await self.open_client(missing_weights)
        self.assertTrue(await asyncio.to_thread(attempted.wait, 3))
        self.assertEqual((await client.get("/health")).status_code, 200)
        self.assert_problem(await client.get("/ready"), 503, "MODEL_NOT_READY")
        self.assert_problem(await client.post(ENDPOINT, json=payload()), 503, "MODEL_NOT_READY")

    async def test_bad_vectors_and_inference_failure_are_recoverable(self):
        client, encoder = await self.ready_encoder()
        encoder.encode.side_effect = None
        for output in (
            [],
            [unit_vector(), unit_vector()],
            [[1.0]],
            [[0.0] * 384],
            [[float("nan")] + [0.0] * 383],
            [[float("inf")] + [0.0] * 383],
            [[2.0] + [0.0] * 383],
        ):
            with self.subTest(output_count=len(output), first=output[0][0] if output else None):
                encoder.encode.return_value = output
                self.assert_problem(
                    await client.post(ENDPOINT, json=payload()), 503, "INVALID_MODEL_OUTPUT"
                )
        encoder.encode.side_effect = RuntimeError("Fake inference failure")
        self.assert_problem(await client.post(ENDPOINT, json=payload()), 503, "INFERENCE_FAILED")
        encoder.encode.side_effect = valid_batch
        self.assertEqual((await client.post(ENDPOINT, json=payload())).status_code, 200)

    async def test_one_waiter_is_admitted_and_excess_work_is_rejected(self):
        client, encoder = await self.ready_encoder(workers=1, max_waiting=1)
        entered, release = Event(), Event()

        def block_first(texts):
            if texts == ["query: first"]:
                entered.set()
                if not release.wait(5):
                    raise TimeoutError("Test did not release the first request")
            return valid_batch(texts)

        encoder.encode.side_effect = block_first
        first = asyncio.create_task(client.post(ENDPOINT, json=payload(["first"])))
        try:
            self.assertTrue(await asyncio.to_thread(entered.wait, 3))
            others = {
                asyncio.create_task(client.post(ENDPOINT, json=payload([text])))
                for text in ("second", "third")
            }
            completed, admitted = await asyncio.wait(
                others, timeout=2, return_when=asyncio.FIRST_COMPLETED
            )
            self.assertEqual(len(completed), 1)
            self.assertEqual(len(admitted), 1)
            self.assert_problem(completed.pop().result(), 503, "MODEL_BUSY")
        finally:
            release.set()
        self.assertEqual((await first).status_code, 200)
        self.assertEqual((await admitted.pop()).status_code, 200)

    async def test_cancelled_http_request_holds_slot_until_inference_finishes(self):
        client, encoder = await self.ready_encoder(workers=1, max_waiting=0)
        entered, release = Event(), Event()

        def blocking_encode(texts):
            entered.set()
            if not release.wait(5):
                raise TimeoutError("Test did not release the encoder")
            return valid_batch(texts)

        encoder.encode.side_effect = blocking_encode
        pending = asyncio.create_task(client.post(ENDPOINT, json=payload()))
        try:
            self.assertTrue(await asyncio.to_thread(entered.wait, 3))
            self.assertEqual((await client.get("/health")).status_code, 200)
            pending.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await pending
            self.assert_problem(await client.post(ENDPOINT, json=payload()), 503, "MODEL_BUSY")
        finally:
            release.set()
        async with asyncio.timeout(5):
            while True:
                retry = await client.post(ENDPOINT, json=payload())
                if retry.status_code == 200:
                    break
                self.assert_problem(retry, 503, "MODEL_BUSY")
                await asyncio.sleep(0.01)
