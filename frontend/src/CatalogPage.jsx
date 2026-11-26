import { useEffect, useId, useMemo, useRef, useState } from "react";
import Filters from "./Filters";
import ProductCard from "./ProductCard";
import RelatedProducts from "./RelatedProducts";
import { api, entityHref, filterParams, sessionId, targetLabels } from "./api";
import { demoSearch, demoSuggestions } from "./catalog";
import { cardEvent, trackDemoSearch, trackEvents } from "./events";

const examples = ["сервер", "Pantum P2500W или BM1800W", "Кибер Бэкап", "EP2-06605"];
const tabs = [["products", "Товары"], ["vendors", "Производители"], ["categories", "Категории"]];
const emptyFacets = { vendors: [], categories: [] };
const targetActions = {
  offer: "Открыть точное предложение", family: "Посмотреть варианты", vendor: "Открыть производителя",
  category: "Открыть категорию", collection: "Открыть подборку",
};
const blockEntries = (items, block) => items.map((item, index) => ({ item, block, position: index + 1 }));

function ResultGrid({ entries, visit, demo, applied }) {
  return <div className="product-grid" data-size={entries.length}>
    {entries.map(entry => {
      const { item } = entry;
      const target = { kind: "offer", id: item.offer_id };
      const action = <a className="button secondary card-link" href={entityHref(target, applied, demo)}
        aria-label={`К товару ${item.name}`} onClick={event => visit(event, target, entry)}>К товару</a>;
      return <ProductCard key={item.offer_id} item={item} action={action} />;
    })}
  </div>;
}

export default function CatalogPage({ demo, active, navigate }) {
  const [query, setQuery] = useState(demo ? examples[1] : "");
  const [request, setRequest] = useState(null);
  const [response, setResponse] = useState(null);
  const [filters, setFilters] = useState({});
  const [sort, setSort] = useState("relevance");
  const [tab, setTab] = useState("products");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [activeInput, setActiveInput] = useState(false);
  const [suggestions, setSuggestions] = useState(null);
  const [activeSuggestion, setActiveSuggestion] = useState(-1);
  const searchRequest = useRef(null);
  const lastSearch = useRef(null);
  const lastImpression = useRef(null);
  const initialDemo = useRef(false);
  const searchInput = useRef(null);
  const suggestionsId = useId();

  useEffect(() => () => searchRequest.current?.abort(), []);

  useEffect(() => {
    if (!demo || !active || initialDemo.current) return;
    initialDemo.current = true;
    search({ q: examples[1], strategy: "standard", sort: "relevance", filters: {} });
  }, [demo, active]);

  useEffect(() => {
    const value = query.trim();
    if (!active || !activeInput || value.length < 2 || (value === request?.q && !error)) return;
    const controller = new AbortController();
    const timeout = window.setTimeout(async () => {
      try {
        const params = new URLSearchParams({ q: value });
        if (filters.category_id) params.set("category_id", filters.category_id);
        const items = demo ? demoSuggestions(value, filters.category_id) : await api(`/v1/suggest?${params}`, { signal: controller.signal });
        if (!controller.signal.aborted) setSuggestions({ value, category: filters.category_id, items });
      } catch {
        if (!controller.signal.aborted) setSuggestions(null);
      }
    }, 160);
    return () => { window.clearTimeout(timeout); controller.abort(); };
  }, [active, activeInput, query, request?.q, error, demo, filters.category_id]);

  async function search(next) {
    searchRequest.current?.abort();
    const controller = new AbortController();
    searchRequest.current = controller;
    setRequest(next);
    setSuggestions(null);
    setActiveSuggestion(-1);
    setError("");
    setPending(true);
    setResponse(null);
    const session = sessionId();
    try {
      const result = demo ? demoSearch(next) : await api("/v1/search", { body: { ...next, session_id: session }, signal: controller.signal });
      if (controller.signal.aborted) return;
      if (demo) trackDemoSearch(result, next.q, session);
      const previous = lastSearch.current;
      if (previous && previous.session === session && previous.q !== next.q && previous.searchId !== result.search_id) {
        trackEvents(demo, session, [{ type: "reformulation", search_id: previous.searchId, next_search_id: result.search_id }]);
      }
      lastSearch.current = { searchId: result.search_id, session, q: next.q };
      setResponse({ result, session });
    } catch (failure) {
      if (controller.signal.aborted) return;
      setError(failure.message);
      if (failure.code === "UNKNOWN_TARGET") {
        setRequest({ ...next, selected_target: undefined });
        setActiveInput(true);
        searchInput.current?.focus();
      }
    } finally {
      if (!controller.signal.aborted) setPending(false);
    }
  }

  function submit(q, suggestion) {
    const value = q.trim();
    if (!value) return;
    setQuery(value);
    setTab("products");
    setActiveInput(false);
    search({ q: value, strategy: "standard", sort, filters,
      context: filters.category_id ? { category_id: filters.category_id } : undefined,
      selected_target: suggestion && { kind: suggestion.kind, target_id: suggestion.target_id } });
  }

  function setAndSearch(nextFilters) {
    const clean = Object.fromEntries(Object.entries(nextFilters).filter(([, value]) => value != null && value !== ""));
    setFilters(clean);
    if (request) search({ ...request, filters: clean, context: clean.category_id ? { category_id: clean.category_id } : undefined, limit: undefined });
  }

  const result = response?.result;
  const suggestionsMatch = suggestions?.value === query.trim() && suggestions.category === filters.category_id;
  const suggestedItems = suggestionsMatch ? suggestions.items : [];
  const showSuggestions = activeInput && suggestedItems.length > 0;
  const facets = result?.facets ?? emptyFacets;
  const { direct, complements, more, alternatives } = useMemo(() => {
    const top = blockEntries(result?.top4 ?? [], "top4");
    const direct = top.filter(({ item }) => item.role === "direct");
    const anchors = new Set(direct.map(({ item }) => item.offer_id));
    const complements = top.filter(({ item }) => item.role === "complement" && anchors.has(item.anchor_offer_id)
      && item.checks?.length && item.checks.every(check => check.result === "pass"));
    return { direct, complements, more: blockEntries(result?.more_results ?? [], "more_results"),
      alternatives: blockEntries(result?.alternatives ?? [], "alternatives") };
  }, [result]);
  const primary = result?.primary_target;
  const related = result?.related_offers ?? complements.map(({ item }) => item);
  const applied = result?.applied_filters;
  const facetItems = tab === "products" ? [] : facets[tab];
  const title = request ? `Результаты по запросу «${request.q}»` : "Поиск по каталогу";
  const implied = filterParams(applied).toString() !== filterParams(filters).toString()
    && Object.entries(applied ?? {}).some(([key, value]) => key !== "currency" && value && value !== filters[key]);

  useEffect(() => {
    if (!active || tab !== "products" || !result || lastImpression.current === result.search_id) return;
    lastImpression.current = result.search_id;
    const shownComplements = complements.filter(({ item }) => (result.related_offers ?? result.top4)
      .slice(0, 4).some(relatedItem => relatedItem.offer_id === item.offer_id));
    trackEvents(demo, response.session, [...direct, ...shownComplements, ...more, ...alternatives].map(({ item, block, position }) =>
      cardEvent("impression", item, block, position, result.search_id)));
  }, [active, tab, result, response, demo, direct, complements, more, alternatives]);

  function visit(event, target, entry, selectedFilters = applied) {
    const session = sessionId();
    const attributed = session === response.session;
    const href = entityHref(target, selectedFilters, demo);
    if (attributed) {
      trackEvents(demo, session, [entry ? cardEvent("click", entry.item, entry.block, entry.position, result.search_id) : {
        type: "click", block: "navigation", search_id: result.search_id, target_kind: target.kind, target_id: target.id,
      }]);
    }
    const matchedOffer = target.kind === "offer" && direct.find(({ item }) => item.offer_id === target.id)?.item;
    navigate(event, href, attributed ? { searchId: result.search_id, session, familyKey: entry?.item.family_key ?? (matchedOffer || null)?.family_key } : null);
  }

  const gridProps = { visit, demo, applied };
  return (
    <main className="container page" tabIndex={-1} hidden={!active}>
      <form className="searchbar" role="search" onSubmit={event => {
        event.preventDefault();
        submit(query, showSuggestions ? suggestedItems[activeSuggestion] : undefined);
      }}>
        <div className="input-group" onBlur={event => {
          if (!event.currentTarget.contains(event.relatedTarget)) { setActiveInput(false); setActiveSuggestion(-1); }
        }}>
          <label className="sr-only" htmlFor="search-input">Товар, артикул или задача</label>
          <input id="search-input" ref={searchInput} required type="search" role="combobox" autoComplete="off" maxLength={1000}
            placeholder="Название, артикул или задача" aria-autocomplete="list" aria-expanded={showSuggestions}
            aria-controls={showSuggestions ? suggestionsId : undefined}
            aria-activedescendant={showSuggestions && activeSuggestion >= 0 ? `${suggestionsId}-${activeSuggestion}` : undefined}
            value={query} onFocus={() => setActiveInput(true)}
            onChange={event => { setQuery(event.target.value); setActiveInput(true); setSuggestions(null); setActiveSuggestion(-1); }}
            onKeyDown={event => {
              if (event.key === "Escape") { setActiveInput(false); setActiveSuggestion(-1); }
              if (!showSuggestions || !["ArrowDown", "ArrowUp"].includes(event.key)) return;
              event.preventDefault();
              const direction = event.key === "ArrowDown" ? 1 : -1;
              setActiveSuggestion(index => index < 0 ? (direction > 0 ? 0 : suggestedItems.length - 1)
                : (index + direction + suggestedItems.length) % suggestedItems.length);
            }} />
          {showSuggestions && <ul role="listbox" id={suggestionsId} className="suggestions" aria-label="Подсказки">
            {suggestedItems.map((suggestion, index) => <li key={`${suggestion.kind}-${suggestion.target_id}`} role="option"
              aria-selected={index === activeSuggestion} id={`${suggestionsId}-${index}`}
              onMouseDown={event => event.preventDefault()} onClick={() => submit(query, suggestion)}>
              <span>{suggestion.label}</span><small>{targetLabels[suggestion.kind]}</small>
            </li>)}
          </ul>}
        </div>
        <button className="button primary search-button" type="submit">Найти</button>
      </form>

      <div className="catalog-workspace">
        <div className="result-heading">
          <h1>{title}</h1>
          {result && <p className="summary-text" role="status">Подходящих семейств: {result.direct_count}</p>}
        </div>
        <nav className="result-tabs" aria-label="Виды результатов">
          {tabs.map(([key, label]) => <button type="button" key={key} aria-pressed={tab === key} onClick={() => setTab(key)}>{label}
            {result && <span>{key === "products" ? result.direct_count : facets[key].length}</span>}
          </button>)}
        </nav>
        <div className="result-layout">
          <Filters filters={filters} facets={facets} update={change => setAndSearch({ ...filters, ...change })}
            reset={() => setAndSearch({})} applied={implied} busy={pending} />
          <section className="result-main" aria-busy={pending} aria-label="Результаты поиска">
            {tab === "products" && (result || pending) && <label className="sort-control">Сортировка
              <select value={sort} onChange={event => {
                const value = event.target.value;
                setSort(value);
                if (request) search({ ...request, sort: value });
              }}><option value="relevance">По релевантности</option><option value="price_asc">Сначала дешевле</option><option value="price_desc">Сначала дороже</option></select>
            </label>}
            {pending && <p className="result-state" role="status">Ищем товары…</p>}
            {error && <div className="result-state" role="alert"><p>{error}</p>
              <div className="example-links"><button type="button" className="button secondary" onClick={() => search(request)}>Повторить</button>
              {!demo && <a className="button secondary" href="/?demo=1">Открыть демо</a>}</div>
            </div>}
            {!request && <div className="result-state"><p>Введите модель, артикул или опишите, что вам нужно.</p>
              <div className="example-links">{examples.map(example => <button type="button" className="button secondary" key={example} onClick={() => submit(example)}>{example}</button>)}</div>
            </div>}
            {result && tab === "products" && <>
              {primary && <a className="primary-target" href={entityHref(primary, applied, demo)} onClick={event => visit(event, primary)}>
                {primary.label && primary.kind !== "offer" ? `${targetActions[primary.kind]} «${primary.label}»` : targetActions[primary.kind]}
              </a>}
              {direct.length > 0 ? <ResultGrid {...gridProps} entries={direct} /> : <div className="result-state"><p>Подходящих товаров не найдено.</p>
                <p>{alternatives.length ? "Ниже есть замены с описанием различий." : "Уточните запрос или сбросьте фильтры."}</p>
              </div>}
              {result.shortfall_reason === "NOT_ENOUGH_VERIFIED_FAMILIES" && <p className="shortfall">В каталоге меньше четырёх подтверждённых семейств для этого запроса.</p>}
              {more.length > 0 && <section className="result-group"><h2>Другие подходящие товары</h2><ResultGrid {...gridProps} entries={more} /></section>}
              {result.direct_count > direct.length + more.length && (request.limit ?? 4) < 50 && <button type="button" className="button secondary more-button"
                onClick={() => search({ ...request, limit: Math.min(50, (request.limit ?? 4) + 12) })}>Показать больше</button>}
              {alternatives.length > 0 && <section className="result-group"><h2>Альтернативы</h2><ResultGrid {...gridProps} entries={alternatives} /></section>}
              <RelatedProducts items={related} demo={demo} onVisit={(event, target) =>
                visit(event, target, complements.find(({ item }) => item.offer_id === target.id), {})} />
            </>}
            {result && tab !== "products" && (facetItems.length ? <ul className="facet-results">
              {facetItems.map(facet => <li key={facet.id}><button type="button" onClick={() => {
                setTab("products");
                setAndSearch({ ...filters, [tab === "vendors" ? "vendor_id" : "category_id"]: facet.id });
              }}><span>{facet.label}</span><span>{facet.count}</span></button></li>)}
            </ul> : <p className="result-state">{tab === "vendors" ? "Производители не найдены." : "Категории не найдены."}</p>)}
          </section>
        </div>
      </div>
    </main>
  );
}
