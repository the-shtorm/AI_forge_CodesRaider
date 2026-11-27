import { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import AnalyticsPage from "./AnalyticsPage";
import AssistantChat from "./AssistantChat";
import CartPage from "./CartPage";
import CatalogPage from "./CatalogPage";
import EntityPage from "./EntityPage";
import { entityKinds, eventSession, filtersFromParams } from "./api";
import { trackEvents } from "./events";
import "./style.css";

function readRoute() {
  const params = new URLSearchParams(window.location.search);
  const demo = params.get("demo") === "1";
  const kind = params.get("kind");
  const id = params.get("id");
  const entity = entityKinds.includes(kind) && id ? { kind, id } : null;
  const view = params.get("view");
  return {
    demo, entity, filters: filtersFromParams(params), origin: window.history.state?.origin,
    view: entity ? "entity" : ["analytics", "cart"].includes(view) ? view : "catalog",
    key: window.location.search,
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
  const [profileId, setProfileId] = useState("");
  const cartKey = `softline-cart-v1${route.demo ? "-demo" : ""}`;
  const [cart, setCart] = useState(() => loadCart(cartKey));
  const routed = useRef(false);
  const saved = new Set(cart.map(({ item }) => item.offer_id));
  const catalogHref = route.demo ? "/?demo=1" : "/";
  const analyticsHref = route.demo ? "/?demo=1&view=analytics" : "/?view=analytics";
  const cartHref = route.demo ? "/?demo=1&view=cart" : "/?view=cart";

  useEffect(() => {
    function pop() { setRoute(readRoute()); }
    window.addEventListener("popstate", pop);
    return () => window.removeEventListener("popstate", pop);
  }, []);

  useEffect(() => {
    document.title = `${route.view === "analytics" ? "Аналитика поиска" : route.view === "cart" ? "Корзина" : "Каталог решений"} · CodesRaider`;
    if (routed.current) document.querySelector("main:not([hidden])")?.focus({ preventScroll: true });
    routed.current = true;
  }, [route]);

  useEffect(() => {
    try { localStorage.setItem(cartKey, JSON.stringify(cart)); } catch { /* The current cart remains available. */ }
  }, [cartKey, cart]);

  function navigate(event, href, origin = null) {
    if (event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return;
    event.preventDefault();
    if (`${window.location.pathname}${window.location.search}` === href) return;
    window.history.pushState({ origin }, "", href);
    setRoute(readRoute());
    window.scrollTo(0, 0);
  }

  function add(item, origin) {
    if (saved.has(item.offer_id)) return;
    const session = eventSession(origin);
    const attributed = origin?.session === session;
    setCart(current => [...current, { item, origin: attributed ? origin : null }]);
    const events = [];
    if (attributed && origin.familyKey) events.push({
      type: "click", block: "details", search_id: origin.searchId, offer_id: item.offer_id, family_key: origin.familyKey,
    });
    events.push({ type: "add_to_cart", offer_id: item.offer_id, search_id: attributed ? origin.searchId : null });
    trackEvents(route.demo, session, events);
  }

  return <>
    <header className="site-header">
      <div className="container header-content">
        <a className="identity" href={catalogHref} onClick={event => navigate(event, catalogHref)}><span className="identity-mark" aria-hidden="true" />Каталог решений</a>
        <nav className="site-nav" aria-label="Разделы приложения">
          <a href={analyticsHref} onClick={event => navigate(event, analyticsHref)} aria-current={route.view === "analytics" ? "page" : undefined}>Аналитика</a>
          <a href={cartHref} onClick={event => navigate(event, cartHref)} aria-current={route.view === "cart" ? "page" : undefined}>Корзина{cart.length > 0 && ` · ${cart.length}`}</a>
        </nav>
        {route.demo && <span className="demo-label">Демоданные</span>}
      </div>
    </header>
    <CatalogPage demo={route.demo} active={route.view === "catalog"} navigate={navigate} profileId={profileId} setProfileId={setProfileId} />
    {route.view === "entity" && <EntityPage key={route.key} target={route.entity} filters={route.filters}
      demo={route.demo} origin={route.origin} navigate={navigate} add={add} saved={saved} />}
    {route.view === "cart" && <CartPage items={cart} demo={route.demo} navigate={navigate}
      remove={id => setCart(current => current.filter(({ item }) => item.offer_id !== id))} />}
    {route.view === "analytics" && <AnalyticsPage demo={route.demo} />}
    <div hidden={route.view === "analytics"}><AssistantChat demo={route.demo} navigate={navigate} profileId={profileId} /></div>
  </>;
}

createRoot(document.getElementById("root")).render(<App />);
