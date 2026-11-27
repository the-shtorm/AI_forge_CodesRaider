FROM ghcr.io/astral-sh/uv:0.12.1 AS uv
FROM python:3.12-slim AS base
WORKDIR /app
COPY --from=uv /uv /usr/local/bin/uv
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --locked --no-default-groups --no-install-project
ENV PATH="/app/.venv/bin:${PATH}" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_LINK_MODE=copy
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]

FROM base AS docs
COPY app.py contract_http.py encoder.py ./
COPY search_contract/ ./search_contract/
USER 10001:10001

FROM base AS ml-deps
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --locked --no-default-groups --group ml --no-install-project

FROM ml-deps AS model
ENV HF_HOME=/opt/hf \
    OMP_NUM_THREADS=2 \
    MKL_NUM_THREADS=2
COPY encoder.py ./
RUN --mount=type=cache,target=/cache/hub,id=smart-search-e5 python - <<'PY'
from pathlib import Path
from shutil import copytree
from huggingface_hub import snapshot_download
from encoder import MODEL_ID, MODEL_REVISION

cache = Path("/cache/hub")
snapshot = Path(snapshot_download(
    MODEL_ID,
    revision=MODEL_REVISION,
    cache_dir=cache,
    allow_patterns=[
        "model.safetensors", "config.json", "modules.json", "1_Pooling/config.json",
        "sentence_bert_config.json", "tokenizer_config.json", "tokenizer.json",
        "special_tokens_map.json", "sentencepiece.bpe.model",
    ],
))
copytree(snapshot, Path("/opt/hf/hub") / snapshot.relative_to(cache))
PY
ENV HF_HUB_OFFLINE=1
COPY app.py contract_http.py demo_search.py ./
COPY search_contract/ ./search_contract/
USER 10001:10001
