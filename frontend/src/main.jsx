import { useEffect, useId, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import AssistantChat from "./AssistantChat";
import { recordEvent, useCardImpression } from "./searchEvents";
import ProductImage from "./ProductImage";
import { demoSearch, demoSuggestions, fetchSearch, safeLink } from "./catalog";
import "./style.css";

const DEFAULT_QUERY = "Pantum P2500W или BM1800W";
const examples = ["сервер", DEFAULT_QUERY, "мтс", "EP2-06605"];
const tabs = [
  ["products", "Товары"],
  ["vendors", "Производители"],
  ["categories", "Категории"],
];
const navigationLabels = {
  vendor: "Перейти на страницу производителя",
  category: "Перейти в категорию",
  family: "Перейти к семейству",
  collection: "Перейти к подборке",
};
const money = new Intl.NumberFormat("ru-RU", { style: "currency", currency: "RUB", maximumFractionDigits: 2, minimumFractionDigits: 0 });

function formatPrice(item) {
  if (item.price_minor == null) return "Цена по запросу";
  return !item.currency || item.currency === "RUB" ? money.format(item.price_minor / 100)
    : new Intl.NumberFormat("ru-RU", { style: "currency", currency: item.currency }).format(item.price_minor / 100);
}

function FacetSelect({ label, value, options, onChange }) {
  const known = options.some(option => option.id === value);
  const retained = value && !known;
  return (
    <label className="filter-field">
      <span>{label}</span>
      <select value={value} onChange={event => onChange(event.target.value)} disabled={!options.length && !retained}>
        <option value="">Все</option>
        {retained && <option value={value}>{value}</option>}
        {options.map(option => <option key={option.id} value={option.id}>{option.label} ({option.count})</option>)}
      </select>
    </label>
  );
}

function Filters({ filters, facets, update, reset, busy }) {
  const [price, setPrice] = useState("");
  const [advanced, setAdvanced] = useState({ platform: "", version: "", device_count: "", term_months: "" });
  const [open, setOpen] = useState(false);
  const active = Object.values(filters).some(Boolean) || filters.max_price_minor === 0;

  function apply(event) {
    event.preventDefault();
    update({ ...Object.fromEntries(Object.entries(advanced).map(([key, value]) => [key, value ? (key === "device_count" || key === "term_months" ? Number(value) : value.trim()) : undefined])),
      max_price_minor: price === "" ? undefined : Math.round(Number(price) * 100) });
  }

  function clear() {
    setPrice("");
    setAdvanced({ platform: "", version: "", device_count: "", term_months: "" });
    reset();
  }

  return (
    <aside className={`filters${open ? " filters-open" : ""}`} aria-labelledby="filters-title">
      <div className="filter-heading">
        <h2 id="filters-title">Фильтры</h2>
        <button className="filter-toggle" type="button" onClick={() => setOpen(!open)} aria-expanded={open} aria-controls="filter-panel">{open ? "Свернуть" : "Развернуть"}</button>
        {active && <button className="text-button filter-reset" type="button" onClick={clear} disabled={busy}>Сбросить</button>}
      </div>
      <form className="filter-form" id="filter-panel" onSubmit={apply}>
        <FacetSelect label="Категория" value={filters.category_id ?? ""} options={facets.categories} onChange={value => update({ category_id: value || undefined })} />
        <FacetSelect label="Производитель" value={filters.vendor_id ?? ""} options={facets.vendors} onChange={value => update({ vendor_id: value || undefined })} />
        <label className="filter-field">
          <span>Цена до, ₽</span>
          <input type="number" min="0" max="1000000000" step="0.01" inputMode="decimal" placeholder="Без ограничения" value={price} onChange={event => setPrice(event.target.value)} />
        </label>
        <label className="checkbox-field">
          <input type="checkbox" checked={filters.available_only ?? false} onChange={event => update({ available_only: event.target.checked || undefined })} />
          Только в наличии
        </label>
        <details className="license-filters">
          <summary>Условия лицензии</summary>
          <div className="license-fields">
            <label className="filter-field">Тип лицензии
              <select value={filters.license_type ?? ""} onChange={event => update({ license_type: event.target.value || undefined })}>
                <option value="">Любой</option><option value="new">Новая</option><option value="renewal">Продление</option><option value="upgrade">Обновление</option>
              </select>
            </label>
            {[["platform", "Платформа"], ["version", "Версия"], ["device_count", "Устройства"], ["term_months", "Срок, мес."]].map(([key, label], index) => (
              <label className="filter-field" key={key}>{label}
                <input value={advanced[key]} type={index < 2 ? "text" : "number"} min={index < 2 ? undefined : 1}
                  onChange={event => setAdvanced({ ...advanced, [key]: event.target.value })} />
              </label>
            ))}
          </div>
        </details>
        <button className="button secondary apply-button" type="submit" disabled={busy}>Применить</button>
      </form>
    </aside>
  );
}

function ProductCard({ item, anchor, tracking }) {
  const [explanation, setExplanation] = useState("");
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const searchId = tracking?.searchId;
  const session = tracking?.session;
  const position = tracking?.position;
  const block = tracking?.block;
  const { element, click } = useCardImpression(session, searchId, item, block, position);

  async function explain() {
    setLoading(true);
    setFailed(false);
    try {
      const answer = await fetchSearch("/v1/explain", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ search_id: searchId, offer_id: item.offer_id }) });
      setExplanation(answer.text);
    } catch { setFailed(true); }
    finally { setLoading(false); }
  }

  const url = safeLink(item.url);
  const attributes = Object.entries(item.attributes ?? {}).slice(0, 2);
  return (
    <article ref={element} className="product-card" data-role={item.role}>
      <figure className="product-figure">
        <div className="product-photo"><ProductImage item={item} /></div>
        <figcaption><h3>{item.name}</h3></figcaption>
      </figure>
      <div className="product-top">
        <span className="vendor-name">{item.vendor}</span>
        {item.role === "complement" && <span className="compatibility-tag">Совместимо</span>}
      </div>
      {item.sku && <p className="sku">Артикул {item.sku}</p>}
      {attributes.length > 0 && <dl className="product-attributes">
        {attributes.map(([key, values]) => <div key={key}><dt>{key}</dt><dd>{values.join(", ")}</dd></div>)}
      </dl>}
      {anchor && <p className="compatible-with">Подходит к {anchor.name}</p>}
      <div className="product-bottom">
        <div><p className="price">{formatPrice(item)}</p><p className={`stock${item.available ? " available" : ""}`}>{item.available ? "В наличии" : "Нет в наличии"}</p></div>
        {url && <a className="button secondary card-link" onClick={click} href={url} target="_blank" rel="noreferrer">К товару</a>}
      </div>
      {searchId && (explanation ? <p className="compatible-with" role="status">{explanation}</p>
        : <button type="button" className="text-button" onClick={explain} disabled={loading}>
          {loading ? "Готовим пояснение…" : failed ? "Повторить запрос пояснения" : "Почему этот товар?"}</button>)}
      {!!item.checks?.length && <details className="proof"><summary>Почему подходит</summary>
        <ul>{item.checks.map((check, index) => {
          const source = safeLink(check.source_url);
          return <li key={index}>{check.candidate_value} подходит к {check.anchor_value}. {source && <a href={source} target="_blank" rel="noreferrer">Источник проверки</a>}</li>;
        })}</ul>
      </details>}
      {!!item.differences?.length && <p className="compatible-with">{item.differences.join(". ")}</p>}
    </article>
  );
}

function ProductGrid({ items, anchors, session, result, block = "top4" }) {
  return <div className="product-grid" data-size={items.length}>{items.map(item => <ProductCard key={[result?.search_id, item.offer_id].join("/")} item={item}
    tracking={session && { session, searchId: result.search_id, block, position: result[block].indexOf(item) + 1 }} anchor={anchors?.get(item.anchor_offer_id)} />)}</div>;
}

function entityLink(url, filters) {
  const link = safeLink(url);
  if (!link?.startsWith("/?")) return link;
  const target = new URL(link, window.location.origin);
  for (const [key, value] of Object.entries(filters)) {
    if (value != null) target.searchParams.set(key, value);
  }
  return target.pathname + target.search;
}

function EntityPage({ params }) {
  const [entity, setEntity] = useState(null);
  const [error, setError] = useState("");
  const [limit, setLimit] = useState(24);
  const filterParams = new URLSearchParams(params);
  filterParams.delete("kind");
  filterParams.delete("id");
  const path = `/v1/entities/${encodeURIComponent(params.get("kind"))}/${encodeURIComponent(params.get("id"))}?${filterParams}`;
  const filters = Object.fromEntries(filterParams);
  useEffect(() => {
    const controller = new AbortController();
    fetchSearch(path, { signal: controller.signal })
      .then(data => { if (!controller.signal.aborted) setEntity(data); })
      .catch(failure => { if (!controller.signal.aborted) setError(failure.message); });
    return () => controller.abort();
  }, [path]);

  return (
    <main className="container page entity-page">
      <a href="/">К поиску по каталогу</a>
      {error && <p role="alert">{error}</p>}
      {!entity && !error && <p role="status">Загружаем карточку…</p>}
      {entity && <>
        <h1>{entity.name}</h1>
        {!!entity.offers.length && <ProductGrid items={entity.offers.slice(0, limit)} />}
        {!!entity.children.length && <ul>
          {entity.children.slice(0, limit).map(child => {
            const url = entityLink(child.url, filters);
            const label = child.kind === "family" ? "Варианты семейства" : `Предложение № ${child.id}`;
            return <li key={`${child.kind}/${child.id}`}>{url ? <a href={url}>{label}</a> : label}</li>;
          })}
        </ul>}
        {Math.max(entity.offers.length, entity.children.length) > limit && <button type="button" className="button secondary" onClick={() => setLimit(limit + 24)}>Показать ещё</button>}
        {!entity.offers.length && !entity.children.length && <p>В выбранных условиях подходящих предложений нет.</p>}
      </>}
    </main>
  );
}

function App() {
  const demo = new URLSearchParams(window.location.search).get("demo") === "1";
  const initialRequest = demo ? { q: DEFAULT_QUERY, strategy: "standard", sort: "relevance", filters: {} } : null;
  const [query, setQuery] = useState(initialRequest?.q ?? "");
  const [request, setRequest] = useState(initialRequest);
  const [result, setResult] = useState(() => initialRequest ? demoSearch(initialRequest) : null);
  const [filters, setFilters] = useState({});
  const [profileId, setProfileId] = useState("");
  const [sort, setSort] = useState("relevance");
  const [tab, setTab] = useState("products");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [activeInput, setActiveInput] = useState(false);
  const [suggestions, setSuggestions] = useState([]);
  const [activeSuggestion, setActiveSuggestion] = useState(-1);
  const searchRequest = useRef(null);
  const searchInput = useRef(null);
  const sessionId = useRef(null);
  const previousSearch = useRef(null);
  const suggestionsId = useId();

  useEffect(() => () => searchRequest.current?.abort(), []);

  useEffect(() => {
    const value = query.trim();
    setSuggestions([]);
    setActiveSuggestion(-1);
    if (!activeInput || value.length < 2) return;
    const controller = new AbortController();
    const timeout = window.setTimeout(async () => {
      try {
        const params = new URLSearchParams({ q: value });
        if (filters.category_id) params.set("category_id", filters.category_id);
        if (profileId) params.set("profile_id", profileId);
        const items = demo ? demoSuggestions(value, filters.category_id, profileId) : await fetchSearch(`/v1/suggest?${params}`, { signal: controller.signal });
        if (!controller.signal.aborted) setSuggestions(items);
      } catch {
        if (!controller.signal.aborted) setSuggestions([]);
      }
    }, 120);
    return () => { window.clearTimeout(timeout); controller.abort(); };
  }, [activeInput, query, demo, filters.category_id, profileId]);

  async function search(next, profile = profileId) {
    searchRequest.current?.abort();
    const body = { ...next, context: profile ? { profile_id: profile } : undefined };
    setRequest(body);
    setSuggestions([]);
    setActiveSuggestion(-1);
    setError("");
    if (demo) {
      setResult(demoSearch(body));
      return;
    }
    const controller = new AbortController();
    searchRequest.current = controller;
    setPending(true);
    setResult(null);
    sessionId.current ??= globalThis.crypto?.randomUUID?.() ?? `search-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    try {
      const payload = await fetchSearch("/v1/search", {
        method: "POST", signal: controller.signal,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...body, session_id: sessionId.current }),
      });
      if (!controller.signal.aborted) {
        const previous = previousSearch.current;
        if (previous && previous.q !== body.q)
          recordEvent(sessionId.current, "reformulation", { search_id: previous.id, next_search_id: payload.search_id });
        previousSearch.current = { id: payload.search_id, q: body.q };
        setResult(payload);
      }
    } catch (failure) {
      if (!controller.signal.aborted) {
        if (next.selected_target && failure.code === "UNKNOWN_TARGET") {
          setRequest({ ...body, selected_target: undefined });
          searchInput.current?.focus();
          setActiveInput(true);
          setError("Выбранная подсказка устарела. Выберите вариант ещё раз или нажмите Enter для поиска по тексту.");
        } else {
          setError(failure.message === "Failed to fetch" ? "Сервис поиска пока недоступен." : failure.message);
        }
      }
    } finally {
      if (!controller.signal.aborted) setPending(false);
    }
  }

  function submit(q, suggestion) {
    if (!q.trim()) return;
    setQuery(q);
    setTab("products");
    setActiveInput(false);
    search({ q: q.trim(), strategy: "standard", sort, filters, selected_target: suggestion && { kind: suggestion.kind, target_id: suggestion.target_id } });
  }

  function setAndSearch(nextFilters) {
    setFilters(nextFilters);
    if (request) search({ ...request, filters: nextFilters });
  }

  function changeProfile(profile) {
    setProfileId(profile);
    setSuggestions([]);
    setActiveSuggestion(-1);
    if (request && query.trim() === request.q) {
      search(request, profile);
    } else if (request) {
      searchRequest.current?.abort();
      setRequest(null);
      setResult(null);
      setError("");
      setPending(false);
    }
  }

  function applyFacet(group, id) {
    setTab("products");
    setAndSearch({ ...filters, [group === "vendors" ? "vendor_id" : "category_id"]: id });
  }

  const showSuggestions = activeInput && suggestions.length > 0;
  const facets = result?.facets ?? { vendors: [], categories: [] };
  const direct = (result?.top4 ?? []).filter(item => item.role === "direct");
  const anchors = new Map(direct.map(item => [item.offer_id, item]));
  const complements = (result?.top4 ?? []).filter(item => item.role === "complement" && anchors.has(item.anchor_offer_id) && item.checks?.length && item.checks.every(check => check.result === "pass"));
  const primary = result?.primary_target;
  const offerWithoutFamilyCard = primary?.kind === "offer" && !direct.length;
  const tracking = { session: demo ? null : sessionId.current, result };
  function navigationClick() {
    recordEvent(tracking.session, "click", { search_id: result.search_id, block: "navigation", target_kind: primary.kind, target_id: primary.id });
  }
  const primaryUrl = entityLink(primary?.url, result?.applied_filters ?? {});
  const facetItems = tab === "products" ? [] : facets[tab];
  const title = request ? `Результаты по запросу «${request.q}»` : "Поиск по каталогу";

  return (
    <>
      <header className="site-header">
        <div className="container header-content">
          <div className="identity"><span className="identity-mark" aria-hidden="true" /><span>Каталог решений</span></div>
          {demo && <span className="demo-label">Демоданные</span>}
        </div>
      </header>
      <main className="container page">
        <form className="searchbar" role="search"
          onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setActiveInput(false); }}
          onSubmit={event => {
            event.preventDefault();
            submit(query, showSuggestions ? suggestions[activeSuggestion] : undefined);
          }}>
          <label className="profile-control">
            Профиль покупателя (демо)
            <select value={profileId} onChange={event => changeProfile(event.target.value)}>
              <option value="">Без профиля</option>
              <option value="hardware_buyer">Закупщик оборудования</option>
              <option value="software_admin">Администратор ПО</option>
            </select>
          </label>
          <div className="input-group">
            <label className="sr-only" htmlFor="search-input">Товар, артикул или задача</label>
            <input ref={searchInput} id="search-input" required type="search" role="combobox" autoComplete="off" placeholder="Название, артикул или задача"
              aria-autocomplete="list" aria-expanded={showSuggestions} aria-controls={showSuggestions ? suggestionsId : undefined}
              aria-activedescendant={showSuggestions && activeSuggestion >= 0 ? `${suggestionsId}-${activeSuggestion}` : undefined}
              value={query} onFocus={() => setActiveInput(true)} onChange={event => { setQuery(event.target.value); setActiveInput(true); setSuggestions([]); setActiveSuggestion(-1); }}
              onKeyDown={event => {
                if (event.key === "Escape") setActiveInput(false);
                if (!showSuggestions || !["ArrowDown", "ArrowUp"].includes(event.key)) return;
                event.preventDefault();
                setActiveSuggestion(index => index < 0 ? (event.key === "ArrowDown" ? 0 : suggestions.length - 1)
                  : (index + (event.key === "ArrowDown" ? 1 : -1) + suggestions.length) % suggestions.length);
              }} />
            {showSuggestions && <ul role="listbox" id={suggestionsId} className="suggestions" aria-label="Подсказки">
              {suggestions.map((suggestion, index) => <li key={`${suggestion.kind}-${suggestion.target_id}`} role="option" aria-selected={index === activeSuggestion} id={`${suggestionsId}-${index}`}
                onMouseDown={event => event.preventDefault()} onClick={() => submit(query, suggestion)}>{suggestion.label}</li>)}
            </ul>}
          </div>
          <button className="button primary search-button" type="submit">Найти</button>
        </form>

        <div className="catalog-workspace">
          <div className="catalog-pane">
            <div className="result-heading">
              <h1>{title}</h1>
              {result && <p className="summary-text">Подходящих семейств: {result.direct_count}{complements.length > 0 && ` · Дополнений: ${complements.length}`}</p>}
            </div>

            <nav className="result-tabs" aria-label="Виды результатов">
              {tabs.map(([key, label]) => <button type="button" key={key} aria-pressed={tab === key} onClick={() => setTab(key)}>{label}
                {result && <span>{key === "products" ? result.direct_count : facets[key].length}</span>}
              </button>)}
            </nav>

            <div className="result-layout">
              <Filters filters={filters} facets={facets} update={change => setAndSearch({ ...filters, ...change })} reset={() => setAndSearch({})} busy={pending} />
              <section className="result-main" aria-busy={pending} aria-label="Результаты поиска">
                {tab === "products" && result && <label className="sort-control">Сортировка
                  <select value={sort} onChange={event => {
                    const value = event.target.value;
                    setSort(value);
                    if (request) search({ ...request, sort: value });
                  }}><option value="relevance">По релевантности</option><option value="price_asc">Сначала дешевле</option><option value="price_desc">Сначала дороже</option></select>
                </label>}

                {pending && <p className="result-state" role="status">Ищем товары…</p>}
                {error && <div className="result-state" role="alert"><p>{error}</p><a href="?demo=1">Открыть демо с примерами</a></div>}
                {!request && <div className="result-state"><p>Введите модель, артикул или опишите, что вам нужно.</p>
                  <div className="example-links">{examples.map(example => <button type="button" className="button secondary" key={example} onClick={() => submit(example)}>{example}</button>)}</div>
                </div>}

                {result && tab === "products" && <>
                  {primaryUrl && primary.kind !== "offer" && <a className="primary-target" onClick={navigationClick} href={primaryUrl} target="_blank" rel="noreferrer">{primary.label ?? navigationLabels[primary.kind]}</a>}
                  {direct.length > 0 && <ProductGrid {...tracking} items={direct} />}
                  {offerWithoutFamilyCard && <a className="primary-target" onClick={navigationClick}
                    href={entityLink(`/?kind=offer&id=${encodeURIComponent(primary.id)}`, result.applied_filters ?? {})}>
                    Открыть предложение № {primary.id}
                  </a>}
                  {!direct.length && !offerWithoutFamilyCard && <div className="result-state"><p>Подходящих товаров не найдено.</p><p>Уточните запрос или сбросьте фильтры.</p></div>}
                  {!!complements.length && <section className="result-group" aria-labelledby="complements-title"><h2 id="complements-title">Совместимые дополнения</h2><ProductGrid {...tracking} items={complements} anchors={anchors} /></section>}
                  {result.shortfall_reason === "NOT_ENOUGH_VERIFIED_FAMILIES" && <p className="shortfall">В каталоге меньше четырёх подтверждённых семейств для этого запроса.</p>}
                  {!!result.more_results?.length && <section className="result-group"><h2>Другие подходящие товары</h2><ProductGrid {...tracking} block="more_results" items={result.more_results} /></section>}
                  {!!result.alternatives?.length && <section className="result-group"><h2>Альтернативы</h2><ProductGrid {...tracking} block="alternatives" items={result.alternatives} /></section>}
                </>}

                {result && tab !== "products" && (facetItems.length ? <ul className="facet-results">
                  {facetItems.map(facet => <li key={facet.id}><button type="button" onClick={() => applyFacet(tab, facet.id)}><span>{facet.label}</span><span>{facet.count}</span></button></li>)}
                </ul> : <p className="result-state">{tab === "vendors" ? "Производители не найдены." : "Категории не найдены."}</p>)}
              </section>
            </div>
          </div>
        </div>
      </main>
      <AssistantChat demo={demo} profileId={profileId} />
    </>
  );
}

const route = new URLSearchParams(window.location.search);
createRoot(document.getElementById("root")).render(route.has("kind") && route.has("id") ? <EntityPage params={route} /> : <App />);
