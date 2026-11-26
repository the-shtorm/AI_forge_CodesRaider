import demoImages from "./demo-product-images.json" with { type: "json" };
import { newId } from "./api.js";

const store = "https://store.softline.ru";
const indexVersion = "demo-2026-11-25";
const backup = { kind: "collection", id: "cyberprotect-1033", label: "Кибер Бэкап", url: store + "/cyberprotect/?filter%5Bcategory%5D%5B%5D=1033" };
const backupScope = { vendor_id: "cyberprotect", category_id: "1033" };
const categories = {
  print: "Принтеры и МФУ",
  supplies: "Расходные материалы",
  servers: "Серверы",
  software: "Серверное ПО",
  office: "Офисные программы",
  meetings: "Видеосвязь",
  "1033": "Резервное копирование",
};
const vendorIds = { "МТС Линк": "webinar", "Группа Астра": "astralinux", "Киберпротект": "cyberprotect" };
const groups = {
  "473582": "391338", "418697": "173614", "418698": "173615", "536456": "442025", "539763": "444870",
  "535950": "414326", "541589": "447242", "541591": "447243",
};
const license = { Платформа: ["Windows"], "Тип лицензии": ["new"], "Количество рабочих мест": ["1"] };

function product(offer_id, sku, name, vendor, category_id, price_minor, path, attributes = {}) {
  return {
    offer_id, sku, name, vendor, category_id, price_minor, attributes,
    vendor_id: vendorIds[vendor] ?? vendor.toLowerCase(), url: store + path, currency: "RUB",
    image_url: demoImages[offer_id] ?? (category_id === "1033" ? demoImages["418697"] : null),
    family_key: groups[offer_id] ? `family/${groups[offer_id]}` : `demo/${offer_id}`,
    available: true, offer_tier: "primary", role: "direct", reason_codes: ["CATALOG_MATCH"],
  };
}

const products = [
  product("363882", "P2500W", "Pantum P2500W", "Pantum", "print", 1036800, "/pantum/p2500w-363882/", { Тип: ["Лазерный принтер"], Подключение: ["Wi-Fi"] }),
  product("521379", "BM1800W", "Pantum BM1800W", "Pantum", "print", 1449100, "/pantum/bm1800w-521379/", { Тип: ["Лазерное МФУ"] }),
  product("526683", "PC-211P", "Картридж Pantum PC-211P", "Pantum", "supplies", 397500, "/pantum/pc-211p-526683/"),
  product("513625", "TL-C2318H", "Картридж Pantum TL-C2318H", "Pantum", "supplies", 345600, "/pantum/tl-c2318h-513625/"),
  { ...product("96022", "CS-LPA4100100", "Бумага Cactus A4", "Cactus", "supplies", 57000, "/cactus/cs-lpa4100100-96022/"), image_url: "/catalog/96022.jpg" },
  { ...product("533800", "CQUBD", "Кабель Vention USB-C — USB-B", "Vention", "supplies", 31100, "/vention/cqubd-533800/"), image_url: "/catalog/533800.png" },
  { ...product("526143", "600SPRO-16-1.8-USB-G", "Сетевой фильтр Buro 600SPro", "Buro", "supplies", 98500, "/buro/600spro-16-1-8-usb-g-526143/"), image_url: "/catalog/526143.png" },
  product("438239", "3470S2A-1", "DEPO Storm 3470S2A", "DEPO", "servers", 28990000, "/depo/3470s2a-1-438239/"),
  product("467607", "3470E2A-6", "DEPO Storm 3470E2A", "DEPO", "servers", 31990000, "/depo/3470e2a-6-467607/"),
  product("467610", "3470Z1A-4", "DEPO Storm 3470Z1A", "DEPO", "servers", 34990000, "/depo/3470z1a-4-467610/"),
  product("482054", "SYS-621P-TRT", "Supermicro SuperServer 621P-TRT", "Supermicro", "servers", 42990000, "/supermicro/sys-621p-trt-482054/"),
  product("487935", "OS2001X8618BOXSKTSR01-PO12", "Astra Linux Special Edition 1.8", "Группа Астра", "software", 510000, "/astralinux/os2001x8618boxsktsr01-po12-487935/", { Версия: ["1.8"], Платформа: ["Linux"] }),
  product("418697", "PSCNL", "Кибер Бэкап · серверная лицензия", "Киберпротект", "1033", 7925000, "/cyberprotect/ps16nl-418697/"),
  product("418698", "PACNL", "Кибер Бэкап · расширенная лицензия", "Киберпротект", "1033", 18716900, "/cyberprotect/pa16nl-418698/"),
  product("536456", "SPEAM1", "Кибер Бэкап · облачная лицензия", "Киберпротект", "1033", 117200, "/cyberprotect/speam1-536456/"),
  product("539763", "СBSS1PC0GB-1Y-SUB", "Кибер Бэкап · подписка", "Киберпротект", "1033", 137000, "/cyberprotect/bss1pc0gb-1y-sub-539763/", { "Срок, месяцев": ["12"], "Количество устройств": ["1"], "Тип лицензии": ["new"] }),
  product("473582", "EP2-06605", "Microsoft Office для дома и бизнеса 2024", "Microsoft", "office", 3658400, "/microsoft/ep2-06605-473582/", { ...license, Лицензия: ["Бессрочная"] }),
  product("535950", null, "МТС Линк Optimum 30", "МТС Линк", "meetings", 662000, "/webinar/-535950/"),
  product("541589", null, "МТС Линк Стандарт", "МТС Линк", "meetings", 360000, "/webinar/-541589/"),
  product("541591", null, "МТС Линк Про", "МТС Линк", "meetings", 450000, "/webinar/-541591/"),
  { ...product("504", null, "Adobe InCopy CC", "Adobe", "office", null, "/adobe/65272676ba01a12-504/"), available: false, offer_tier: "reserve" },
];

const compatibility = {
  "363882": ["526683", "https://www.pantum.ru/products/laser-devices/monohromnyj-printer-p2500w/"],
  "521379": ["513625", "https://www.pantum.ru/options-and-supplies/toner-kartridzh-tl-c2318h/"],
};

// Curated demo placements. These are independent of the verified compatibility rules.
const demoBundles = {
  "363882": ["526683", "96022", "533800", "526143"],
  "521379": ["513625", "96022", "533800", "526143"],
  "438239": ["487935", "418697", "418698", "536456"],
};

function demoRelated(anchors) {
  const bundle = anchors.map(item => demoBundles[item.offer_id]).find(Boolean);
  if (!bundle) return [];
  const displayed = new Set(anchors.map(item => item.offer_id));
  return bundle.filter(id => !displayed.has(id)).map(id => products.find(item => item.offer_id === id));
}

function normalize(text) {
  return text.trim().toLowerCase().replaceAll("ё", "е");
}

function matchesFilters(item, filters, except) {
  return (except === "category_id" || !filters.category_id || item.category_id === filters.category_id)
    && (except === "vendor_id" || !filters.vendor_id || item.vendor_id === filters.vendor_id)
    && (!filters.available_only || item.available)
    && (filters.max_price_minor == null || (item.currency === (filters.currency ?? "RUB") && item.price_minor != null && item.price_minor <= filters.max_price_minor))
    && (!filters.version || item.attributes.Версия?.some(value => normalize(value) === normalize(filters.version)))
    && (!filters.platform || item.attributes.Платформа?.some(value => normalize(value) === normalize(filters.platform)))
    && (!filters.license_type || item.attributes["Тип лицензии"]?.includes(filters.license_type))
    && (!filters.term_months || Number(item.attributes["Срок, месяцев"]?.[0]) === filters.term_months)
    && (!filters.device_count || Number(item.attributes["Количество устройств"]?.[0]) >= filters.device_count)
    && (!filters.seat_count || Number(item.attributes["Количество рабочих мест"]?.[0]) >= filters.seat_count);
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

function offerTarget(item) {
  return { kind: "offer", id: item.offer_id, label: item.name, url: item.url };
}

function familyTarget(item) {
  return {
    kind: "family", id: groups[item.offer_id], label: item.name,
    url: item.offer_id === "473582" ? store + "/microsoft/microsoft-office-dlya-doma-i-biznesa-2024/" : item.url,
  };
}

function lookup({ kind, id }) {
  if (kind === "collection" && id === backup.id) return { ...backup, matches: item => matchesFilters(item, backupScope) };
  if (kind === "category" && categories[id]) return { kind, id, label: categories[id], url: store, matches: item => item.category_id === id };
  if (kind === "family") {
    const representative = products.find(item => groups[item.offer_id] === id);
    if (representative) return { ...familyTarget(representative), matches: item => groups[item.offer_id] === id };
  }
  const item = products.find(candidate => kind === "vendor" ? candidate.vendor_id === id : kind === "offer" && candidate.offer_id === id);
  if (!item) return null;
  if (kind === "vendor") return { kind, id, label: item.vendor, url: `${store}/${id}/`, matches: candidate => candidate.vendor_id === id };
  return { ...offerTarget(item), matches: candidate => candidate === item };
}

export function demoEntity(target, filters) {
  const resolved = lookup(target);
  if (!resolved) throw new Error("Карточка отсутствует в текущем каталоге.");
  const { kind, id, label: name, url } = resolved;
  const matching = products.filter(item => resolved.matches(item) && matchesFilters(item, filters));
  const group = kind === "offer" ? groups[id] : null;
  const variants = group && matching.length ? [lookup({ kind: "family", id: group })] : [];
  return {
    kind, id, name, url,
    offers: kind === "offer" || kind === "family" ? matching : [],
    related_offers: kind === "offer" ? demoRelated(matching) : [],
    children: (kind === "offer" ? variants : matching.map(offerTarget)).map(({ kind: childKind, id: childId, label, url: childUrl }) =>
      ({ kind: childKind, id: childId, label, url: childUrl })),
  };
}

export function demoSearch({ q, filters = {}, selected_target, sort = "relevance", limit = 4 }) {
  const query = normalize(q);
  const exact = !selected_target && products.find(item => item.sku && normalize(item.sku) === query);
  const family = !selected_target && products.find(item => groups[item.offer_id] && normalize(item.name) === query);
  const inferred = exact ? offerTarget(exact) : family ? familyTarget(family) : ["мтс", "мтс линк"].includes(query)
    ? { kind: "vendor", id: "webinar" } : query === "кибер бэкап" ? backup : null;
  const primary = selected_target ? lookup({ kind: selected_target.kind, id: selected_target.target_id }) : inferred && lookup(inferred);
  if (selected_target && !primary) throw Object.assign(new Error("Эта подсказка устарела. Повторите поиск."), { code: "UNKNOWN_TARGET" });
  const analog = !primary && /\bp2507\b/.test(query);
  let candidates = products.filter(item => {
    if (primary) return primary.matches(item);
    if (analog || /почтовый сервер/.test(query)) return false;
    if (/по для сервера|серверное по/.test(query)) return ["software", "1033"].includes(item.category_id);
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
  if (sort !== "relevance" && primary?.kind !== "offer") {
    const direction = sort === "price_asc" ? 1 : -1;
    candidates.sort((left, right) => {
      if (left.price_minor == null || right.price_minor == null) return Number(left.price_minor == null) - Number(right.price_minor == null);
      return direction * (left.price_minor - right.price_minor);
    });
  }
  const direct = candidates.slice(0, 4).map(item => primary?.kind === "offer" ? { ...item, reason_codes: ["EXACT_SKU"] } : item);
  const complements = direct.flatMap(anchor => {
    const relation = compatibility[anchor.offer_id];
    const item = relation && products.find(candidate => candidate.offer_id === relation[0]);
    if (!item || direct.some(candidate => candidate.offer_id === item.offer_id) || !matchesFilters(item, filters)) return [];
    return [{ ...item, role: "complement", anchor_offer_id: anchor.offer_id,
      checks: [{ field: "supported_device", anchor_value: anchor.sku, candidate_value: item.sku, source_url: relation[1], result: "pass" }],
    }];
  }).slice(0, 4 - direct.length);
  const top4 = [...direct, ...complements];
  const alternatives = analog ? products.filter(item => item.offer_id === "363882" && matchesFilters(item, filters))
    .map(item => ({ ...item, role: "alternative", differences: ["Другая модель: Pantum P2500W вместо P2507"] })) : [];
  const { matches, ...primary_target } = primary ?? {};
  return {
    search_id: newId(), index_version: indexVersion, normalized_q: query, mode: "lexical",
    applied_filters: primary_target.id === backup.id ? { ...backupScope, ...filters } : filters,
    direct_count: candidates.length, top4, more_results: candidates.slice(4, limit), alternatives, facets,
    related_offers: demoRelated(direct),
    primary_target: primary ? primary_target : null,
    shortfall_reason: top4.length === 4 ? null : direct.length ? "NOT_ENOUGH_VERIFIED_FAMILIES" : "NO_DIRECT_MATCH",
    llm_applied: false,
  };
}

export function demoSuggestions(q, categoryId) {
  const prefix = normalize(q);
  const eligible = products.filter(item => !categoryId || item.category_id === categoryId);
  const targets = [
    ...((!categoryId || categoryId === backupScope.category_id) && normalize(backup.label).startsWith(prefix) ? [backup] : []),
    ...options(eligible, "vendor_id", item => item.vendor).filter(item => normalize(item.label).startsWith(prefix)).map(item => ({ ...item, kind: "vendor" })),
    ...options(eligible, "category_id", item => categories[item.category_id]).filter(item => normalize(item.label).startsWith(prefix)).map(item => ({ ...item, kind: "category" })),
    ...eligible.filter(item => normalize(item.name).startsWith(prefix) || normalize(item.sku ?? "").startsWith(prefix))
      .map(item => ({ ...offerTarget(item), label: item.sku ? `${item.name} · ${item.sku}` : item.name })),
  ];
  return targets.slice(0, 6).map(({ kind, id, label }) => ({ kind, target_id: id, label }));
}
