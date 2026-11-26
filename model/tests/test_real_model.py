"""Set RUN_MODEL_SMOKE=1 to exercise the loaded E5 weights on the synthetic catalog."""

import asyncio
import math
import os
import platform
import time
import unittest

import httpx


@unittest.skipUnless(os.environ.get("RUN_MODEL_SMOKE") == "1", "Requires the pinned model weights")
class RealModelSmokeTest(unittest.IsolatedAsyncioTestCase):
    async def test_russian_file_recovery_query_ranks_backup_first(self):
        from app import create_app
        from demo_search import DEMO_CATALOG

        app = create_app()
        await self.enterAsyncContext(app.router.lifespan_context(app))
        client = await self.enterAsyncContext(
            httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://local")
        )
        # The smoke run expects a prepared cache. Normal contract tests never load weights.
        async with asyncio.timeout(180):
            while True:
                readiness = await client.get("/ready")
                if readiness.status_code == 200:
                    break
                self.assertEqual(readiness.status_code, 503, readiness.text)
                await asyncio.sleep(0.1)
        profile = readiness.json()["profile_id"]
        self.assertTrue(profile)
        self.assertEqual(readiness.json()["dimension"], 384)

        async def encode(input_type, texts):
            started = time.perf_counter()
            response = await client.post(
                "/internal/v1/embeddings",
                json={"profile_id": profile, "input_type": input_type, "texts": texts},
            )
            elapsed_ms = (time.perf_counter() - started) * 1000
            self.assertEqual(response.status_code, 200, response.text)
            batch = response.json()
            self.assertEqual(batch["profile_id"], profile)
            self.assertEqual(batch["dimension"], 384)
            vectors = batch["vectors"]
            self.assertEqual(len(vectors), len(texts))
            for vector in vectors:
                self.assertEqual(len(vector), 384)
                self.assertTrue(all(math.isfinite(component) for component in vector))
                self.assertAlmostEqual(math.hypot(*vector), 1.0, delta=1e-4)
            return vectors, elapsed_ms

        labels, passages = zip(*DEMO_CATALOG)
        documents, batch_ms = await encode("passage", list(passages))
        queries, query_ms = await encode("query", ["Как вернуть утерянные файлы после сбоя диска?"])
        scores = {
            label: math.sumprod(queries[0], vector)
            for label, vector in zip(labels, documents, strict=True)
        }
        print(
            f"E5 CPU smoke · {platform.processor() or platform.machine()} · "
            f"{os.cpu_count()} logical CPUs · warm query {query_ms:.1f} ms · "
            f"batch of {len(passages)} {batch_ms:.1f} ms · scores {scores}"
        )
        self.assertGreater(scores["backup"], max(v for k, v in scores.items() if k != "backup"))
