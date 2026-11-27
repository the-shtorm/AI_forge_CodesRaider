import { useEffect, useState } from "react";
import { entityHref, eventSession, getEntity, safeLink, targetLabels } from "./api";
import { trackEvents } from "./events";
import OfferDetail from "./OfferDetail";
import ProductCard from "./ProductCard";
import RelatedProducts from "./RelatedProducts";

function childLabel(parent, child) {
  if (parent.kind === "offer" && child.kind === "family") return "Посмотреть варианты";
  const path = safeLink(child.url)?.split("?")[0];
  const slug = path?.split("/").filter(Boolean).at(-1)?.replace(/-\d+$/, "").replaceAll("-", " ");
  return child.label ?? (slug && !/^\d+$/.test(slug) ? slug : `${targetLabels[child.kind]} ${child.id}`);
}

export default function EntityPage({ target, filters, origin, navigate, add, saved, returnTo = "/" }) {
  const [entity, setEntity] = useState(null);
  const [error, setError] = useState("");
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    async function load() {
      try {
        const payload = await getEntity(target, filters, controller.signal);
        if (!controller.signal.aborted) setEntity(payload);
      } catch (failure) {
        if (!controller.signal.aborted) setError(failure.message);
      }
    }
    load();
    return () => controller.abort();
  }, [attempt, filters, target]);

  function visitChild(event, child, selectedFilters = filters) {
    const session = eventSession(origin);
    const attributed = origin?.session === session;
    const sameFamily = (entity.kind === "offer" && child.kind === "family") || (entity.kind === "family" && child.kind === "offer");
    if (attributed && sameFamily && child.kind === "offer" && origin.familyKey) {
      trackEvents(session, [
        { type: "click", block: "details", search_id: origin.searchId, offer_id: child.id, family_key: origin.familyKey },
      ]);
    }
    navigate(event, entityHref(child, selectedFilters), attributed && sameFamily ? origin : null);
  }

  const offer = entity?.kind === "offer" ? entity.offers[0] : undefined;
  return (
    <main className="container page entity-page" tabIndex={-1}>
      <a href={returnTo} className="back-link" onClick={event => navigate(event, returnTo)}>← К каталогу</a>
      {!entity && !error && <p role="status" className="result-state">Загружаем карточку…</p>}
      {error && <div role="alert" className="result-state"><p>{error}</p>
        <button type="button" className="text-button" onClick={() => { setError(""); setAttempt(attempt + 1); }}>Повторить</button>
      </div>}
      {entity && <>
        {offer
          ? <OfferDetail item={offer} saved={saved.has(offer.offer_id)} add={() => add(offer, origin)} />
          : <div className="result-heading"><p className="summary-text">{targetLabels[entity.kind]}</p><h1>{entity.name}</h1></div>}
        {entity.kind === "family" && entity.offers.length > 0 && <section className="result-group" aria-labelledby="offers-title">
          <h2 id="offers-title">Варианты</h2>
          <div className="product-grid" data-size={entity.offers.length}>
            {entity.offers.map(item => {
              const child = { kind: "offer", id: item.offer_id };
              const canAdd = item.available && item.price_minor != null;
              return <ProductCard key={item.offer_id} item={item} action={<div className="offer-actions">
                <a className="button secondary" href={entityHref(child, filters)} onClick={event => visitChild(event, child)}>К товару</a>
                {canAdd && <button type="button" className="button primary" disabled={saved.has(item.offer_id)} onClick={() => add(item, origin)}>
                  {saved.has(item.offer_id) ? "В корзине" : "Добавить в корзину"}
                </button>}
              </div>} />;
            })}
          </div>
        </section>}
        {entity.children.length > 0 && <section className="result-group" aria-labelledby="children-title">
          <h2 id="children-title">{entity.kind === "offer" ? "Варианты и разделы" : "В этом разделе"}</h2>
          <ul className="facet-results">{entity.children.map(child => <li key={`${child.kind}-${child.id}`}>
            <a href={entityHref(child, filters)} onClick={event => visitChild(event, child)}>
              <span>{childLabel(entity, child)}</span><span>{targetLabels[child.kind]}</span>
            </a>
          </li>)}</ul>
        </section>}
        {!entity.offers.length && !entity.children.length && <p role="status" className="result-state">По этим условиям предложений нет. Вернитесь к поиску, чтобы изменить фильтры.</p>}
        {entity.kind === "offer" && <RelatedProducts items={entity.related_offers ?? []}
          onVisit={(event, target) => visitChild(event, target, {})} />}
      </>}
    </main>
  );
}
