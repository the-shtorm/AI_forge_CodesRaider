# FastAPI model and retrieval implementation handoff

## Task for the implementing agent

Implement the standalone CPU embedding service for Softline Smart Search under `model/`. Deliver a runnable FastAPI app, locked Python dependencies, a CPU Dockerfile, API tests, a real-model smoke test, and run instructions. Use a small synthetic catalog fixture so the module can be developed before the Softline XML and Spring Boot application are available. Use the API and acceptance checks below as the implementation brief.

Spring Boot owns the catalog, Lucene index, search API, product selection, and analytics. FastAPI supplies embeddings to Spring. React calls the public Spring API. Implement the encoder and its API in this task. The retrieval rules describe how Spring will consume that API. The RAG design covers a later feature for questions that need a sourced explanation.

## FastAPI service

Use Python 3.12, FastAPI, Pydantic, Sentence Transformers, and CPU PyTorch. Load `intfloat/multilingual-e5-small` once per process using an immutable Hugging Face revision. Document the exact revision and lock the Python dependencies. Warm the model before accepting embeddings. Keep a single model process for the initial container, and document how to populate the model cache before an offline demo. [E5 model card](https://huggingface.co/intfloat/multilingual-e5-small)

### Encode a query or a catalog passage

`POST /internal/v1/embeddings` accepts raw text. `profile_id` is the expected ID from `GET /ready` for a new index, or the ID persisted in the active index manifest for live search.

```json
{
  "profile_id": "<expected profile ID>",
  "input_type": "query",
  "texts": ["Как восстановить файлы после сбоя?"]
}
```

| Field | Behavior |
|---|---|
| `input_type` | `query` accepts exactly one text. `passage` accepts 1–32 texts for catalog import. |
| `texts` | Require nonblank strings. Preserve the input order, numbers, and punctuation. Spring sends text without E5 prefixes. |
| Encoding | Add `query: ` or `passage: ` once according to `input_type`. Use the model tokenizer with a 512-token limit, masked mean pooling, and L2 normalization. |
| Success | Return `profile_id`, `dimension: 384`, and `vectors`, an array containing one 384-element numeric vector per input string in the original order. Reject malformed, nonfinite, or zero-norm model output before serialization. |

Derive `profile_id` deterministically from a stable serialization of the model ID, immutable revision, tokenizer and pooling settings, token limit, prefixes, output dimension, and normalization settings. The importer records it in the Lucene snapshot manifest. A profile change requires new document vectors before Spring enables semantic search on that snapshot.

### Readiness and failures

`GET /ready` returns HTTP 200 after model load and warmup, with `ready`, `profile_id`, and `dimension`. While the process is reachable but the encoder is unavailable, return HTTP 503. Expose the contract through FastAPI’s `/openapi.json`.

| Status | Condition |
|---|---|
| `422` | Invalid request shape, unsupported `input_type`, blank text, or out-of-range batch size. |
| `409` | A valid request names a different embedding profile. Spring treats the index and service as incompatible. |
| `503` | Model unavailable, capacity exhausted, or the encoder cannot produce valid vectors. |

Run inference off the HTTP event loop. Bound concurrent inference and the number of waiting requests. Release capacity when the underlying inference finishes, including when the HTTP caller disconnects. Spring starts with a 200 ms online embedding budget and uses a longer timeout for import batches. If an embedding fails or exceeds the search deadline, Spring returns the lexical result. Log request type, batch size, latency, and outcome without capturing raw query text in model-service logs.

## Product retrieval in Spring Boot and Lucene

### Build the catalog index

1. Stream the XML feed into SQLite. Store SKU, availability, price, license type, version, term, device count, category, product family, and canonical URLs as structured fields. Keep absent attributes distinguishable from confirmed values.
2. Index offer text and exact keys in Lucene. Use one semantic document per software family and one per ungrouped hardware offer. Use `offer_id` for the physical SKU. Attach the canonical `family_key` to every candidate, including each hardware vector (vendor and confirmed base model). An unidentified hardware family cannot occupy a distinct top-four slot. Keep vendor, category, and scoped collection targets in the entity index. Resolving `collection/cyberprotect-1033` restricts both candidate lists to vendor `cyberprotect` and category `1033`.
3. Build each passage from the product name, purpose, category path, and confirmed descriptive attributes. Put the product name and purpose first so they survive truncation. Keep transactional fields such as live availability and price in SQLite. Send passages to FastAPI in ordered batches of up to 32. Validate the profile, vector count, and dimensions before writing normalized vectors to a Lucene dot-product field.
4. Build the snapshot beside the active one. Record the catalog hash, index version, and embedding profile in its manifest. Verify the snapshot before activation. A failed vector build leaves the current snapshot available, and a lexical-only snapshot can operate without a model service.

### Retrieve, rank, and validate

1. **Resolve exact intent.** Check an exact SKU or ID first. A unique exact SKU keeps its own destination, including an explicit unavailable status where applicable. Preserve digits and versions while normalizing spelling, keyboard layout, spacing, and known aliases. Exact SKU lookups and autocomplete bypass E5.
2. **Get candidates.** Retrieve up to 100 distinct family keys with BM25. Start with the architecture’s relative field boosts for family, title, vendor, category, attributes, and description of `8, 6, 4, 3, 2, 1`. For eligible task queries, encode one query and retrieve the top 50 vector neighbors. Request candidates in parallel where possible.
3. **Merge by family.** Map offer/entity IDs to verified `family_key`, keep the best hit per family in each list, then assign contiguous ranks and apply reciprocal rank fusion. For each list containing the entity, add `1 / (60 + rank)`, with ranks starting at 1. Use a stable tie-break so repeated runs are comparable. Skip vector retrieval when the active index and ready service have different profiles.
4. **Check individual offers.** Load the shortlisted variants from SQLite and apply all hard constraints to one SKU. A known conflict excludes the offer from direct matches. An unknown required attribute can appear in an explicitly labeled alternative for clarification. Choose the best valid SKU per family or distinct hardware model. Use product-role and intent rules to distinguish a primary license from support, an add-on, or a renewal. Retrieve additional candidates when filtering leaves too few valid offers and more candidates are available.
5. **Build the answer.** Return the primary destination, direct matches, alternatives, complements, and explicit reasons. Only direct matches and verified complements may occupy top4. Each complement references a visible direct SKU. Return substitutes in alternatives outside top4, and record a shortfall when fewer than four families qualify. Preserve the category distinction between `сервер`, which targets ready-to-buy hardware, and `ПО для сервера`, which targets software. Keep a lexical response available on timeout, overload, invalid model output, or profile mismatch. When an exact model is absent, return verified functional substitutes in `alternatives[]` on the first response, with the model difference and all mandatory attributes checked on the offered SKU.

For example, `Как восстановить файлы после сбоя?` can retrieve a backup product from a matching passage even when the user supplied no brand name. A query specifying `Astra Linux 1.8` also needs verified compatibility evidence before Spring can make that claim about a recommendation. An embedding similarity score ranks candidates and carries no product-level compatibility evidence. [System architecture](TECHNICAL_ARCHITECTURE.md)

## RAG for sourced explanations

The first release can explain product cards with reason codes and templates based on catalog and compatibility fields. Retrieval-augmented generation (RAG) adds a text answer for a question such as “Why does this backup product work with Astra Linux 1.8?” or “What is the difference between these editions?” The E5 encoder can support evidence retrieval when needed. A separate generation model writes the explanation from the selected evidence.

1. **Store evidence.** Keep reviewed vendor excerpts or compatibility records with an evidence ID, subject and related product keys, both version scopes where relevant, source URL, excerpt, and verification date. Keep table headers and any qualifying conditions with an extracted row.
2. **Retrieve evidence for the selected products.** Start with exact product and version filters. Structured records handle known compatibility pairs. If supporting documents grow, use Lucene and the same embedding API to rank passages within the eligible evidence. Keep evidence IDs and product IDs distinct.
3. **Generate from a bounded context.** Pass the question, verified catalog fields, and a small set of evidence records to the generator. Ask for an answer with claims linked to evidence IDs. Treat evidence text as reference data. Resolve citation URLs from the approved records server-side. Spring supplies IDs, URLs, price, availability, and version values from the catalog or evidence tables.
4. **Verify or fall back.** Verify that cited IDs were supplied and that compatibility claims match the known products and versions. For the compatibility example, the source supports the family pair **Кибер Бэкап 17.1 and Astra Linux SE 1.8**. If SKU `418697` has an unknown version, report the confirmed pair and say the offer’s version needs verification. If evidence is missing or generation times out, return a deterministic explanation or an explicit insufficient-evidence response. Product search can finish independently. [Compatibility evidence example](https://astra.ru/ready-for-astra/compatible-software/versions/38258/)

**Suggested future generation instruction**

```text
Answer the shopper’s question in the language they used. Use the supplied
catalog facts and evidence records as reference data. Return a short answer,
supporting evidence IDs, and an insufficient_evidence flag. For compatibility,
state the exact products and versions supported by the evidence. If the selected
offer’s version is unknown, say it needs verification. Use only evidence IDs
from the supplied context. The application will render the offer fields and URLs.
```

The architecture also describes an optional LLM experiment for turning a difficult query into structured intent and constraints before retrieval. Evaluate that experiment separately from RAG explanations. Cross-encoder reranking is another later experiment for improving the order of candidates already retrieved. [System architecture](TECHNICAL_ARCHITECTURE.md)

## Tests and acceptance

| Check | Acceptance |
|---|---|
| Unit and API tests | A fake encoder verifies exact prefixes, preservation of input order, request limits, invalid payloads, profile behavior, readiness, overload, and malformed encoder output. Default tests run without downloading weights. |
| Real-model smoke test | Encode both input types using the pinned revision. Verify shape, finite values, and unit norms within floating-point tolerance. A Russian file-recovery query scores a synthetic backup passage above a clearly unrelated synthetic product passage. Record warm query and batch timings with the CPU details. |
| Standalone handoff | The README documents commands for tests and Docker, model-cache preparation, the readiness check, and valid query and passage requests. A consumer can run them with the fixture alone. |
| Spring integration | Once Spring exists, exercise exact SKU bypass, `max patrol`, the server-versus-software distinction, a task query, and lexical fallback with FastAPI unavailable or mismatched. Check the ten organizer destinations separately from product-card quality. |
| Hybrid release decision | Compare BM25 and BM25 + E5 on the same held-out data. The project target is at least `+0.05` nDCG@4 for task queries, preserved FamilyHit@4 on eight product targets, exact-SKU results and both navigation targets, and end-to-end search p95 at or below 500 ms. Report actual measurements. |
| RAG acceptance when implemented | Test a supported version pair, an unknown SKU version, irrelevant evidence, and a generation failure. Accept an explanation only when its cited evidence supports the claim. |

The standalone model task is complete when the API tests and real-model smoke test pass and the container can reach readiness using the documented model revision. Full-catalog retrieval and RAG evaluation follow their respective integration work.
