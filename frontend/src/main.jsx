import { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import AnalyticsPage from "./AnalyticsPage";
import AssistantChat from "./AssistantChat";
import CartPage from "./CartPage";
import CatalogPage from "./CatalogPage";
import EntityPage from "./EntityPage";
import { entityKinds, eventSession, filtersFromParams } from "./api";
import { readCatalogRoute } from "./catalogRoute";
import { trackEvents } from "./events";
import "./style.css";

const legacyOffers = {
  "/microsoft/ep2-06605-473582/": "473582",
  "/pantum/p2500w-363882/": "363882",
};

function readRoute() {
  const params = new URLSearchParams(window.location.search);
  const legacyId = legacyOffers[window.location.pathname];
  const kind = params.get("kind") ?? (legacyId && "offer");
  const id = params.get("id") ?? legacyId;
  const entity = entityKinds.includes(kind) && id ? { kind, id } : null;
  const view = params.get("view") ?? (window.location.pathname === "/basket/" ? "cart" : null);
  return {
    entity, filters: filtersFromParams(params), origin: window.history.state?.origin,
    catalog: readCatalogRoute(params, window.location.pathname),
    returnTo: window.history.state?.returnTo,
    view: entity ? "entity" : ["analytics", "cart"].includes(view) ? view : "catalog",
    key: window.location.pathname + window.location.search,
  };
}

function loadCart(key) {
  try {
    const items = JSON.parse(localStorage.getItem(key));
    if (Array.isArray(items)) return items.filter(row => typeof row?.item?.offer_id === "string" && typeof row.item.name === "string");
  } catch { /* The cart can stay in memory if storage is blocked. */ }
  return [];
}

function App() {
  const [route, setRoute] = useState(readRoute);
  const cartKey = "softline-cart-v1";
  const [cart, setCart] = useState(() => loadCart(cartKey));
  const routed = useRef(false);
  const keepPosition = useRef(false);
  const saved = new Set(cart.map(({ item }) => item.offer_id));
  const catalogHref = "/";
  const analyticsHref = "/?view=analytics";
  const cartHref = "/?view=cart";

  useEffect(() => {
    function pop() { setRoute(readRoute()); }
    window.addEventListener("popstate", pop);
    return () => window.removeEventListener("popstate", pop);
  }, []);

  useEffect(() => {
    document.title = `${route.view === "analytics" ? "Аналитика поиска" : route.view === "cart" ? "Корзина" : "Каталог"} · Softline Store`;
    if (routed.current && !keepPosition.current) document.querySelector("main:not([hidden])")?.focus({ preventScroll: true });
    routed.current = true;
    keepPosition.current = false;
  }, [route]);

  useEffect(() => {
    try { localStorage.setItem(cartKey, JSON.stringify(cart)); } catch { /* The current cart remains available. */ }
  }, [cartKey, cart]);

  function navigate(event, href, origin = null) {
    if (event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return;
    event.preventDefault();
    go(href, origin);
  }

  function go(href, origin = null, { keepPosition: preserve = false } = {}) {
    if (route.key === href) return;
    const returnTo = route.view === "catalog" ? route.key : route.returnTo;
    keepPosition.current = preserve;
    window.history.pushState({ origin, returnTo }, "", href);
    setRoute(readRoute());
    if (!preserve) window.scrollTo(0, 0);
  }

  function add(item, origin) {
    if (saved.has(item.offer_id)) return;
    const session = eventSession(origin);
    const attributed = origin?.session === session;
    setCart(current => current.some(row => row.item.offer_id === item.offer_id)
      ? current : [...current, { item, origin: attributed ? origin : null }]);
    const events = [];
    if (attributed && origin.familyKey && origin.clickedOfferId !== item.offer_id) events.push({
      type: "click", block: "details", search_id: origin.searchId, offer_id: item.offer_id, family_key: origin.familyKey,
    });
    events.push({ type: "add_to_cart", offer_id: item.offer_id, search_id: attributed ? origin.searchId : null });
    trackEvents(session, events);
  }

  return <>
    <header className="site-header">
      <div className="container header-content">
        <a className="identity" href={catalogHref} onClick={event => navigate(event, catalogHref)}>
          <img src="/softline/d60ae55440858110a234-softline-solutions-digital-asset.svg" alt="Softline Store" width="190" height="40" />
        </a>
        <nav className="site-nav" aria-label="Разделы приложения">
          <a href={catalogHref} onClick={event => navigate(event, catalogHref)} aria-current={route.view === "catalog" ? "page" : undefined}>Каталог</a>
          <a href={analyticsHref} onClick={event => navigate(event, analyticsHref)} aria-current={route.view === "analytics" ? "page" : undefined}>Аналитика</a>
          <a href={cartHref} onClick={event => navigate(event, cartHref)} aria-current={route.view === "cart" ? "page" : undefined}>Корзина{cart.length > 0 && ` · ${cart.length}`}</a>
        </nav>
      </div>
    </header>
    {route.view === "catalog" && <CatalogPage route={route.catalog} go={go} navigate={navigate} />}
    {route.view === "entity" && <EntityPage key={route.key} target={route.entity} filters={route.filters}
      origin={route.origin} navigate={navigate} add={add} saved={saved} returnTo={route.returnTo} />}
    {route.view === "cart" && <CartPage items={cart} navigate={navigate}
      remove={id => setCart(current => current.filter(({ item }) => item.offer_id !== id))} clear={() => setCart([])} />}
    {route.view === "analytics" && <AnalyticsPage />}
    <div hidden={route.view === "analytics"}><AssistantChat navigate={navigate} profileId={route.catalog.profileId} /></div>
  </>;
}

createRoot(document.getElementById("root")).render(<App />);
