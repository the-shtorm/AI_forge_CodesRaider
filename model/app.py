from typing import Annotated, Literal

from fastapi import Body
from fastapi.exceptions import RequestValidationError
from pydantic import Field, FiniteFloat

from contract_http import DTO, DocumentedAPI, Text, error_doc, problem, validation_handler


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


app = DocumentedAPI(
    title="Умный поиск Softline · энкодер",
    version="1.0.0",
    description=(
        "Внутренний API для Spring Boot. GET /health сообщает о доступности HTTP-процесса. "
        "В каркасе энкодер ещё не загружен. Для /ready и корректного вызова /internal/v1/embeddings "
        "предусмотрен ответ 503. Примеры со статусом 200 описывают контракт готовой модели."
    ),
    openapi_tags=[{"name": "Состояние"}, {"name": "Эмбеддинги"}],
)
app.add_exception_handler(RequestValidationError, validation_handler(422))

UNAVAILABLE = error_doc(
    "Энкодер временно недоступен",
    MODEL_NOT_READY=(503, "Модель ещё не прогрета."),
    MODEL_BUSY=(503, "Очередь заполнена."),
    INVALID_MODEL_OUTPUT=(503, "Энкодер вернул некорректные векторы."),
)


@app.get(
    "/health",
    response_model=ModelHealth,
    operation_id="getEncoderHealth",
    tags=["Состояние"],
    summary="Проверить HTTP-процесс",
)
def health():
    """Используется Docker Compose и Spring Boot для проверки связи с FastAPI."""
    return {"status": "up"}


@app.get(
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
                    "example": {"ready": True, "profile_id": "example-e5-profile", "dimension": 384}
                }
            },
        },
        503: error_doc("Профиль ещё недоступен", MODEL_NOT_READY=(503, "Модель ещё не прогрета.")),
    },
)
def readiness():
    """При импорте Java сохраняет profile_id в манифест снимка. Профиль фиксирует модель,
    SHA весов, токенизацию, префиксы, pooling, размерность и нормализацию."""
    return problem(503, "MODEL_NOT_READY", "Энкодер недоступен", "Модель ещё не подключена.")


@app.post(
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
                                "profile_id": "example-e5-profile",
                                "dimension": 384,
                                "vectors": [[1.0] + [0.0] * 383],
                            },
                        }
                    }
                }
            },
        },
        409: error_doc(
            "Профили различаются",
            PROFILE_MISMATCH=(409, "Профиль запроса отличается от активного."),
        ),
        422: error_doc(
            "Ошибка входных данных",
            INVALID_REQUEST=(422, "Для query нужен ровно один непустой текст."),
        ),
        503: UNAVAILABLE,
    },
)
def encode_texts(
    body: Annotated[
        QueryBatch | PassageBatch,
        Body(
            discriminator="input_type",
            openapi_examples={
                "query": {
                    "summary": "Один запрос витрины",
                    "value": {
                        "input_type": "query",
                        "profile_id": "example-e5-profile",
                        "texts": ["Как восстановить файлы после сбоя?"],
                    },
                },
                "passage": {
                    "summary": "Пакет при импорте",
                    "value": {
                        "input_type": "passage",
                        "profile_id": "example-e5-profile",
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
    return problem(503, "MODEL_NOT_READY", "Энкодер недоступен", "Модель ещё не подключена.")
