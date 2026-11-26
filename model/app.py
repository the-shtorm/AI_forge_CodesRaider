from collections.abc import Callable
from contextlib import asynccontextmanager
from time import perf_counter
from typing import Annotated, Literal

from fastapi import APIRouter, Body, Request
from fastapi.exceptions import RequestValidationError
from pydantic import Field, FiniteFloat

from contract_http import DTO, DocumentedAPI, Text, error_doc, problem, validation_handler
from encoder import DIMENSION, PROFILE_ID, E5Model, EncoderError, EncoderRuntime, Model, log


class ModelHealth(DTO):
    status: Literal["up"]


class Ready(DTO):
    ready: Literal[True]
    profile_id: Text = Field(description="ID профиля после загрузки и прогрева.")
    dimension: Literal[384]


class QueryBatch(DTO):
    input_type: Literal["query"]
    profile_id: Text
    texts: Annotated[list[Text], Field(min_length=1, max_length=1)]


class PassageBatch(DTO):
    input_type: Literal["passage"]
    profile_id: Text
    texts: Annotated[list[Text], Field(min_length=1, max_length=32)]


Vector = Annotated[list[FiniteFloat], Field(min_length=384, max_length=384)]


class Embeddings(DTO):
    profile_id: Text
    dimension: Literal[384]
    vectors: Annotated[
        list[Vector],
        Field(
            min_length=1,
            max_length=32,
            description=(
                "Один L2-нормализованный вектор на каждый входной текст в исходном порядке."
            ),
        ),
    ]


router = APIRouter()
UNAVAILABLE_CODES = {
    "MODEL_NOT_READY": (503, "Модель ещё не прогрета."),
    "MODEL_BUSY": (503, "Очередь заполнена."),
    "INVALID_MODEL_OUTPUT": (503, "Энкодер вернул некорректные векторы."),
    "INFERENCE_FAILED": (503, "Энкодер не смог завершить вычисление."),
}
MISMATCH = {"PROFILE_MISMATCH": (409, "Профиль запроса отличается от активного.")}


def active_encoder(request: Request) -> EncoderRuntime:
    runtime = request.app.state.encoder
    if runtime is None or runtime.model is None:
        raise EncoderError("MODEL_NOT_READY")
    return runtime


@router.get(
    "/health",
    response_model=ModelHealth,
    operation_id="getEncoderHealth",
    tags=["Состояние"],
    summary="Проверить HTTP-процесс",
)
async def health():
    """Используется Docker Compose и Spring Boot для проверки связи с FastAPI."""
    return {"status": "up"}


@router.get(
    "/ready",
    response_model=Ready,
    operation_id="getEncoderReadiness",
    tags=["Состояние"],
    summary="Узнать профиль прогретого энкодера",
    responses={
        200: {
            "description": "Энкодер прогрет.",
            "content": {
                "application/json": {
                    "example": {"ready": True, "profile_id": PROFILE_ID, "dimension": DIMENSION}
                }
            },
        },
        503: error_doc("Профиль ещё недоступен", MODEL_NOT_READY=(503, "Модель ещё не прогрета.")),
    },
)
async def readiness(request: Request):
    """При импорте Java сохраняет profile_id в манифест снимка. Профиль фиксирует модель,
    SHA весов, токенизацию, префиксы, pooling, размерность и нормализацию."""
    active_encoder(request)
    return {"ready": True, "profile_id": PROFILE_ID, "dimension": DIMENSION}


@router.post(
    "/internal/v1/embeddings",
    response_model=Embeddings,
    operation_id="encodeTexts",
    tags=["Эмбеддинги"],
    summary="Закодировать запрос или пакет карточек",
    responses={
        200: {
            "description": "Векторы в порядке входных текстов.",
            "content": {
                "application/json": {
                    "examples": {
                        "unit": {
                            "summary": "Синтетический единичный вектор для проверки формата",
                            "value": {
                                "profile_id": PROFILE_ID,
                                "dimension": 384,
                                "vectors": [[1.0] + [0.0] * 383],
                            },
                        }
                    }
                }
            },
        },
        409: error_doc("Профили различаются", **MISMATCH),
        422: error_doc(
            "Ошибка входных данных",
            INVALID_REQUEST=(422, "Для query нужен ровно один непустой текст."),
        ),
        503: error_doc("Энкодер временно недоступен", **UNAVAILABLE_CODES),
    },
)
async def encode_texts(
    request: Request,
    body: Annotated[
        QueryBatch | PassageBatch,
        Body(
            discriminator="input_type",
            openapi_examples={
                "query": {
                    "summary": "Один запрос витрины",
                    "value": {
                        "input_type": "query",
                        "profile_id": PROFILE_ID,
                        "texts": ["Как восстановить файлы после сбоя?"],
                    },
                },
                "passage": {
                    "summary": "Пакет при импорте",
                    "value": {
                        "input_type": "passage",
                        "profile_id": PROFILE_ID,
                        "texts": ["Средства резервного копирования", "Операционные системы"],
                    },
                },
            },
        ),
    ],
):
    """Передавайте текст без служебного префикса. Энкодер добавляет query: или passage: один раз,
    ограничивает вход 512 токенами и выдаёт 384 конечных числа для каждого текста.
    При отказе энкодера публичный поиск использует лексический режим."""
    started = perf_counter()
    outcome = "cancelled"
    try:
        runtime = active_encoder(request)
        vectors = await runtime.encode(body.input_type, body.texts, body.profile_id)
        outcome = "ok"
        return {"profile_id": PROFILE_ID, "dimension": DIMENSION, "vectors": vectors}
    except EncoderError as exc:
        outcome = str(exc)
        raise
    finally:
        log.info(
            "E5 input_type=%s batch=%d elapsed_ms=%.1f outcome=%s",
            body.input_type,
            len(body.texts),
            (perf_counter() - started) * 1000,
            outcome,
        )


def create_app(
    loader: Callable[[], Model] = E5Model, *, workers: int = 1, max_waiting: int = 2
) -> DocumentedAPI:
    @asynccontextmanager
    async def lifespan(application):
        runtime = EncoderRuntime(loader, workers, max_waiting)
        application.state.encoder = runtime
        runtime.start()
        try:
            yield
        finally:
            application.state.encoder = None
            await runtime.close()

    application = DocumentedAPI(
        title="Умный поиск Softline · энкодер",
        version="1.0.0",
        description=(
            "Внутренний API для Spring Boot. GET /health проверяет HTTP-процесс. "
            "После загрузки и прогрева CPU-модели GET /ready возвращает profile_id для "
            "POST /internal/v1/embeddings. На отдельном справочном стенде модель отключена."
        ),
        openapi_tags=[{"name": "Состояние"}, {"name": "Эмбеддинги"}],
        lifespan=lifespan,
    )
    application.state.encoder = None
    application.add_exception_handler(RequestValidationError, validation_handler(422))

    @application.exception_handler(EncoderError)
    async def encoder_error(request: Request, exc: EncoderError):
        code = str(exc)
        if code in MISMATCH:
            return problem(409, code, "Профили различаются", MISMATCH[code][1])
        status, detail = UNAVAILABLE_CODES[code]
        return problem(status, code, "Энкодер временно недоступен", detail)

    application.include_router(router)
    return application


app = create_app()
