import { useEffect, useState } from "react";
import { api } from "./api";
import { priceLabel } from "./ProductCard";

const dateFormat = new Intl.DateTimeFormat("ru-RU", { dateStyle: "short", timeZone: "Europe/Moscow" });

export default function PriceComparison({ offerId }) {
  const [comparison, setComparison] = useState(null);

  useEffect(() => {
    const controller = new AbortController();
    api(`/v1/entities/offer/${encodeURIComponent(offerId)}/price-comparison`, { signal: controller.signal })
      .then(result => {
        if (!controller.signal.aborted) setComparison(result);
      })
      .catch(() => {});
    return () => controller.abort();
  }, [offerId]);

  if (!comparison) return null;
  const { currency, reference_type: type, difference_minor: difference, catalog_date: catalogDate,
    checked_at: checkedAt, source_url: sourceUrl } = comparison;
  const rrp = type === "rrp";
  const amount = priceLabel(Math.abs(difference), currency);
  const change = difference === 0 ? "Цены совпадают"
    : `У нас на ${amount} ${difference > 0 ? "ниже" : "выше"}${rrp ? " РРЦ" : ""}`;

  return <section className="offer-comparison" aria-label="Сравнение цен">
    <h2>Сравнение цен</h2>
    <dl>
      <div><dt>У нас</dt><dd>{priceLabel(comparison.store_price_minor, currency)}</dd></div>
      <div><dt>{rrp ? "РРЦ" : "Цена у вендора"} <a href={sourceUrl} target="_blank" rel="noreferrer">{comparison.vendor}</a></dt>
        <dd>{priceLabel(comparison.vendor_price_minor, currency)}</dd></div>
    </dl>
    <p className={difference > 0 ? "offer-saving" : undefined}>{change}</p>
    <p className="summary-text">Каталог от <time dateTime={catalogDate}>{dateFormat.format(new Date(catalogDate))}</time>
      {" · "}Проверено <time dateTime={checkedAt}>{dateFormat.format(new Date(checkedAt))}</time></p>
  </section>;
}
