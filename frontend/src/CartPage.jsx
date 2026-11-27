import { useRef, useState } from "react";
import { entityHref } from "./api";
import ProductCard from "./ProductCard";

export default function CartPage({ items, navigate, remove, clear }) {
  const catalogHref = "/";
  const confirm = useRef(null);
  const [completed, setCompleted] = useState(false);
  return <main className="container page entity-page" tabIndex={-1}>
    <a className="back-link" href={catalogHref} onClick={event => navigate(event, catalogHref)}>← К поиску</a>
    <div className="result-heading"><h1>Корзина</h1></div>
    {completed && <p className="result-state" role="status">Пробный заказ оформлен.</p>}
    {items.length > 0 ? <div className="product-grid" data-size={items.length}>{items.map(({ item, origin }) => {
      const href = entityHref({ kind: "offer", id: item.offer_id });
      return <ProductCard key={item.offer_id} item={item} action={<div className="offer-actions">
        <a className="button secondary card-link" href={href} aria-label={`К товару ${item.name}`}
          onClick={event => navigate(event, href, origin)}>К товару</a>
        <button type="button" className="text-button" aria-label={`Убрать ${item.name}`} onClick={() => remove(item.offer_id)}>Убрать</button>
      </div>} />;
    })}</div> : !completed && <p className="result-state">Добавьте подходящие предложения из карточек товаров.</p>}
    {items.length > 0 && <div className="result-group">
      <button type="button" className="button primary" onClick={() => confirm.current.showModal()}>Оформить пробный заказ</button>
    </div>}
    <dialog className="purchase-dialog" ref={confirm} aria-labelledby="cart-checkout-title">
      <h2 id="cart-checkout-title">Пробный заказ</h2>
      <form method="dialog" className="offer-actions">
        <button type="submit" className="button primary" onClick={() => { clear(); setCompleted(true); }}>Подтвердить</button>
        <button type="submit" className="button secondary">Отмена</button>
      </form>
    </dialog>
  </main>;
}
