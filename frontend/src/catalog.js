import demoImages from "./demo-product-images.json" with { type: "json" };

const store = "https://store.softline.ru";
const categories = {
  print: "Принтеры и МФУ",
  supplies: "Расходные материалы",
  servers: "Серверы",
  software: "Серверное ПО",
  office: "Офисные программы",
  meetings: "Видеосвязь",
};

function product(offer_id, sku, name, vendor, category_id, price_minor, path, attributes = {}) {
  return {
    offer_id, sku, name, vendor, category_id, price_minor, attributes,
    vendor_id: vendor.toLowerCase(), url: store + path, currency: "RUB",
    image_url: demoImages[offer_id] ?? null,
    family_key: `demo/${offer_id}`, available: true, offer_tier: "primary", role: "direct",
  };
}

const products = [
  product("363882", "P2500W", "Pantum P2500W", "Pantum", "print", 1036800, "/pantum/p2500w-363882/", { Тип: ["Лазерный принтер"], Подключение: ["Wi-Fi"] }),
  product("521379", "BM1800W", "Pantum BM1800W", "Pantum", "print", 1449100, "/pantum/bm1800w-521379/", { Тип: ["Лазерное МФУ"] }),
  product("526683", "PC-211P", "Картридж Pantum PC-211P", "Pantum", "supplies", 397500, "/pantum/pc-211p-526683/"),
  product("513625", "TL-C2318H", "Картридж Pantum TL-C2318H", "Pantum", "supplies", 345600, "/pantum/tl-c2318h-513625/"),
  product("438239", "3470S2A-1", "DEPO Storm 3470S2A", "DEPO", "servers", 28990000, "/depo/3470s2a-1-438239/"),
  product("467607", "3470E2A-6", "DEPO Storm 3470E2A", "DEPO", "servers", 31990000, "/depo/3470e2a-6-467607/"),
  product("467610", "3470Z1A-4", "DEPO Storm 3470Z1A", "DEPO", "servers", 34990000, "/depo/3470z1a-4-467610/"),
  product("482054", "SYS-621P-TRT", "Supermicro SuperServer 621P-TRT", "Supermicro", "servers", 42990000, "/supermicro/sys-621p-trt-482054/"),
  product("487935", "OS2001X8618BOXSKTSR01-PO12", "Astra Linux Special Edition 1.8", "Группа Астра", "software", 510000, "/astralinux/os2001x8618boxsktsr01-po12-487935/", { Версия: ["1.8"], Платформа: ["Linux"] }),
  product("418697", "PS16NL", "Кибер Бэкап · серверная лицензия", "Киберпротект", "software", 899900, "/cyberprotect/ps16nl-418697/", { Назначение: ["Резервное копирование"] }),
  product("473582", "EP2-06605", "Microsoft Office для дома и бизнеса 2024", "Microsoft", "office", 3658400, "/microsoft/ep2-06605-473582/", { Платформа: ["Windows"], Лицензия: ["Бессрочная"] }),
  product("535950", null, "МТС Линк Optimum 30", "МТС Линк", "meetings", 662000, "/webinar/-535950/"),
  product("541589", null, "МТС Линк Стандарт", "МТС Линк", "meetings", 360000, "/webinar/-541589/"),
  product("541591", null, "МТС Линк Про", "МТС Линк", "meetings", 450000, "/webinar/-541591/"),
  { ...product("504", null, "Adobe InCopy CC", "Adobe", "office", null, "/adobe/65272676ba01a12-504/"), available: false, offer_tier: "reserve" },
];

const compatibility = {
  "363882": ["526683", "https://www.pantum.ru/products/laser-devices/monohromnyj-printer-p2500w/"],
  "521379": ["513625", "https://www.pantum.ru/options-and-supplies/toner-kartridzh-tl-c2318h/"],
};

function normalize(text) {
  return text.trim().toLowerCase().replaceAll("ё", "е");
}

function matchesFilters(item, filters, except) {
  return (except === "category_id" || !filters.category_id || item.category_id === filters.category_id)
    && (except === "vendor_id" || !filters.vendor_id || item.vendor_id === filters.vendor_id)
    && (!filters.available_only || item.available)
    && (filters.max_price_minor == null || (item.price_minor != null && item.price_minor <= filters.max_price_minor))
    && (!filters.version || item.attributes.Версия?.some(value => normalize(value) === normalize(filters.version)))
    && (!filters.platform || item.attributes.Платформа?.some(value => normalize(value) === normalize(filters.platform)))
    && (!filters.license_type || item.attributes["Тип лицензии"]?.includes(filters.license_type))
    && (!filters.term_months || Number(item.attributes["Срок, месяцев"]?.[0]) === filters.term_months)
    && (!filters.device_count || Number(item.attributes["Количество устройств"]?.[0]) >= filters.device_count);
}

function options(items, key, label) {
  const counts = new Map();
  for (const item of items) {
    const id = item[key];
    const facet = counts.get(id) ?? { id, label: label(item), count: 0 };
    facet.count += 1;
    counts.set(id, facet);
  }
  return [...counts.values()].sort((a, b) => b.count - a.count || a.label.localeCompare(b.label, "ru"));
}

export function demoSearch({ q, filters = {}, selected_target, sort = "relevance", limit = 4 }) {
  const query = normalize(q);
  const exact = products.find(item => (selected_target?.kind === "offer" && item.offer_id === selected_target.target_id)
    || (item.sku && normalize(item.sku) === query));
  const targetVendor = query === "мтс" || query === "мтс линк" || selected_target?.target_id === "мтс линк";
  let candidates = products.filter(item => {
    if (exact) return item === exact;
    if (targetVendor) return item.vendor === "МТС Линк";
    if (selected_target?.kind === "vendor") return item.vendor_id === selected_target.target_id;
    if (selected_target?.kind === "category") return item.category_id === selected_target.target_id;
    if (/по для сервера|почтовый сервер|серверное по/.test(query)) return /почтовый/.test(query) ? false : item.category_id === "software";
    if (/^сервер(ы)?$/.test(query)) return item.category_id === "servers";
    if (item.category_id === "supplies" && /p2500w|bm1800w/.test(query) && !/картридж|pc-211p|tl-c2318h/.test(query)) return false;
    const haystack = normalize([item.name, item.sku, item.vendor, categories[item.category_id], ...Object.values(item.attributes).flat()].filter(Boolean).join(" "));
    const words = query.split(/[\s,?]+/).filter(word => word.length > 2);
    return words.length > 0 && (haystack.includes(query) || words.some(word => haystack.includes(word)));
  });
  const facets = {
    vendors: options(candidates.filter(item => matchesFilters(item, filters, "vendor_id")), "vendor_id", item => item.vendor),
    categories: options(candidates.filter(item => matchesFilters(item, filters, "category_id")), "category_id", item => categories[item.category_id]),
  };
  candidates = candidates.filter(item => matchesFilters(item, filters));
  if (sort !== "relevance" && !exact) {
    const direction = sort === "price_asc" ? 1 : -1;
    candidates.sort((left, right) => {
      if (left.price_minor == null || right.price_minor == null) return Number(left.price_minor == null) - Number(right.price_minor == null);
      return direction * (left.price_minor - right.price_minor);
    });
  }
  const direct = candidates.slice(0, 4);
  const complements = direct.flatMap(anchor => {
    const relation = compatibility[anchor.offer_id];
    const item = relation && products.find(candidate => candidate.offer_id === relation[0]);
    if (!item || direct.includes(item) || !matchesFilters(item, filters)) return [];
    return [{ ...item, role: "complement", anchor_offer_id: anchor.offer_id,
      checks: [{ field: "supported_device", anchor_value: anchor.sku, candidate_value: item.sku, source_url: relation[1], result: "pass" }],
    }];
  }).slice(0, 4 - direct.length);
  const top4 = [...direct, ...complements];
  return {
    search_id: `demo-${Date.now()}`, normalized_q: query, applied_filters: filters, mode: "lexical",
    direct_count: candidates.length, top4, more_results: candidates.slice(4, limit), alternatives: [], facets,
    primary_target: targetVendor ? { kind: "vendor", id: "webinar", label: "МТС Линк", url: `${store}/webinar/` }
      : exact && direct.length ? { kind: "offer", id: exact.offer_id, label: exact.name, url: exact.url } : null,
    shortfall_reason: top4.length === 4 ? null : direct.length ? "NOT_ENOUGH_VERIFIED_FAMILIES" : "NO_DIRECT_MATCH",
    llm_applied: false,
  };
}

export function demoSuggestions(q) {
  const prefix = normalize(q);
  return products.filter(item => normalize(item.name).startsWith(prefix) || normalize(item.sku ?? "").startsWith(prefix))
    .slice(0, 6).map(item => ({ kind: "offer", target_id: item.offer_id, label: item.sku ? `${item.name} · ${item.sku}` : item.name }));
}

export function safeLink(url) {
  return typeof url === "string" && (/^https?:\/\//i.test(url) || (url.startsWith("/") && !url.startsWith("//") && !url.includes("\\"))) ? url : undefined;
}

export async function fetchSearch(path, options) {
  const response = await fetch(path, options);
  if (!response.ok) {
    if (response.status === 404 || response.status === 501 || response.status === 503) throw new Error("Сервис поиска пока недоступен.");
    const problem = await response.json().catch(() => null);
    throw new Error(problem?.detail ?? "Не удалось выполнить поиск. Попробуйте ещё раз.");
  }
  return response.json();
}
