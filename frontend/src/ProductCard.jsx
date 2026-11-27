import ProductImage from "./ProductImage";
import { safeLink } from "./api.js";

const licenseTypes = { new: "Новая", renewal: "Продление", upgrade: "Обновление" };

export function priceLabel(amount, currency = "RUB") {
  if (amount == null) return "Цена не указана";
  const format = new Intl.NumberFormat("ru-RU", { style: "currency", currency, minimumFractionDigits: 0 });
  return format.format(amount / 10 ** format.resolvedOptions().maximumFractionDigits);
}

export default function ProductCard({ item, action, explanation }) {
  const attributes = Object.entries(item.attributes ?? {}).map(([key, values]) => [
    key, key === "Тип лицензии" ? values.map(value => licenseTypes[value] ?? value) : values,
  ]);
  const promotion = item.available && item.price_minor > 0 && item.old_price_minor > item.price_minor;

  return (
    <article className="product-card" data-role={item.role}>
      <figure className="product-figure">
        <div className="product-photo"><ProductImage item={item} /></div>
        <figcaption><h3>{item.name}</h3></figcaption>
      </figure>
      <div className="product-top">
        <span className="vendor-name">{item.vendor}</span>
        {item.role === "complement" && <span className="compatibility-tag">Совместимо</span>}
        {item.offer_tier === "reserve" && <span className="compatibility-tag">Резерв</span>}
      </div>
      {item.sku && <p className="sku">Артикул {item.sku}</p>}
      {item.reason_codes?.includes("EXACT_SKU") && <p className="summary-text">Совпадение по артикулу</p>}
      {attributes.length > 0 && <dl className="product-attributes">
        {attributes.slice(0, 3).map(([key, values]) => <div key={key}><dt>{key}</dt><dd>{values.join(", ")}</dd></div>)}
      </dl>}
      {attributes.length > 3 && <details className="proof"><summary>Все характеристики</summary><dl className="product-attributes">
        {attributes.slice(3).map(([key, values]) => <div key={key}><dt>{key}</dt><dd>{values.join(", ")}</dd></div>)}
      </dl></details>}
      {item.differences?.length > 0 && <p className="compatible-with">{item.differences.join(". ")}</p>}
      <div className="product-bottom">
        <div>
          {promotion && <p className="old-price"><del>{priceLabel(item.old_price_minor, item.currency)}</del></p>}
          <p className="price">{priceLabel(item.price_minor, item.currency)}</p>
          <p className={`stock${item.available ? " available" : ""}`}>{item.available ? "В наличии" : "Нет в наличии"}</p>
        </div>
        {action}
      </div>
      {item.checks?.length > 0 && <details className="proof"><summary>Почему подходит</summary>
        <ul>{item.checks.map((check, index) => {
          const source = safeLink(check.source_url);
          return <li key={index}>{check.candidate_value} подходит к {check.anchor_value}. {source && <a href={source} target="_blank" rel="noreferrer">Источник проверки</a>}</li>;
        })}</ul>
      </details>}
      {explanation}
    </article>
  );
}
