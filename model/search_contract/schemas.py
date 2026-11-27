from typing import Annotated, Literal

from pydantic import AwareDatetime, Field, NonNegativeInt, PositiveInt, model_validator

from contract_http import DTO, Text

Kind = Literal["offer", "family", "vendor", "category", "collection"]
Role = Literal["direct", "complement", "alternative", "related"]
Source = Literal["demo", "seed"]
License = Literal["new", "renewal", "upgrade"]
Currency = Annotated[str, Field(pattern="^[A-Z]{3}$")]
Link = Annotated[
    str,
    Field(
        pattern=r"^(https?://|/)", description="Абсолютный HTTP(S) URL или путь от корня витрины."
    ),
]


class SelectedTarget(DTO):
    kind: Kind
    target_id: Text = Field(
        description="Строковый ID из ответа /v1/suggest, в пространстве выбранного kind."
    )


class Suggestion(SelectedTarget):
    label: Text


class Context(DTO):
    category_id: Text | None = Field(
        None, description="Текущий раздел витрины. Помогает разрешать неоднозначность."
    )
    segment: Text | None = Field(None, description="Сегмент покупателя, например b2b.")
    profile_id: Text | None = Field(
        None,
        description="Демонстрационный профиль hardware_buyer или software_admin. Для нового или отсутствующего ID действует обычный порядок.",
    )


class Filters(DTO):
    """Явные ограничения применяются совместно к одному предложению."""

    category_id: Text | None = Field(None, description="Обязательный раздел каталога.")
    vendor_id: Text | None = Field(None, description="Ключ вендора из каталога.")
    platform: Text | None = Field(None, description="Требуемая ОС или платформа.")
    version: Text | None = Field(None, description="Версия продукта как строка, например 5.0.")
    license_type: License | None = Field(
        None, description="Покупка новой лицензии, продление или обновление."
    )
    term_months: PositiveInt | None = Field(None, description="Срок лицензии в месяцах.")
    device_count: PositiveInt | None = Field(
        None, description="Сколько устройств должна покрывать лицензия."
    )
    seat_count: PositiveInt | None = Field(
        None, description="Сколько рабочих мест должна покрывать лицензия."
    )
    available_only: bool = Field(
        False, description="При true исключить недоступные предложения, включая резерв."
    )
    max_price_minor: NonNegativeInt | None = Field(
        None, description="Бюджет всей запрошенной конфигурации в минимальных единицах валюты."
    )
    currency: Currency = Field(
        "RUB", description="Валюта бюджета по ISO 4217. Для рублей сумма дана в копейках."
    )


class AssistantTurn(DTO):
    role: Literal["user", "assistant"]
    text: Text = Field(max_length=2000)


class SearchRequest(DTO):
    q: Text = Field(
        description="Исходная строка покупателя, в том числе префикс при выборе подсказки."
    )
    session_id: Text | None = Field(
        None, description="Анонимный ID сессии для контекста и последующей атрибуции."
    )
    context: Context | None = None
    filters: Filters = Field(default_factory=Filters)
    selected_target: SelectedTarget | None = Field(
        None, description="Выбранная подсказка. Сервер проверяет её ID и тип в текущем снимке."
    )
    strategy: Literal["standard", "llm"] = Field(
        "standard", description="llm запрашивает интерпретацию намерения. Явные фильтры всегда обязательны."
    )
    assistant_history: list[AssistantTurn] | None = Field(
        None, max_length=8, description="Предыдущие сообщения чата от старых к новым. Текущий вопрос находится в q."
    )
    sort: Literal["relevance", "price_asc", "price_desc"] = Field(
        "relevance", description="Порядок прямых результатов. Точный SKU сохраняет приоритет, товары без цены идут в конце."
    )
    limit: int = Field(
        4,
        ge=4,
        le=50,
        description="Размер основного списка вместе с more_results. Первые четыре места всегда находятся в top4.",
    )


class Target(DTO):
    kind: Kind
    id: Text = Field(description="ID сущности в её пространстве kind.")
    url: Link


class Offer(DTO):
    offer_id: Text = Field(
        description="ID предложения из XML. Именно этот ID передаётся в товарных событиях."
    )
    sku: Text | None = Field(
        description="Исходный артикул vendorCode. Может отсутствовать или повторяться."
    )
    name: Text
    vendor: Text
    url: Link
    image_url: Link | None = Field(None, description="Фотография предложения из каталога, если доступна.")
    available: bool = Field(description="Флаг наличия из снимка.")
    price_minor: PositiveInt | None = Field(
        description="Цена выбранного предложения в минимальных единицах. Неизвестная или нулевая цена фида возвращается как null."
    )
    currency: Currency
    old_price_minor: PositiveInt | None = Field(
        None,
        description="Предыдущая цена. Признак акции требует old_price_minor > price_minor и наличия.",
    )
    offer_tier: Literal["primary", "reserve"] = Field(
        description="primary — доступен и имеет цену. reserve — подходящий товар второй очереди."
    )
    attributes: dict[str, list[str]] = Field(
        default_factory=dict,
        description="Подтверждённые свойства этого SKU. Повторяющиеся параметры сохраняются массивом.",
    )


class Card(Offer):
    family_key: Text = Field(
        description="Непрозрачный ключ группировки. Для ПО имеет вид family/{group_id}."
    )
    reason_codes: list[Text] = Field(
        min_length=1,
        description="Основания показа, например EXACT_SKU, VENDOR_MATCH или COMPATIBLE_CONSUMABLE.",
    )


class DirectCard(Card):
    role: Literal["direct"]


class CompatibilityCheck(DTO):
    field: Text = Field(description="Проверенная характеристика или правило совместимости.")
    anchor_value: Text = Field(description="Значение основного SKU.")
    candidate_value: Text = Field(description="Значение дополнительного SKU.")
    source_url: Link = Field(description="Страница производителя или карточка каталога с явным подтверждением совместимости.")
    result: Literal["pass"] = Field(
        description="Для совместимого дополнения все обязательные проверки должны пройти."
    )


class ComplementCard(Card):
    role: Literal["complement"]
    anchor_offer_id: Text = Field(description="offer_id прямой карточки из этой выдачи.")
    checks: list[CompatibilityCheck] = Field(min_length=1)


class RelatedCard(Card):
    role: Literal["related"]
    recommendation_kind: Literal["paired_category", "upgrade", "catalog_pick"]
    recommendation_reason: Text = Field(description="Основание предложения по сведениям каталога.")
    anchor_offer_id: Text | None = Field(None, description="ID товара, для которого подобрано предложение, если он есть.")


class AlternativeCard(Card):
    role: Literal["alternative"]
    differences: list[Text] = Field(
        min_length=1, description="Существенные отличия от исходного запроса."
    )


TopCard = Annotated[DirectCard | ComplementCard, Field(discriminator="role")]
UpsellCard = Annotated[ComplementCard | RelatedCard, Field(discriminator="role")]


class SearchFacet(DTO):
    id: Text
    label: Text
    count: NonNegativeInt = Field(description="Число подходящих прямых семейств.")


class SearchFacets(DTO):
    vendors: list[SearchFacet]
    categories: list[SearchFacet]


Purpose = Literal["WORDS", "BACKUP", "WEBINAR", "REMOTE_ACCESS", "ANTIVIRUS",
                  "SERVER", "SERVER_SOFTWARE", "PRINTER", "UNKNOWN"]


class SearchProgram(DTO):
    compiler: Literal["rules", "llm", "llm_fallback", "llm_abstained"]
    purpose: Purpose
    additional_purposes: list[Purpose] = Field(max_length=1)
    join: Literal["all", "any"]
    terms: str
    excluded_versions: list[str]
    excluded_licenses: list[License]


class ExplainRequest(DTO):
    search_id: Text
    offer_id: Text


class Explanation(DTO):
    offer_id: Text
    source: Literal["llm", "template"]
    text: Text
    evidence: list[Text] = Field(min_length=1)
    source_url: Link


class SearchResponse(DTO):
    program: SearchProgram | None = Field(None, description="Исполненная программа и исключения по SKU.")
    search_id: Text = Field(description="ID этого поиска, включая поиск с пустым результатом.")
    index_version: Text = Field(description="Версия снимка каталога и поискового индекса.")
    mode: Literal["lexical", "hybrid"] = Field(
        description="Фактически использованный режим. При отказе энкодера применяется lexical."
    )
    normalized_q: str
    applied_filters: Filters = Field(
        description="Ограничения, фактически применённые к SKU после разбора запроса."
    )
    primary_target: Target | None = Field(
        description="Переход к SKU, семейству, вендору, категории или подборке. Учитывается отдельно от товарных мест."
    )
    direct_count: NonNegativeInt = Field(
        description="Число различных прямых семейств после фильтров и выбора очереди цены, до обрезки по limit."
    )
    top4: list[TopCard] = Field(
        max_length=4,
        description="Порядок отображения. Сначала прямые семейства, затем подтверждённые дополнения. Все family_key различны.",
    )
    more_results: list[DirectCard] = Field(
        description="Дополнительные прямые семейства сверх первой четвёрки, до общего limit."
    )
    alternatives: list[AlternativeCard] = Field(
        description="Отдельный блок функциональных замен ниже основной выдачи."
    )
    upsell: list[UpsellCard] = Field(
        max_length=4,
        description="Отдельный блок предложений к покупке. Дополнение с ролью complement может также присутствовать в top4. Показывайте его один раз.",
    )
    shortfall_reason: Literal["NO_DIRECT_MATCH", "NOT_ENOUGH_VERIFIED_FAMILIES"] | None = Field(
        description="null при полной четвёрке. При нуле прямых совпадений — NO_DIRECT_MATCH, при недоборе проверенных семейств — NOT_ENOUGH_VERIFIED_FAMILIES."
    )
    facets: SearchFacets | None = Field(
        None, description="Варианты фильтров по прямым совпадениям. Для каждой группы учитывать все явные ограничения, кроме собственного фильтра."
    )
    llm_applied: bool | None = Field(
        None, description="true, когда интерпретация LLM участвовала в поиске, false при деградации."
    )
    assistant_message: Text | None = Field(
        None, max_length=2000, description="Ответ чата на основе найденных сведений или уточняющий вопрос."
    )


class Entity(DTO):
    kind: Kind
    id: Text
    name: Text
    url: Link
    offers: list[Offer] = Field(
        description="Для семейства — варианты лицензий после фильтрации, для offer — выбранный SKU."
    )
    children: list[Target] = Field(
        description="Для вендора, категории и collection — переходы внутри выбранной области."
    )


class EventBase(DTO):
    event_id: Text = Field(
        description="Стабильный ключ идемпотентности. При повторной доставке сохранить тот же ID."
    )
    session_id: Text
    ts: AwareDatetime = Field(description="RFC 3339 со смещением времени.")
    source: Source = Field(
        "demo",
        description="demo — действия на стенде, seed — подготовленные синтетические события.",
    )


class CardEvent(EventBase):
    search_id: Text
    offer_id: Text
    family_key: Text
    role: Role
    block: Literal["top4", "more_results", "alternatives", "upsell"]
    position: PositiveInt = Field(description="Позиция с 1 внутри указанного блока.")


class Impression(CardEvent):
    type: Literal["impression"]


class CardClick(CardEvent):
    type: Literal["click"]


class DetailClick(EventBase):
    type: Literal["click"]
    block: Literal["details"]
    search_id: Text
    offer_id: Text = Field(description="Фактически выбранный вариант из карточки семейства.")
    family_key: Text


class NavigationClick(EventBase):
    type: Literal["click"]
    block: Literal["navigation"]
    search_id: Text
    target_kind: Kind
    target_id: Text


class Reformulation(EventBase):
    type: Literal["reformulation"]
    search_id: Text = Field(description="Предыдущий отправленный поиск.")
    next_search_id: Text = Field(description="Поиск после переформулировки.")


class AddToCart(EventBase):
    type: Literal["add_to_cart"]
    offer_id: Text
    search_id: Text | None = None


class OrderLine(EventBase):
    type: Literal["order"]
    offer_id: Text = Field(
        description="Одна купленная позиция на событие. search_id вычисляет сервер."
    )


Event = (
    Impression | CardClick | DetailClick | NavigationClick | Reformulation | AddToCart | OrderLine
)
EventBatch = Annotated[list[Event], Field(min_length=1)]


class AnalyticsPeriod(DTO):
    start: AwareDatetime = Field(alias="from", description="Включительная нижняя граница периода.")
    end: AwareDatetime = Field(alias="to", description="Исключительная верхняя граница периода.")
    source: Source | None = Field(None, description="Фильтр происхождения событий.")
    index_version: Text | None = Field(
        None, description="Версия индекса, записанная вместе с поиском."
    )

    @model_validator(mode="after")
    def ordered(self):
        if self.end <= self.start:
            raise ValueError("Верхняя граница периода должна быть позже нижней.")
        return self


class Metric(DTO):
    numerator: NonNegativeInt
    denominator: NonNegativeInt
    value: Annotated[float, Field(ge=0, le=1)] | None = Field(
        description="Доля от 0 до 1. null при нулевом знаменателе."
    )


class FailureGroup(DTO):
    reason: Literal[
        "missing_synonym", "ranking", "catalog_gap", "unavailable", "filter_conflict", "unknown"
    ]
    count: NonNegativeInt
    query_examples: list[str]
    action: Text = Field(description="Предлагаемая проверка или действие менеджера.")


class AnalyticsResponse(DTO):
    period: AnalyticsPeriod
    zero_result_rate: Metric
    abandonment_rate: Metric
    conversion_rate: Metric
    failures: list[FailureGroup]


class PublicHealth(DTO):
    status: Literal["up"]
    model_service: Literal["up", "unavailable"]
    index_version: Text | None = Field(
        None, description="Версия активного каталога после подключения индекса."
    )
    search_ready: bool | None = Field(
        None, description="Готовность поиска после подключения каталога."
    )
    semantic_ready: bool | None = Field(
        None, description="Прогрев совместимого энкодера после подключения модели."
    )
