"""Интерактивная спецификация публичного API. Бизнес-обработчики принадлежат Spring Boot."""

from typing import Annotated

from fastapi import Body, Path, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import RedirectResponse
from pydantic import Field

from app import app as model_app
from contract_http import DocumentedAPI, Text, error_doc, problem, validation_handler
from search_contract import examples as ex
from search_contract.schemas import (
    AnalyticsPeriod,
    AnalyticsResponse,
    CatalogPage,
    CatalogQuery,
    Entity,
    ExplainRequest,
    Explanation,
    EventBatch,
    Filters,
    Kind,
    PublicHealth,
    SearchRequest,
    SearchResponse,
    Suggestion,
)

DEFAULT_ERROR = error_doc("Другие ошибки используют тот же формат Problem Details.")
INVALID = error_doc(
    "Ошибка входных данных", INVALID_REQUEST=(400, "Проверьте типы полей и ограничения запроса.")
)
NO_CATALOG = error_doc(
    "Каталог недоступен", CATALOG_UNAVAILABLE=(503, "Активный снимок каталога ещё не опубликован.")
)
PENDING = error_doc(
    "Справочный стенд",
    CONTRACT_ONLY=(501, "Формат запроса принят. Обработчик реализуется в Spring Boot."),
)

app = DocumentedAPI(
    title="Умный поиск Softline · публичный API",
    version="1.0.0",
    description=(
        "Контракт витрины, поиска и аналитики для реализации в Spring Boot. Разверните операцию, "
        "чтобы посмотреть схему и именованные примеры в **Responses**. На этом справочном стенде "
        "**Try it out** проверяет входные данные. Корректный вызов возвращает **501 CONTRACT_ONLY**, "
        "ошибка формата — **400 INVALID_REQUEST**. Живой публичный сервис принимает запросы на порту 8080.\n\n"
        "[Swagger энкодера](/model/docs) · [ReDoc поиска](/redoc)"
    ),
    openapi_tags=[
        {"name": "Поиск", "description": "Отбор семейств, ранжирование и подсказки."},
        {"name": "Каталог", "description": "Навигация и варианты конкретного товара."},
        {"name": "Аналитика", "description": "Доставка событий и агрегаты по сессиям."},
        {"name": "Состояние", "description": "Доступность публичного сервиса и его зависимостей."},
    ],
    responses={"default": DEFAULT_ERROR, 501: PENDING},
)
app.add_exception_handler(RequestValidationError, validation_handler(400))


def pending():
    return problem(
        501,
        "CONTRACT_ONLY",
        "Справочный стенд",
        "Формат запроса принят. Обработчик реализуется в Spring Boot.",
    )


def success(description, examples):
    return {"description": description, "content": {"application/json": {"examples": examples}}}


@app.post(
    "/v1/search",
    operation_id="searchProducts",
    tags=["Поиск"],
    summary="Найти товары",
    response_model=SearchResponse,
    responses={
        200: success("Список в порядке показа, включая пустой результат.", ex.SEARCH_RESULTS),
        400: error_doc(
            "Некорректный запрос или цель подсказки",
            INVALID_REQUEST=(400, "Поле q должно содержать текст."),
            UNKNOWN_TARGET=(400, "Выбранная цель отсутствует в текущем снимке."),
        ),
        503: error_doc(
            "Хранилище временно недоступно",
            CATALOG_UNAVAILABLE=(503, "Активный снимок каталога недоступен."),
            JOURNAL_UNAVAILABLE=(503, "Не удалось записать поиск в журнал."),
        ),
    },
)
def search_products(body: Annotated[SearchRequest, Body(openapi_examples=ex.SEARCH_REQUESTS)]):
    """Точный артикул и явные фильтры имеют приоритет над контекстом. Все ограничения лицензии должны
    совпасть у одного SKU. В top4 каждое семейство занимает одно место. Дополнение содержит ID опорного
    предложения и положительные проверки. В upsell возвращаются совместимые дополнения и предложения
    с объяснением подбора. Блок содержит до восьми карточек. Один и тот же расходник
    может находиться в top4 и upsell. Клиент показывает его один раз и использует позицию из upsell.
    Похожие предложения берутся из категории прямого товара или проверенного аналога.
    Для ввода вроде «???» подборка curated_fallback сохраняет direct_count=0.

    Отсутствие совпадений даёт 200 и пустой top4. При недоступности модели поиск переходит в lexical.
    Сервер регистрирует отправленный поиск один раз и возвращает search_id."""
    return pending()


@app.post("/v1/explain", operation_id="explainCard", tags=["Поиск"], response_model=Explanation,
          responses={400: INVALID, 404: error_doc("Карточка отсутствует в этой выдаче")})
def explain_card(body: ExplainRequest):
    """Вернуть пояснение по сохранённым фактам конкретной показанной карточки."""
    return pending()


@app.get(
    "/v1/suggest",
    operation_id="suggestTargets",
    tags=["Поиск"],
    summary="Дополнить поисковую строку",
    response_model=Annotated[list[Suggestion], Field(max_length=6)],
    responses={
        200: success(
            "До шести подсказок, пустой массив при отсутствии вариантов.",
            {
                "offer": ex.example("Префикс EP2-0", ex.SUGGESTIONS),
                "backup": ex.example("Префикс кибер бэ", [ex.BACKUP_SUGGESTION]),
            },
        ),
        400: INVALID,
        503: NO_CATALOG,
    },
)
def suggest_targets(
    q: Annotated[Text, Query(description="Непустой префикс, например EP2-0.")],
    category_id: Annotated[
        Text | None, Query(description="Ограничить подсказки текущим разделом.")
    ] = None,
    profile_id: Annotated[
        Text | None,
        Query(description="hardware_buyer — закупщик оборудования, software_admin — администратор ПО. Без известного профиля используется обычный порядок."),
    ] = None,
):
    """Выбор мышью или Enter передаёт kind и target_id в selected_target обычного поиска.
    Клиент сохраняет исходный q. Вызов подсказок не создаёт отправленный поиск."""
    return pending()


@app.get(
    "/v1/catalog",
    operation_id="browseCatalog",
    tags=["Каталог"],
    summary="Получить страницу каталога",
    response_model=CatalogPage,
    responses={400: INVALID, 503: NO_CATALOG},
)
def browse_catalog(query: Annotated[CatalogQuery, Query()]):
    """Страницы семейств и отдельных предложений с фасетами по фильтрам активного снимка."""
    return pending()


@app.get(
    "/v1/entities/{kind}/{id}",
    operation_id="getEntity",
    tags=["Каталог"],
    summary="Открыть сущность каталога",
    response_model=Entity,
    responses={
        200: success(
            "Карточка, отфильтрованные SKU и дочерние переходы.",
            {
                "family": ex.example("Семейство Office", ex.ENTITY),
                "backup": ex.example("Подборка Кибер Бэкап", ex.BACKUP_ENTITY),
                "reserve": ex.example("Резерв без цены", ex.RESERVE),
            },
        ),
        400: INVALID,
        404: error_doc(
            "Сущность отсутствует",
            ENTITY_NOT_FOUND=(404, "В снимке нет сущности с таким типом и ID."),
        ),
        503: NO_CATALOG,
    },
)
def get_entity(
    kind: Kind,
    id: Annotated[Text, Path(description="Строковый ID сущности выбранного kind.")],
    filters: Annotated[Filters, Query()],
):
    """Повторно применяет фильтры поиска, переданные отдельными query-параметрами.
    Известная сущность с пустым списком удовлетворяющих фильтрам вариантов даёт 200."""
    return pending()


@app.post(
    "/v1/events",
    operation_id="recordEvents",
    tags=["Аналитика"],
    summary="Записать события сессии",
    status_code=204,
    responses={
        204: {"description": "Пакет зафиксирован, тело ответа пустое."},
        400: INVALID,
        503: error_doc(
            "Журнал недоступен", EVENT_STORE_UNAVAILABLE=(503, "Запись временно недоступна.")
        ),
    },
)
def record_events(
    events: Annotated[
        EventBatch,
        Body(
            openapi_examples={
                "order": ex.example("Клик и покупка того же SKU", ex.EVENTS),
                "navigation": ex.example(
                    "Клик по вендору и переформулировка", ex.NAVIGATION_EVENTS
                ),
            }
        ),
    ],
):
    """Весь пакет проверяется и записывается в одной транзакции. При повторной доставке event_id
    сохраняется первая запись. Сервер создаёт событие search самостоятельно. Для товарного клика,
    корзины и позиции заказа передавайте фактический offer_id. У навигации передавайте тип и ID цели.

    Каждая купленная позиция получает отдельный event_id. Сервер привязывает её к последнему предшествующему
    клику по тому же offer_id в той же сессии. Заказ без такого клика сохраняется с search_id=null."""
    return pending()


@app.get(
    "/v1/analytics",
    operation_id="getSearchAnalytics",
    tags=["Аналитика"],
    summary="Получить показатели поиска",
    response_model=AnalyticsResponse,
    responses={
        200: success(
            "Агрегаты, числители, знаменатели и причины неудач.",
            {"seed": ex.example("Синтетический период", ex.ANALYTICS)},
        ),
        400: INVALID,
        503: error_doc(
            "Журнал недоступен", EVENT_STORE_UNAVAILABLE=(503, "Статистика временно недоступна.")
        ),
    },
)
def get_search_analytics(period: Annotated[AnalyticsPeriod, Query()]):
    """Период [from, to) задаётся временем RFC 3339 с часовым поясом.
    Нулевая выдача означает direct_count=0. Уход учитывается для завершённой неуспешной сессии после
    30 минут бездействия. В конверсии сессия с атрибутированным заказом учитывается один раз.
    При нулевом знаменателе value=null."""
    return pending()


@app.get(
    "/health",
    operation_id="getSearchHealth",
    tags=["Состояние"],
    summary="Получить состояние Spring Boot",
    response_model=PublicHealth,
    responses={
        200: success(
            "Доступность HTTP-приложения.",
            {
                "bootstrap": ex.example(
                    "Существующий ответ Spring Boot",
                    {"status": "up", "model_service": "unavailable"},
                ),
                "indexed": ex.example(
                    "После подключения каталога",
                    {
                        "status": "up",
                        "model_service": "up",
                        "index_version": ex.SNAPSHOT,
                        "search_ready": True,
                        "semantic_ready": True,
                    },
                ),
            },
        )
    },
)
def get_search_health():
    """На рабочем Spring Boot этот маршрут уже доступен. На справочном стенде показывается его контракт.
    Поля состояния индекса и семантики появятся при подключении этих компонентов."""
    return pending()


@app.get("/", include_in_schema=False)
def index(request: Request):
    return RedirectResponse(request.scope.get("root_path", "") + "/docs")


app.mount("/model", model_app, name="encoder")
