import { entityKinds, filterParams, filtersFromParams } from "./api";

export const taskQueries = [
  "astra linux", "Microsoft Office для дома и бизнеса", "kaspersky small office security",
  "крипто про csp 5.0", "anydesk", "кибер бэкап", "сканер-вс", "мтс", "max patrol", "365",
];
export const sorts = ["relevance", "price_asc", "price_desc"];
export const tabs = [["products", "Товары"], ["vendors", "Производители"], ["categories", "Категории"]];

export function readCatalogRoute(params, pathname) {
  const number = Number(params.get("page"));
  const q = (params.get("q") ?? params.get("query") ?? (pathname === "/cyberprotect/" ? "кибер бэкап" : "")).trim().slice(0, 1000);
  const kind = params.get("target_kind");
  const id = params.get("target_id");
  return {
    q,
    sort: sorts.includes(params.get("sort")) ? params.get("sort") : sorts[0],
    tab: tabs.some(([name]) => name === params.get("tab")) ? params.get("tab") : pathname === "/vendors/" ? "vendors" : "products",
    page: Number.isInteger(number) && number > 0 && number <= 2147483647 ? number : 1,
    filters: filtersFromParams(params),
    profileId: ["hardware_buyer", "software_admin"].includes(params.get("profile_id")) ? params.get("profile_id") : "",
    target: q && entityKinds.includes(kind) && id ? { kind, target_id: id } : undefined,
  };
}

export function catalogHref({ q, sort, tab, page, filters, profileId, target }) {
  const params = filterParams(filters);
  if (q) params.set("q", q);
  if (sort && sort !== "relevance") params.set("sort", sort);
  if (tab && tab !== "products") params.set("tab", tab);
  if (page > 1) params.set("page", page);
  if (profileId) params.set("profile_id", profileId);
  if (target) {
    params.set("target_kind", target.kind);
    params.set("target_id", target.target_id);
  }
  return params.size ? `/?${params}` : "/";
}
