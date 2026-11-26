import { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import "./style.css";

async function request(path, options) {
  const response = await fetch(path, options);
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new Error(problem.detail || "Сервис поиска недоступен");
  }
  return response.json();
}

const post = (body, signal) => ({
  method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), signal,
});

function price(card) {
  if (card.price_minor == null) return "Цена по запросу";
  return new Intl.NumberFormat("ru-RU", { style: "currency", currency: card.currency })
    .format(card.price_minor / 100);
}

function Card({ card, searchId }) {
  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  async function explain() {
    setLoading(true);
    setError("");
    try {
      setDetail(await request("/v1/explain", post({ search_id: searchId, offer_id: card.offer_id })));
    } catch (e) { setError(e.message); }
    finally { setLoading(false); }
  }
  return <article>
    <p className="meta">{card.vendor} · {card.available ? "В наличии" : "Под заказ"}</p>
    <h3><a href={card.url}>{card.name}</a></h3>
    <p>{price(card)}{card.sku && <small> · {card.sku}</small>}</p>
    {card.role === "complement" && card.checks.map(c => <p key={c.source_url}>
      Для {c.anchor_value} · <a href={c.source_url}>Таблица производителя</a>
    </p>)}
    {card.differences?.map(d => <p key={d}>{d}</p>)}
    <p>{detail ? detail.text : card.explanation}</p>
    {detail ? <a href={detail.source_url}>Источник</a> :
      <button className="link" type="button" onClick={explain} disabled={loading}>
        {loading ? "Загружаем пояснение…" : "Почему этот товар?"}</button>}
    {error && <p role="alert">{error}</p>}
  </article>;
}

function Entry({ kind, id }) {
  const [entry, setEntry] = useState(null);
  const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    request("/v1/entities/" + encodeURIComponent(kind) + "/" + encodeURIComponent(id), { signal: controller.signal })
      .then(setEntry).catch(e => { if (!controller.signal.aborted) setError(e.message); });
    return () => controller.abort();
  }, [kind, id]);
  return <main><a href="/">← К поиску</a>{error && <p role="alert">{error}</p>}
    {entry ? <><h1>{entry.name}</h1>{entry.offers.map(o => <article key={o.offer_id}>
      <h3>{o.name}</h3><p>{price(o)} · {o.available ? "В наличии" : "Под заказ"}</p>
      <small>Предложение {o.offer_id}{o.sku && " · " + o.sku}</small>
      {o.url.startsWith("http") && <p><a href={o.url}>Открыть в магазине</a></p>}
    </article>)}</> : !error && <p>Загрузка…</p>}</main>;
}

function App() {
  const [q, setQ] = useState("");
  const [result, setResult] = useState(null);
  const [suggestions, setSuggestions] = useState([]);
  const [suggestOpen, setSuggestOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const active = useRef();
  const [health, setHealth] = useState(null);
  useEffect(() => {
    const c = new AbortController();
    request("/health", { signal: c.signal }).then(setHealth).catch(() => {});
    return () => { c.abort(); active.current?.abort(); };
  }, []);
  useEffect(() => {
    if (!q.trim() || !suggestOpen) return;
    const c = new AbortController();
    const timer = setTimeout(() => request("/v1/suggest?q=" + encodeURIComponent(q), { signal: c.signal })
      .then(setSuggestions).catch(() => {}), 180);
    return () => { clearTimeout(timer); c.abort(); };
  }, [q, suggestOpen]);
  async function search(pick) {
    if (!q.trim()) return;
    active.current?.abort();
    const c = new AbortController();
    active.current = c;
    setSuggestOpen(false); setSuggestions([]); setBusy(true); setError(""); setResult(null);
    try {
      const payload = { q, limit: 12 };
      if (pick) payload.selected_target = { kind: pick.kind, target_id: pick.target_id };
      const response = await request("/v1/search", post(payload, c.signal));
      if (!c.signal.aborted) setResult(response);
    } catch (e) { if (!c.signal.aborted) setError(e.message); }
    finally { if (!c.signal.aborted) setBusy(false); }
  }
  const list = (title, cards) => cards.length > 0 && <section><h2>{title}</h2>
    <div className="cards">{cards.map(c => <Card key={result.search_id + c.offer_id} card={c} searchId={result.search_id} />)}</div></section>;
  return <main>
    <header><span>Softline Store</span><h1>Поиск по каталогу</h1></header>
    <form role="search" onSubmit={e => { e.preventDefault(); search(); }}>
      <label htmlFor="query">Название, артикул или задача</label>
      <div className="row"><input id="query" type="search" autoComplete="off" maxLength={512}
        value={q} onChange={e => { setQ(e.target.value); setSuggestions([]); setSuggestOpen(true); }}
        onKeyDown={e => { if (e.key === "Escape") setSuggestOpen(false); }}
        placeholder="Например, ПО для сервера" />
        <button disabled={!q.trim()}>Найти</button></div>
      {suggestOpen && suggestions.length > 0 && <ul className="suggestions" aria-label="Подсказки">
        {suggestions.map(s => <li key={s.kind + s.target_id}><button type="button" onClick={() => search(s)}>{s.label}</button></li>)}
      </ul>}
    </form>
    <div aria-live="polite">{busy && <p>Ищем в каталоге…</p>}{error && <p role="alert">{error}</p>}
      {result && <>
        {result.index_version.startsWith("demo-") && <p className="meta">Демонстрационный снимок · тестовые цены и наличие</p>}
        {result.primary_target && <a href={result.primary_target.url}>Открыть {({ offer: "предложение", family: "семейство", collection: "подборку",
          category: "раздел", vendor: "страницу вендора" })[result.primary_target.kind]} →</a>}
        {result.shortfall_reason && <p>{result.direct_count === 0 ? "Точных совпадений нет." : "Подтверждённых семейств меньше четырёх."}</p>}
        {list("По запросу", result.top4.filter(c => c.role === "direct"))}
        {list("Совместимые товары", result.top4.filter(c => c.role === "complement"))}
        {list("Ещё по запросу", result.more_results)}
        {list("Функциональные аналоги", result.alternatives)}
      </>}
    </div>
    {health && <details className="health"><summary>Состояние сервисов</summary>
      <p>Spring Boot доступен · FastAPI {health.model_service === "up" ? "доступен" : "недоступен"}</p></details>}
  </main>;
}

const params = new URLSearchParams(location.search);
createRoot(document.getElementById("root")).render(params.has("kind") && params.has("id")
  ? <Entry kind={params.get("kind")} id={params.get("id")} /> : <App />);
