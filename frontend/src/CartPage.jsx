import { entityHref } from "./api";
import ProductCard from "./ProductCard";

export default function CartPage({ items, demo, navigate, remove }) {
  const catalogHref = demo ? "/?demo=1" : "/";
  return <main className="container page entity-page" tabIndex={-1}>
    <a className="back-link" href={catalogHref} onClick={event => navigate(event, catalogHref)}>← К поиску</a>
    <div className="result-heading"><h1>Корзина</h1>
      {items.length > 0 && <p className="summary-text">Подборка сохранена в этом браузере. Оформление доступно на странице поставщика.</p>}
    </div>
    {items.length > 0 ? <div className="product-grid" data-size={items.length}>{items.map(({ item, origin }) => {
      const href = entityHref({ kind: "offer", id: item.offer_id }, {}, demo);
      return <ProductCard key={item.offer_id} item={item} action={<div className="offer-actions">
        <a className="button secondary" href={href} onClick={event => navigate(event, href, origin)}>К товару</a>
        <button type="button" className="text-button" aria-label={`Убрать ${item.name}`} onClick={() => remove(item.offer_id)}>Убрать</button>
      </div>} />;
    })}</div> : <p className="result-state">Добавьте подходящие предложения из карточек товаров.</p>}
  </main>;
}
