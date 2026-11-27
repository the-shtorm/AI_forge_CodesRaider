import { useId, useRef, useState } from "react";
import { formatOfferAttribute, priceLabel } from "./ProductCard";
import ProductImage from "./ProductImage";
import PriceComparison from "./PriceComparison";

function plainDescription(value) {
  if (!value) return "";
  if (!value.includes("<") && !value.includes("&")) return value.trim();
  const template = document.createElement("template");
  template.innerHTML = value;
  for (const node of template.content.querySelectorAll("script, style, template, iframe, noscript")) node.remove();
  for (const lineBreak of template.content.querySelectorAll("br")) lineBreak.replaceWith("\n");
  for (const paragraph of template.content.querySelectorAll("p, li, div")) paragraph.append("\n");
  return template.content.textContent.trim();
}

export default function OfferDetail({ item, saved, add }) {
  const dialog = useRef(null);
  const dialogTitle = useId();
  const [confirmed, setConfirmed] = useState(false);
  const description = plainDescription(item.description);
  const attributes = Object.entries(item.attributes ?? {});
  const price = priceLabel(item.price_minor, item.currency);
  const canBuy = item.available && item.price_minor > 0;
  const oldPrice = canBuy && item.old_price_minor > item.price_minor;

  return <article className="offer-detail">
    <div className="product-photo offer-photo"><ProductImage eager /></div>
    <div className="offer-content">
      <div className="result-heading">
        <p className="vendor-name">{item.vendor}{item.sku && ` · Артикул ${item.sku}`}</p>
        <h1>{item.name}</h1>
      </div>
      <div className="offer-price">
        {oldPrice && <p className="old-price"><del>{priceLabel(item.old_price_minor, item.currency)}</del></p>}
        <p className="price">{price}</p>
        <p className={`stock${item.available ? " available" : ""}`}>{item.available ? "В наличии" : "Нет в наличии"}</p>
      </div>
      <div className="offer-actions">
        <button type="button" className="button primary" onClick={() => {
          setConfirmed(false);
          dialog.current.showModal();
        }} disabled={!canBuy}>Купить</button>
        {canBuy && <button type="button" className="button secondary" onClick={add} disabled={saved}>
          {saved ? "В корзине" : "В корзину"}
        </button>}
      </div>
      {canBuy && item.currency === "RUB" && item.sku && <PriceComparison key={item.offer_id} offerId={item.offer_id} />}
      <section className="offer-description">
        <h2>Описание</h2>
        <p>{description || "Описание отсутствует в каталоге."}</p>
      </section>
      {attributes.length > 0 && <details className="offer-specifications">
        <summary>Характеристики</summary>
        <dl className="product-attributes">{attributes.map(([key, values]) =>
          <div key={key}><dt>{key}</dt><dd>{formatOfferAttribute(key, values)}</dd></div>)}</dl>
      </details>}
    </div>

    <dialog className="purchase-dialog" ref={dialog} aria-labelledby={dialogTitle}>
      <h2 id={dialogTitle}>Пробная покупка</h2>
      {confirmed ? <p role="status">Пробный заказ оформлен.</p> : <>
        <p>{item.name}</p>
        <p className="price">{price}</p>
      </>}
      <div className="offer-actions">
        {!confirmed && <button type="button" className="button primary" onClick={() => setConfirmed(true)}>Оформить пробный заказ</button>}
        <form method="dialog">
          <button className={`button ${confirmed ? "primary" : "secondary"}`}>{confirmed ? "Закрыть" : "Отмена"}</button>
        </form>
      </div>
    </dialog>
  </article>;
}
