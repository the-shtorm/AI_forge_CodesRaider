import { useEffect, useState } from "react";
import { api } from "./api.js";
import { defaultAnalyticsPeriod } from "./events.js";

const percent = new Intl.NumberFormat("ru-RU", { style: "percent", maximumFractionDigits: 1 });
const dateLabel = new Intl.DateTimeFormat("ru-RU", { dateStyle: "short", timeStyle: "short" });
const metrics = [
  ["zero_result_rate", "Поиски без прямых совпадений"],
  ["abandonment_rate", "Ушли после неудачного поиска"],
  ["conversion_rate", "Поисковые сессии с заказом"],
];
const reasons = {
  missing_synonym: "Пропущенный синоним", ranking: "Порядок выдачи", catalog_gap: "Возможный пробел каталога",
  unavailable: "Позиция недоступна", filter_conflict: "Несовместимые фильтры", unknown: "Причина требует проверки",
};

function forInput(iso) {
  const date = new Date(iso);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}

export default function AnalyticsPage() {
  const [period, setPeriod] = useState(() => ({ ...defaultAnalyticsPeriod(new Date(Math.ceil(Date.now() / 60_000) * 60_000)), source: "" }));
  const [draft, setDraft] = useState(() => ({ from: forInput(period.from), to: forInput(period.to), source: period.source, indexVersion: "" }));
  const [report, setReport] = useState(null);
  const [error, setError] = useState("");
  const [invalid, setInvalid] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    async function load() {
      setReport(null);
      setError("");
      try {
        const query = Object.fromEntries(Object.entries(period).filter(([, value]) => Boolean(value)));
        const result = await api(`/v1/analytics?${new URLSearchParams(query)}`, { signal: controller.signal });
        if (!controller.signal.aborted) setReport(result);
      } catch (failure) {
        if (!controller.signal.aborted) setError(failure.message);
      }
    }
    load();
    return () => controller.abort();
  }, [period]);

  function apply(event) {
    event.preventDefault();
    const from = new Date(draft.from);
    const to = new Date(draft.to);
    if (!(from < to)) { setInvalid("Конец периода должен быть позже начала."); return; }
    setInvalid("");
    setPeriod({ from: from.toISOString(), to: to.toISOString(), source: draft.source, index_version: draft.indexVersion.trim() });
  }

  return (
    <main className="container page report-page" tabIndex={-1}>
      <div className="result-heading"><h1>Аналитика поиска</h1></div>
      <form className="report-form" onSubmit={apply}>
        <label className="filter-field">Начало<input type="datetime-local" required value={draft.from} onChange={event => setDraft({ ...draft, from: event.target.value })} /></label>
        <label className="filter-field">Конец периода<input type="datetime-local" required value={draft.to} onChange={event => setDraft({ ...draft, to: event.target.value })} /></label>
        <label className="filter-field">Источник
          <select value={draft.source} onChange={event => setDraft({ ...draft, source: event.target.value })}>
            <option value="">Все источники</option><option value="demo">Посещения стенда</option><option value="seed">Демо-сессии</option>
          </select>
        </label>
        <label className="filter-field">Версия индекса<input value={draft.indexVersion} placeholder="Все версии" onChange={event => setDraft({ ...draft, indexVersion: event.target.value })} /></label>
        <button className="button primary" type="submit">Показать</button>
      </form>
      {invalid && <p role="alert" className="result-state">{invalid}</p>}
      {!report && !error && <p className="result-state" role="status">Загружаем отчёт…</p>}
      {error && <div className="result-state" role="alert"><p>{error}</p><button className="text-button" onClick={() => setPeriod({ ...period })}>Повторить</button></div>}
      {report && <>
        <p className="summary-text report-period">{dateLabel.format(new Date(report.period.from))} — {dateLabel.format(new Date(report.period.to))}</p>
        <div className="metric-grid">
          {metrics.map(([key, label]) => {
            const item = report[key];
            return <article key={key} className="metric-card"><h2>{label}</h2>
              <p className="metric-value">{item.value == null ? "—" : percent.format(item.value)}</p>
              <p className="summary-text">{item.numerator} из {item.denominator}</p></article>;
          })}
        </div>
        <section className="result-group" aria-labelledby="failures-title">
          <h2 id="failures-title">Причины неудачных поисков</h2>
          {report.failures.length ? <div className="report-table"><table>
            <thead><tr><th scope="col">Причина</th><th scope="col">Поисков</th><th scope="col">Примеры</th><th scope="col">Действие</th></tr></thead>
            <tbody>{report.failures.map(row => <tr key={row.reason}><th scope="row">{reasons[row.reason] ?? row.reason}</th><td>{row.count}</td><td>{row.query_examples.join(", ") || "—"}</td><td>{row.action}</td></tr>)}</tbody>
          </table></div> : <p className="result-state">За этот период причины неудач не зарегистрированы.</p>}
        </section>
      </>}
    </main>
  );
}
