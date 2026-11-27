import { useEffect, useId, useRef, useState } from "react";
import Filters from "./Filters";
import ProductCard from "./ProductCard";
import RelatedProducts from "./RelatedProducts";
import ResultExplanation from "./ResultExplanation";
import { api, entityHref, filterParams, sessionId, targetLabels } from "./api";
import { catalogHref, readCatalogRoute, tabs, taskQueries } from "./catalogRoute";
import { cardEvent, trackEvents } from "./events";

const emptyFacets = { vendors: [], categories: [] };
const searchPageSize = 4;
const catalogPageSize = 8;

function SearchBox({ route, submit, update }) {
  const [draft, setDraft] = useState(route.q);
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState(-1);
  const [list, setList] = useState(null);
  const listId = useId();
  const query = draft.trim();
  const canSuggest = open && query.length >= 3;
  const params = new URLSearchParams({ q: query });
  if (route.profileId) params.set("profile_id", route.profileId);
  if (route.filters.category_id) params.set("category_id", route.filters.category_id);
  const key = params.toString();
  const visible = canSuggest && list?.key === key && list.items.length > 0;

  useEffect(() => {
    if (!canSuggest) return;
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      api(`/v1/suggest?${key}`, { signal: controller.signal })
        .then(items => { if (!controller.signal.aborted) setList({ key, items }); })
        .catch(() => { if (!controller.signal.aborted) setList(null); });
    }, 160);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [canSuggest, key]);

  function close() {
    setOpen(false);
    setSelected(-1);
  }

  function choose(suggestion) {
    if (!query) return;
    close();
    submit(query, suggestion);
  }

  return <form className="searchbar" role="search" onSubmit={event => {
    event.preventDefault();
    choose(visible ? list.items[selected] : undefined);
  }}>
    <label className="profile-control">Профиль покупателя
      <select value={route.profileId} onChange={event => update({ profileId: event.target.value, page: 1 })}>
        <option value="">Без профиля</option>
        <option value="hardware_buyer">Закупщик оборудования</option>
        <option value="software_admin">Администратор ПО</option>
      </select>
    </label>
    <div className="input-group" onBlur={event => {
      if (!event.currentTarget.contains(event.relatedTarget)) close();
    }}>
      <label className="sr-only" htmlFor="search-input">Товар, артикул или задача</label>
      <input id="search-input" type="search" role="combobox" aria-autocomplete="list" aria-expanded={!!visible}
        aria-controls={visible ? listId : undefined} aria-activedescendant={visible && selected >= 0 ? `${listId}-${selected}` : undefined}
        autoComplete="off" maxLength={1000} placeholder="Товар, производитель или артикул" value={draft}
        onFocus={() => setOpen(draft !== route.q)} onChange={event => {
          setDraft(event.target.value);
          setSelected(-1);
          setOpen(true);
        }} onKeyDown={event => {
          if (event.key === "Escape") close();
          if (!visible || !["ArrowDown", "ArrowUp"].includes(event.key)) return;
          event.preventDefault();
          const length = list.items.length;
          setSelected(index => event.key === "ArrowDown" ? (index + 1) % length : index < 0 ? length - 1 : (index - 1 + length) % length);
        }} />
      {visible && <ul className="suggestions" role="listbox" aria-label="Подсказки" id={listId}>
        {list.items.map((item, i) => <li key={`${item.kind}-${item.target_id}`} role="option" id={`${listId}-${i}`} aria-selected={selected === i}
          onMouseDown={event => event.preventDefault()} onClick={() => choose(item)}>
          <span>{item.label}</span><small>{targetLabels[item.kind]}</small>
        </li>)}
      </ul>}
    </div>
    <button className="button primary search-button" type="submit">Найти</button>
  </form>;
}

function Pagination({ current, count, href, navigate }) {
  if (count <= 1) return null;
  const pages = [1, ...Array.from({ length: 5 }, (_, i) => current - 2 + i)
    .filter(page => page > 1 && page < count), count];
  return <nav className="pagination" aria-label="Страницы выдачи">
    {current > 1 && <a href={href(current - 1)} onClick={event => navigate(event, href(current - 1))}>Назад</a>}
    {pages.map((page, index) => <span key={page}>
      {index > 0 && page > pages[index - 1] + 1 && <span aria-hidden="true">…</span>}
      <a href={href(page)} onClick={event => navigate(event, href(page))}
        aria-label={`Страница ${page}`} aria-current={page === current ? "page" : undefined}>{page}</a>
    </span>)}
    {current < count && <a href={href(current + 1)} onClick={event => navigate(event, href(current + 1))}>Далее</a>}
  </nav>;
}

function readProducts(requestKey, signal) {
  const route = readCatalogRoute(new URLSearchParams(requestKey), "/");
  if (!route.q) {
    const params = filterParams(route.filters);
    params.set("sort", route.sort);
    params.set("page", String(route.page));
    params.set("page_size", String(catalogPageSize));
    return api(`/v1/catalog?${params}`, { signal });
  }
  const session = sessionId();
  return api("/v1/search", { signal, body: {
    q: route.q, sort: route.sort, limit: 50, filters: route.filters, strategy: "standard",
    session_id: session, selected_target: route.target,
    context: route.profileId ? { profile_id: route.profileId } : undefined,
  } }).then(result => ({ ...result, session }));
}

const entries = (items, block) => items.map((item, index) => ({ item, block, position: index + 1 }));

export default function CatalogPage({ route, go, navigate }) {
  const key = catalogHref({ ...route, page: route.q ? 1 : route.page, tab: "products" }).split("?")[1] ?? "";
  const [load, setLoad] = useState(null);
  const [attempt, setAttempt] = useState(0);
  const lastSearch = useRef(null);
  const shown = useRef(new Set());
  const data = load?.key === key ? load.data : undefined;
  const pending = load?.key !== key;
  const error = load?.key === key ? load.error : undefined;
  const facets = data?.facets ?? emptyFacets;
  const top = entries(data?.top4 ?? [], "top4");
  const direct = [...top.filter(({ item }) => item.role === "direct"), ...entries(data?.more_results ?? [], "more_results")];
  const complements = top.filter(({ item }) => item.role === "complement");
  const total = data?.total ?? direct.length;
  const pageCount = Math.max(1, Math.ceil(total / (route.q ? searchPageSize : catalogPageSize)));
  const current = data?.page ?? Math.min(route.page, pageCount);
  const rows = route.q
    ? direct.slice((current - 1) * searchPageSize, current * searchPageSize) : entries(data?.offers ?? [], "");
  const primary = data?.primary_target;

  function update(change) {
    go(catalogHref({ ...route, ...change }));
  }

  useEffect(() => {
    const controller = new AbortController();
    readProducts(key, controller.signal).then(result => {
      if (controller.signal.aborted) return;
      if (result.search_id) {
        const previous = lastSearch.current;
        if (previous?.session === result.session && previous.q !== result.normalized_q) {
          trackEvents(result.session, [{ type: "reformulation", search_id: previous.search_id, next_search_id: result.search_id }]);
        }
        lastSearch.current = { search_id: result.search_id, session: result.session, q: result.normalized_q };
      }
      setLoad({ key, data: result });
    }).catch(failure => {
      if (!controller.signal.aborted) setLoad({ key, error: failure.message });
    });
    return () => controller.abort();
  }, [key, attempt]);

  useEffect(() => {
    if (!data?.search_id || route.tab !== "products") return;
    const marker = `${data.search_id}/${current}`;
    if (shown.current.has(marker)) return;
    shown.current.add(marker);
    const pageRows = [...entries(data.top4.filter(item => item.role === "direct"), "top4"), ...entries(data.more_results, "more_results")]
      .slice((current - 1) * searchPageSize, current * searchPageSize);
    if (current === 1) {
      pageRows.push(...entries(data.top4, "top4").filter(({ item }) => item.role === "complement"));
      pageRows.push(...entries(data.alternatives ?? [], "alternatives"));
    }
    trackEvents(data.session, pageRows.map(({ item, block, position }) => cardEvent("impression", item, block, position, data.search_id)));
  }, [data, current, route.tab]);

  function visit(event, target, entry, filters = data?.applied_filters ?? {}) {
    const tracked = data?.search_id && (entry || primary?.kind === target.kind && primary?.id === target.id);
    if (tracked) {
      const interaction = entry ? cardEvent("click", entry.item, entry.block, entry.position, data.search_id) : {
        type: "click", block: "navigation", search_id: data.search_id, target_kind: target.kind, target_id: target.id,
      };
      trackEvents(data.session, [interaction]);
    }
    navigate(event, entityHref(target, filters), tracked
      ? { searchId: data.search_id, session: data.session, familyKey: entry?.item.family_key } : null);
  }

  function grid(items) {
    return <div className="product-grid" data-size={items.length}>
      {items.map(entry => {
        const { item } = entry;
        const target = { kind: "offer", id: item.offer_id };
        return <ProductCard key={item.offer_id} item={item} action={
          <a className="button secondary card-link" href={entityHref(target, data.applied_filters)}
            aria-label={`К товару ${item.name}`} onClick={event => visit(event, target, entry)}>К товару</a>
        } explanation={data.search_id && <ResultExplanation key={data.search_id} searchId={data.search_id} offerId={item.offer_id} />} />;
      })}
    </div>;
  }

  return <main className="container page" tabIndex={-1}>
    <SearchBox key={route.q} route={route} update={update} submit={(q, suggestion) => update({
      q, page: 1, tab: "products", target: suggestion && { kind: suggestion.kind, target_id: suggestion.target_id },
    })} />
    <div className="example-links task-queries">
      {taskQueries.map(q => {
        const href = catalogHref({ q, profileId: route.profileId });
        return <a className="button secondary" href={href} onClick={event => navigate(event, href)} key={q}>{q}</a>;
      })}
    </div>
    <div className="catalog-workspace">
      <div className="result-heading">
        <h1>{route.q ? `Результаты по запросу «${route.q}»` : "Каталог"}</h1>
        {data && <p className="summary-text" role="status">Найдено: {data.direct_count ?? data.total}</p>}
      </div>
      <nav className="result-tabs" aria-label="Виды результатов">
        {tabs.map(([key, label]) => <button type="button" key={key} aria-pressed={route.tab === key}
          onClick={() => update({ tab: key })}>{label}
          {data && <span>{key === "products" ? data.direct_count ?? data.total : facets[key].length}</span>}
        </button>)}
      </nav>
      <div className="result-layout">
        <Filters key={filterParams(route.filters).toString()} filters={route.filters} facets={facets} busy={pending}
          update={change => update({ page: 1, filters: { ...route.filters, ...change } })}
          reset={() => update({ page: 1, filters: {} })} />
        <section className="result-main" aria-busy={pending} aria-label="Выдача">
          {route.tab === "products" && <label className="sort-control">Показывать сначала
            <select value={route.sort} onChange={event => update({ sort: event.target.value, page: 1 })}>
              <option value="relevance">По релевантности</option>
              <option value="price_asc">Недорогие</option>
              <option value="price_desc">Дорогие</option>
            </select>
          </label>}
          {pending && <p className="result-state" role="status">Загружаем товары…</p>}
          {error && <div className="result-state" role="alert"><p>{error}</p>
            <button className="button secondary" type="button" onClick={() => { setLoad(null); setAttempt(value => value + 1); }}>Повторить</button>
          </div>}
          {data && route.tab === "products" && <>
            {current === 1 && primary && <a className="primary-target" href={entityHref(primary, data.applied_filters)}
              onClick={event => visit(event, primary)}>{`Открыть: ${primary.label ?? targetLabels[primary.kind]}`}</a>}
            {rows.length ? grid(rows) : <p className="result-state">Подходящих товаров не найдено.</p>}
            {current === 1 && <>
              <RelatedProducts items={complements.map(({ item }) => item)}
                onVisit={(event, target) => visit(event, target, complements.find(({ item }) => item.offer_id === target.id), {})} />
              {!!data.alternatives?.length && <section className="result-group"><h2>Альтернативы</h2>{grid(entries(data.alternatives, "alternatives"))}</section>}
            </>}
            <Pagination current={current} count={pageCount} href={page => catalogHref({ ...route, page })} navigate={navigate} />
            {data.direct_count > total && <p className="summary-text">Показаны первые {total} результатов. Уточните запрос или фильтры.</p>}
          </>}
          {data && route.tab !== "products" && (facets[route.tab].length ? <ul className="facet-results">
            {facets[route.tab].map(facet => <li key={facet.id}><button type="button" onClick={() => update({
              tab: "products", page: 1, filters: { ...route.filters, [route.tab === "vendors" ? "vendor_id" : "category_id"]: facet.id },
            })}><span>{facet.label}</span><span>{facet.count}</span></button></li>)}
          </ul> : <p className="result-state">По этим условиям ничего не найдено.</p>)}
        </section>
      </div>
    </div>
  </main>;
}
