import { useState } from "react";

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

const emptyLicense = { platform: "", version: "", device_count: "", seat_count: "", term_months: "" };

export default function Filters({ filters, facets, update, reset, applied, busy }) {
  const [price, setPrice] = useState(() => filters.max_price_minor == null ? "" : String(filters.max_price_minor / 100));
  const [advanced, setAdvanced] = useState(() => Object.fromEntries(
    Object.keys(emptyLicense).map(key => [key, String(filters[key] ?? "")]),
  ));
  const [open, setOpen] = useState(false);
  const active = Object.values(filters).some(Boolean) || filters.max_price_minor === 0;

  function apply(event) {
    event.preventDefault();
    const { platform, version, device_count, seat_count, term_months } = advanced;
    update({
      platform: platform.trim() || undefined,
      version: version.trim() || undefined,
      device_count: device_count ? Number(device_count) : undefined,
      seat_count: seat_count ? Number(seat_count) : undefined,
      term_months: term_months ? Number(term_months) : undefined,
      max_price_minor: price === "" ? undefined : Math.round(Number(price) * 100),
    });
  }

  function clear() {
    setPrice("");
    setAdvanced(emptyLicense);
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
        {applied && <p className="summary-text">Учтены условия из запроса и выбранной подсказки.</p>}
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
            {[["platform", "Платформа"], ["version", "Версия"], ["device_count", "Устройства"], ["seat_count", "Рабочие места"], ["term_months", "Срок, мес."]].map(([key, label], index) => (
              <label className="filter-field" key={key}>{label}
                <input value={advanced[key]} type={index < 2 ? "text" : "number"} min={index < 2 ? undefined : 1}
                  max={index < 2 ? undefined : Number.MAX_SAFE_INTEGER} maxLength={index < 2 ? 100 : undefined} onChange={event => setAdvanced({ ...advanced, [key]: event.target.value })} />
              </label>
            ))}
          </div>
        </details>
        <button className="button secondary apply-button" type="submit" disabled={busy}>Применить</button>
      </form>
    </aside>
  );
}
