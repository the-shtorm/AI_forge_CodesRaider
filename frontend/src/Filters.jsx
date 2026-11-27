import { useId, useState } from "react";

function FacetSelect({ label, value, options, onChange }) {
  const id = useId();
  const known = options.some(option => option.id === value);
  const retained = value && !known;
  return (
    <div className="filter-field">
      <label htmlFor={id}>{label}</label>
      <select id={id} value={value} onChange={event => onChange(event.target.value)} disabled={!options.length && !retained}>
        <option value="">Все</option>
        {retained && <option value={value}>{value}</option>}
        {options.map(option => <option key={option.id} value={option.id}>{option.label} ({option.count})</option>)}
      </select>
    </div>
  );
}

const licenseFields = [
  ["platform", "Платформа", "text"],
  ["version", "Версия", "text"],
  ["device_count", "Устройства", "number"],
  ["seat_count", "Рабочие места", "number"],
  ["term_months", "Срок, мес.", "number"],
];

export default function Filters({ filters, facets, update, reset, busy }) {
  const headingId = useId();
  const panelId = useId();
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState(null);
  const applied = {
    price: filters.max_price_minor == null ? "" : String(filters.max_price_minor / 100),
    ...Object.fromEntries(licenseFields.map(([key]) => [key, String(filters[key] ?? "")])),
  };
  // Immediate facet changes should preserve edits that haven't been applied yet.
  const draftKey = JSON.stringify(applied);
  const fields = draft?.key === draftKey ? draft.fields : applied;
  const activeCount = Object.values(filters).filter(value => value != null && value !== "" && value !== false).length;

  function edit(key, value) {
    setDraft({ key: draftKey, fields: { ...fields, [key]: value } });
  }

  function apply(event) {
    event.preventDefault();
    const { price, platform, version, device_count, seat_count, term_months } = fields;
    setDraft(null);
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
    setDraft(null);
    reset();
  }

  return (
    <aside className={`filters${open ? " filters-open" : ""}`} aria-labelledby={headingId}>
      <div className="filter-heading">
        <h2 id={headingId}>Фильтры</h2>
        {activeCount > 0 && <span className="filter-count"><span className="sr-only">Активных фильтров </span>{activeCount}</span>}
        <div className="filter-actions">
          {activeCount > 0 && <button className="text-button filter-reset" type="button" onClick={clear} disabled={busy}>Сбросить</button>}
          <button className="filter-toggle" type="button" onClick={() => setOpen(value => !value)} aria-expanded={open} aria-controls={panelId}>{open ? "Свернуть" : "Развернуть"}</button>
        </div>
      </div>
      <form className="filter-form" id={panelId} onSubmit={apply}>
        <FacetSelect label="Категория" value={filters.category_id ?? ""} options={facets.categories} onChange={value => update({ category_id: value || undefined })} />
        <FacetSelect label="Производитель" value={filters.vendor_id ?? ""} options={facets.vendors} onChange={value => update({ vendor_id: value || undefined })} />
        <label className="filter-field">
          <span>Цена до, ₽</span>
          <input type="number" min="0" max="1000000000" step="0.01" inputMode="decimal" placeholder="Без ограничения" value={fields.price} onChange={event => edit("price", event.target.value)} />
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
            {licenseFields.map(([key, label, type]) => (
              <label className="filter-field" key={key}>{label}
                <input value={fields[key]} type={type} min={type === "number" ? 1 : undefined}
                  max={type === "number" ? Number.MAX_SAFE_INTEGER : undefined} maxLength={type === "text" ? 100 : undefined}
                  onChange={event => edit(key, event.target.value)} />
              </label>
            ))}
          </div>
        </details>
        <button className="button primary apply-button" type="submit" disabled={busy}>Применить</button>
      </form>
    </aside>
  );
}
