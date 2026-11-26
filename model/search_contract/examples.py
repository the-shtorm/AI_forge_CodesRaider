"""Примеры контракта. Товары и цены взяты из снимка от 25.11.2026."""

SNAPSHOT = "2026-11-25T14:12"
SESSION = "demo-session-1"
SEARCH = "example-search-1"
STORE = "https://store.softline.ru"


def example(summary, value):
    return {"summary": summary, "value": value}


def offer(offer_id, name, sku, vendor, price_minor, path):
    return {
        "offer_id": offer_id,
        "name": name,
        "sku": sku,
        "vendor": vendor,
        "price_minor": price_minor,
        "currency": "RUB",
        "available": True,
        "offer_tier": "primary",
        "url": STORE + path,
    }


def direct(item, family, reason):
    return {**item, "family_key": family, "role": "direct", "reason_codes": [reason]}


def consumable(item, device, source):
    return {
        **item,
        "family_key": "hardware/pantum/" + item["sku"].lower(),
        "role": "complement",
        "reason_codes": ["COMPATIBLE_CONSUMABLE"],
        "anchor_offer_id": device["offer_id"],
        "checks": [
            {
                "field": "supported_device",
                "anchor_value": device["sku"],
                "candidate_value": item["sku"],
                "source_url": source,
                "result": "pass",
            }
        ],
    }


OFFICE = offer(
    "473582",
    "Microsoft Office для дома и бизнеса 2024",
    "EP2-06605",
    "Программы и лицензии Microsoft Corporation",
    3658400,
    "/microsoft/ep2-06605-473582/",
)
PRINTER = offer("363882", "Pantum P2500W", "P2500W", "Pantum", 1036800, "/pantum/p2500w-363882/")
MFP = offer("521379", "Pantum BM1800W", "BM1800W", "Pantum", 1449100, "/pantum/bm1800w-521379/")
PC211 = offer(
    "526683", "Картридж Pantum PC-211P", "PC-211P", "Pantum", 397500, "/pantum/pc-211p-526683/"
)
TL2318 = offer(
    "513625",
    "Картридж Pantum TL-C2318H",
    "TL-C2318H",
    "Pantum",
    345600,
    "/pantum/tl-c2318h-513625/",
)

MTS = [
    direct(
        offer("535950", "МТС Линк Optimum 30", None, "МТС Линк", 662000, "/webinar/-535950/"),
        "family/414326",
        "VENDOR_MATCH",
    ),
    direct(
        offer("541589", "МТС Линк Стандарт", None, "МТС Линк", 360000, "/webinar/-541589/"),
        "family/447242",
        "VENDOR_MATCH",
    ),
    direct(
        offer("541591", "МТС Линк Про", None, "МТС Линк", 450000, "/webinar/-541591/"),
        "family/447243",
        "VENDOR_MATCH",
    ),
]

PANTUM_Q = "Покажи устройства Pantum P2500W или BM1800W"
SEARCH_REQUESTS = {
    "sku": example("Точный артикул", {"q": "EP2-06605", "session_id": SESSION}),
    "selected": example(
        "Выбор подсказки по префиксу",
        {"q": "EP2-0", "selected_target": {"kind": "offer", "target_id": "473582"}},
    ),
    "context": example(
        "Неоднозначный запрос с B2B-контекстом",
        {"q": "сервер", "context": {"segment": "b2b", "profile_id": "hardware-buyer"}},
    ),
    "license": example(
        "Ограничения одной лицензии",
        {
            "q": "КриптоПро CSP",
            "filters": {
                "version": "5.0",
                "license_type": "renewal",
                "device_count": 5,
                "term_months": 12,
                "available_only": True,
            },
        },
    ),
    "task": example("Поиск по задаче", {"q": "Как восстановить файлы после сбоя?", "limit": 10}),
    "pantum": example("Два устройства и два дополнения", {"q": PANTUM_Q}),
    "mts": example("Навигация к вендору", {"q": "мтс"}),
    "absent": example("Пустая выдача", {"q": "несуществующая-модель-000"}),
}


def result(q, cards, direct_count, reason, target=None):
    return {
        "search_id": SEARCH,
        "index_version": SNAPSHOT,
        "mode": "lexical",
        "normalized_q": q,
        "applied_filters": {},
        "primary_target": target,
        "direct_count": direct_count,
        "top4": cards,
        "more_results": [],
        "alternatives": [],
        "shortfall_reason": reason,
    }


SEARCH_RESULTS = {
    "sku": example(
        "Office, цена 36 584 ₽, один прямой SKU",
        result(
            "ep2-06605",
            [direct(OFFICE, "family/391338", "EXACT_SKU")],
            1,
            "NOT_ENOUGH_VERIFIED_FAMILIES",
            {"kind": "offer", "id": OFFICE["offer_id"], "url": OFFICE["url"]},
        ),
    ),
    "pantum": example(
        "Полная четвёрка с проверяемыми допродажами",
        result(
            PANTUM_Q.lower(),
            [
                direct(PRINTER, "hardware/pantum/p2500w", "MODEL_MATCH"),
                direct(MFP, "hardware/pantum/bm1800w", "MODEL_MATCH"),
                consumable(
                    PC211,
                    PRINTER,
                    "https://www.pantum.ru/products/laser-devices/monohromnyj-printer-p2500w/",
                ),
                consumable(
                    TL2318,
                    MFP,
                    "https://www.pantum.ru/options-and-supplies/toner-kartridzh-tl-c2318h/",
                ),
            ],
            2,
            None,
        ),
    ),
    "mts": example(
        "Три прямых семейства и отдельный переход",
        result(
            "мтс",
            MTS,
            3,
            "NOT_ENOUGH_VERIFIED_FAMILIES",
            {"kind": "vendor", "id": "webinar", "url": STORE + "/webinar/"},
        ),
    ),
    "absent": example(
        "Совпадений нет", result("несуществующая-модель-000", [], 0, "NO_DIRECT_MATCH")
    ),
}

SUGGESTIONS = [
    {"kind": "offer", "target_id": "473582", "label": "Microsoft Office 2024 · EP2-06605"}
]
ENTITY = {
    "kind": "family",
    "id": "391338",
    "name": OFFICE["name"],
    "url": STORE + "/microsoft/microsoft-office-dlya-doma-i-biznesa-2024/",
    "offers": [OFFICE],
    "children": [],
}
RESERVE = {
    "kind": "offer",
    "id": "504",
    "name": "Adobe InCopy CC",
    "url": STORE + "/adobe/65272676ba01a12-504/",
    "offers": [
        {
            "offer_id": "504",
            "sku": None,
            "name": "Adobe InCopy CC",
            "vendor": "Adobe Systems",
            "url": STORE + "/adobe/65272676ba01a12-504/",
            "available": False,
            "price_minor": None,
            "currency": "RUB",
            "offer_tier": "reserve",
        }
    ],
    "children": [],
}

EVENTS = [
    {
        "type": "click",
        "event_id": "evt-click-1",
        "session_id": SESSION,
        "ts": "2026-11-26T10:05:00+03:00",
        "search_id": SEARCH,
        "offer_id": "473582",
        "family_key": "family/391338",
        "role": "direct",
        "block": "top4",
        "position": 1,
    },
    {
        "type": "order",
        "event_id": "evt-order-line-1",
        "session_id": SESSION,
        "ts": "2026-11-26T10:12:00+03:00",
        "offer_id": "473582",
    },
]

NAVIGATION_EVENTS = [
    {
        "type": "click",
        "event_id": "evt-nav-1",
        "session_id": SESSION,
        "ts": "2026-11-26T10:15:00+03:00",
        "search_id": "search-mts",
        "block": "navigation",
        "target_kind": "vendor",
        "target_id": "webinar",
    },
    {
        "type": "reformulation",
        "event_id": "evt-edit-1",
        "session_id": SESSION,
        "ts": "2026-11-26T10:16:00+03:00",
        "search_id": "search-mts",
        "next_search_id": "search-mts-standard",
    },
]

ANALYTICS = {
    "period": {
        "from": "2026-11-26T00:00:00+03:00",
        "to": "2026-11-27T00:00:00+03:00",
        "source": "seed",
    },
    "zero_result_rate": {"numerator": 2, "denominator": 10, "value": 0.2},
    "abandonment_rate": {"numerator": 1, "denominator": 2, "value": 0.5},
    "conversion_rate": {"numerator": 1, "denominator": 5, "value": 0.2},
    "failures": [
        {
            "reason": "missing_synonym",
            "count": 1,
            "query_examples": ["мдаемон"],
            "action": "Проверить написание в каталоге и добавить подтверждённый синоним.",
        }
    ],
}
