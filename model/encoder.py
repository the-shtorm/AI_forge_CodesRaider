"""Pinned E5 encoder and the bounded inference pool for one FastAPI process."""

import asyncio
import hashlib
import json
import logging
import math
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from typing import Protocol

MODEL_ID = "intfloat/multilingual-e5-small"
MODEL_REVISION = "614241f622f53c4eeff9890bdc4f31cfecc418b3"
DIMENSION = 384
TOKEN_LIMIT = 512
PREFIXES = {"query": "query: ", "passage": "passage: "}

_PROFILE = {
    "model": MODEL_ID,
    "revision": MODEL_REVISION,
    "tokenizer": "native-fast",
    "max_tokens": TOKEN_LIMIT,
    "pooling": "attention-mask-mean",
    "normalization": "l2",
    "prefixes": PREFIXES,
    "dimension": DIMENSION,
    "precision": "float32",
}
PROFILE_ID = (
    "e5-"
    + hashlib.sha256(
        json.dumps(_PROFILE, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
)
log = logging.getLogger("uvicorn.error")


class Model(Protocol):
    def encode(self, texts: list[str]) -> list[list[float]]: ...


class E5Model:
    def __init__(self):
        # Importing the API or serving its mounted documentation must not load torch.
        from sentence_transformers import SentenceTransformer

        self.model = SentenceTransformer(
            MODEL_ID,
            revision=MODEL_REVISION,
            device="cpu",
            trust_remote_code=False,
            tokenizer_kwargs={"use_fast": True},
        )
        self.model.max_seq_length = TOKEN_LIMIT
        self.model.float().eval()

    def encode(self, texts: list[str]) -> list[list[float]]:
        return self.model.encode(
            texts,
            batch_size=32,
            normalize_embeddings=True,
            convert_to_numpy=True,
            show_progress_bar=False,
            prompt="",
        ).tolist()


class EncoderError(Exception):
    """Expose a stable error code without logging a caller's input."""


def checked_encode(model: Model, texts: list[str]) -> list[list[float]]:
    try:
        rows = model.encode(texts)
    except Exception as exc:  # noqa: BLE001 - isolate failures in the external inference library.
        log.error("E5 inference failed (%s)", type(exc).__name__)
        raise EncoderError("INFERENCE_FAILED") from None

    try:
        if len(rows) != len(texts):
            raise ValueError
        for row in rows:
            if len(row) != DIMENSION or not all(
                type(x) in (int, float) and math.isfinite(x) for x in row
            ):
                raise ValueError
            if not math.isclose(math.hypot(*row), 1.0, abs_tol=1e-4):
                raise ValueError
    except (TypeError, ValueError, OverflowError):
        raise EncoderError("INVALID_MODEL_OUTPUT") from None
    return rows


class EncoderRuntime:
    """Only the event loop touches readiness and the admission counter."""

    def __init__(self, loader: Callable[[], Model], workers: int, max_waiting: int):
        if workers < 1 or max_waiting < 0:
            raise ValueError("workers must be positive and max_waiting nonnegative")
        self.pool = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="e5")
        self.limit = workers + max_waiting
        self.pending = 0
        self.model: Model | None = None
        self.loader = loader
        self.closing = False

    def start(self):
        future = asyncio.get_running_loop().run_in_executor(self.pool, self._warmup)
        future.add_done_callback(self._loaded)

    def _warmup(self) -> Model:
        model = self.loader()
        checked_encode(model, [PREFIXES["query"] + "warmup"])
        return model

    def _loaded(self, future: asyncio.Future):
        if future.cancelled():
            return
        exc = future.exception()
        if exc is not None:
            log.error("E5 model unavailable after warmup (%s)", type(exc).__name__)
            return
        if not self.closing:
            self.model = future.result()
            log.info("E5 ready, profile=%s", PROFILE_ID)

    def _finished(self, future: asyncio.Future):
        self.pending -= 1
        if not future.cancelled():
            future.exception()  # The HTTP caller may already have disconnected.

    async def encode(self, input_type: str, texts: list[str], profile_id: str):
        if self.model is None:
            raise EncoderError("MODEL_NOT_READY")
        if profile_id != PROFILE_ID:
            raise EncoderError("PROFILE_MISMATCH")
        if self.pending >= self.limit:
            raise EncoderError("MODEL_BUSY")
        prefix = PREFIXES[input_type]
        prefixed = [text if text.startswith(prefix) else prefix + text for text in texts]
        future = asyncio.get_running_loop().run_in_executor(
            self.pool, checked_encode, self.model, prefixed
        )
        self.pending += 1
        future.add_done_callback(self._finished)
        # Cancellation of the HTTP task must not free a still-running worker slot.
        return await asyncio.shield(future)

    async def close(self):
        self.closing = True
        self.model = None
        await asyncio.to_thread(self.pool.shutdown, wait=True, cancel_futures=True)
