import { useId } from "react";
import { entityHref } from "./api";
import { priceLabel } from "./ProductCard";
import ProductImage from "./ProductImage";

export default function RelatedProducts({ items, demo, filters, onVisit }) {
  const title = useId();
  if (!items.length) return null;
  return <section className="result-group" aria-labelledby={title}>
    <h2 id={title}>С этим товаром ещё покупают</h2>
    <div className="related-grid">
      {items.slice(0, 4).map(item => {
        const target = { kind: "offer", id: item.offer_id };
        return <a className="product-card related-product" key={item.offer_id} href={entityHref(target, filters, demo)}
          onClick={event => onVisit(event, target)}>
          <div className="product-photo"><ProductImage item={item} fallback={null} /></div>
          <h3>{item.name}</h3>
          <p className="price">{priceLabel(item.price_minor, item.currency)}</p>
        </a>;
      })}
    </div>
  </section>;
}
