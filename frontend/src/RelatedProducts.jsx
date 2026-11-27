import { useId } from "react";
import { cardTarget, entityHref, targetLabels } from "./api";
import { priceLabel } from "./ProductCard";
import ProductImage from "./ProductImage";

export default function RelatedProducts({ heading = "С этим товаром ещё покупают", items, filters, onVisit }) {
  const title = useId();
  if (!items.length) return null;
  return <section className="result-group" aria-labelledby={title}>
    <h2 id={title}>{heading}</h2>
    <div className="related-grid">
      {items.map(item => {
        const target = cardTarget(item);
        return <a className="product-card related-product" key={item.offer_id} href={entityHref(target, filters)}
          data-role={item.role} data-offer-id={item.offer_id}
          onClick={event => onVisit(event, target, item)}>
          <div className="product-photo"><ProductImage /></div>
          <h3>{item.name}</h3>
          {item.result_kind && <span className="compatibility-tag">{targetLabels[item.result_kind]}</span>}
          {item.role === "complement" && <span className="compatibility-tag">Совместимо</span>}
          {item.result_kind === "offer" && (item.sku || item.version) && <p className="sku">
            {[item.sku && `Артикул ${item.sku}`, item.version && `Версия ${item.version}`].filter(Boolean).join(" · ")}
          </p>}
          {item.recommendation_reason && <p className="summary-text">{item.recommendation_reason}</p>}
          <p className="price">{priceLabel(item.price_minor, item.currency)}</p>
        </a>;
      })}
    </div>
  </section>;
}
