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


def card(item, family, kind="offer", target_id=None, count=1):
    return {**item, "family_key": family, "result_kind": kind, "target_kind": kind,
            "target_id": target_id or item["offer_id"], "offer_count": count}


def direct(item, family, reason):
    if reason == "EXACT_SKU" or family.startswith("offer/"):
        destination = card(item, family)
    else:
        family_id = family.split("/", 1)[1].replace("/", "~")
        destination = card(item, family, "family", family_id)
    return {**destination, "role": "direct", "reason_codes": [reason]}


def consumable(item, device, source):
    return {
        **card(item, "hardware/pantum/" + item["sku"].lower()),
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

CURATED = [
    {**card(item, key), "role": "related", "recommendation_kind": "curated_fallback",
     "recommendation_reason": "Предложение из подборки повседневных товаров и программ.",
     "reason_codes": ["CURATED_FALLBACK"]}
    for item, key in (
        (OFFICE, "family/391338"),
        (offer("231538", "Dr.Web Security Space", "LHW-BK-12M-1-A3", "Доктор Веб", 172400,
               "/drweb/lhw-bk-12m-1-a3-231538/"), "family/193420"),
        (offer("83303", "Клавиатура Logitech K120", "920-002522", "Logitech", 98500,
               "/logitech/920-002522-83303/"), "hardware/logitech/920-002522"),
        (offer("508887", "Флешка Netac U185 USB 3.2 Gen 1 64GB", "NT03U185N-064G-32WH", "Netac", 65700,
               "/netac/nt03u185n-064g-32wh-508887/"), "offer/508887"),
    )
]

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

BACKUP_ID = "cyberprotect-1033"
BACKUP_FILTERS = {"vendor_id": "cyberprotect", "category_id": "1033"}
BACKUP_TARGET = {
    "kind": "collection",
    "id": BACKUP_ID,
    "url": STORE + "/cyberprotect/?filter%5Bcategory%5D%5B%5D=1033",
}
BACKUP_SUGGESTION = {"kind": "collection", "target_id": BACKUP_ID, "label": "Кибер Бэкап"}
# Представители разных семейств из снимка. Порядок равнозначных редакций иллюстративный.
BACKUP_CARDS = [
    direct(offer(oid, name, sku, "Киберпротект", price, path), "family/" + group, "COLLECTION_MATCH")
    for oid, group, name, sku, price, path in (
        (
            "418697", "173614",
            "Киберпротект Кибер Бэкап Стандартная редакция (лицензия для коммерческих организаций), для физического сервера",
            "PSCNL", 7925000, "/cyberprotect/ps16nl-418697/",
        ),
        (
            "418698", "173615",
            "Киберпротект Кибер Бэкап Расширенная редакция (бессрочная лицензия для коммерческих организаций), для физического",
            "PACNL", 18716900, "/cyberprotect/pa16nl-418698/",
        ),
        (
            "536456", "442025",
            "Киберпротект Кибер Бэкап Облачный (лицензия), Лицензия для физического сервера",
            "SPEAM1", 117200, "/cyberprotect/speam1-536456/",
        ),
        (
            "539763", "444870",
            "Киберпротект Кибер Бэкап Старт (подписка на 1 год, включая услугу по технической поддержке), 1 ПК",
            "СBSS1PC0GB-1Y-SUB", 137000, "/cyberprotect/bss1pc0gb-1y-sub-539763/",
        ),
    )
]
BACKUP_ENTITY = {
    **BACKUP_TARGET,
    "name": "Кибер Бэкап",
    "offers": [],
    "children": [
        {"kind": "offer", "id": card["offer_id"], "url": card["url"]} for card in BACKUP_CARDS
    ],
}

PANTUM_Q = "Покажи устройства Pantum P2500W или BM1800W"
ANALOG_Q = "Нужен монохромный лазерный принтер A4 Pantum P2507"
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
    "backup": example("Поиск подборки по названию", {"q": "кибер бэкап"}),
    "selected_backup": example(
        "Выбор подборки по префиксу",
        {"q": "кибер бэ", "selected_target": {"kind": "collection", "target_id": BACKUP_ID}},
    ),
    "mts": example("Навигация к вендору", {"q": "мтс"}),
    "analog": example("Отсутствующая модель с известным назначением", {"q": ANALOG_Q}),
    "absent": example("Пустая выдача", {"q": "несуществующая-модель-000"}),
}


def result(q, cards, direct_count, reason, target=None, upsell=None):
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
        "upsell": [card for card in cards if card["role"] == "complement"] if upsell is None else upsell,
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
    "backup": example(
        "Четыре семейства вендора в категории 1033",
        {**result("кибер бэкап", BACKUP_CARDS, 4, None, BACKUP_TARGET), "applied_filters": BACKUP_FILTERS},
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
    "analog": example(
        "Искомая модель отсутствует в тестовом снимке, есть подходящий аналог",
        {
            **result(ANALOG_Q.lower(), [], 0, "NO_DIRECT_MATCH"),
            "alternatives": [{
                **card(PRINTER, "hardware/pantum/p2500w"),
                "role": "alternative",
                "reason_codes": ["FUNCTIONAL_ALTERNATIVE"],
                "differences": ["Другая модель принтера: P2500W вместо P2507."],
            }],
        },
    ),
    "absent": example(
        "Модель отсутствует, показана редакционная подборка",
        result("несуществующая-модель-000", [], 0, "NO_DIRECT_MATCH", upsell=CURATED),
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
