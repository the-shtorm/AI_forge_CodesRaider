const active = "is-active";
const one = (selector, root = document) => root.querySelector(selector);
const all = (selector, root = document) => [...root.querySelectorAll(selector)];

function setActive(element, enabled) {
  element?.classList.toggle(active, enabled);
}

function closePanels() {
  for (const panel of all(".js-catalog.is-active, .js-catalog-btn.is-active, .js-city-dropdown.is-active, .js-popup-modal-menu.is-active, .js-dropdown-menu.is-active, .b-modal.is-active")) {
    setActive(panel, false);
  }
}

function carousel(container) {
  const slides = all(".b-main-slider__slide", container);
  if (!slides.length) return;
  const wrapper = container.closest(".b-main-slider");
  const clip = document.createElement("div");
  clip.className = "owl-stage-outer";
  const stage = document.createElement("div");
  stage.className = "owl-stage storefront-banner-stage";
  stage.append(...slides.map((slide) => {
    const item = document.createElement("div");
    item.className = "owl-item";
    item.append(slide);
    return item;
  }));
  clip.append(stage);
  container.replaceChildren(clip);
  container.classList.add("owl-loaded");
  let index = 0;
  let timer;

  const items = [...stage.children];

  function show(position) {
    index = (position + slides.length) % slides.length;
    const next = (index + 1) % slides.length;
    for (const [i, item] of items.entries()) {
      const visible = i === index || i === next;
      item.hidden = !visible;
      item.classList.toggle("active", visible);
    }
    stage.prepend(items[index], items[next]);
    window.clearTimeout(timer);
    if (!matchMedia("(prefers-reduced-motion: reduce)").matches) {
      const delay = Number(slides[index].dataset.autoplaytimeout) || 6000;
      timer = window.setTimeout(() => show(index + 1), delay);
    }
  }

  one(".js-slide-backward", wrapper)?.addEventListener("click", () => show(index - 1));
  one(".js-slide-forward", wrapper)?.addEventListener("click", () => show(index + 1));
  show(0);
}

for (const slider of all(".b-main-slider__slider")) carousel(slider);

for (const list of all("main .owl-carousel:not(.owl-loaded)")) {
  if (!list.children.length) continue;
  const stage = document.createElement("div");
  stage.className = "owl-stage storefront-product-stage";
  for (const content of [...list.children]) {
    const item = document.createElement("div");
    item.className = "owl-item active";
    item.append(content);
    stage.append(item);
  }
  const clip = document.createElement("div");
  clip.className = "owl-stage-outer";
  clip.append(stage);
  list.append(clip);
  list.classList.add("owl-loaded");

  const section = list.parentElement;
  for (const [selector, direction] of [[".js-slide-backward", -1], [".js-slide-forward", 1]]) {
    one(selector, section)?.addEventListener("click", () => {
      clip.scrollBy({ left: direction * (stage.firstElementChild?.offsetWidth ?? 220), behavior: "smooth" });
    });
  }
}

function selectCategory(trigger) {
  const menu = trigger.closest(".js-catalog");
  if (!menu) return;
  for (const item of all("[data-catalog-trigger]", menu)) setActive(item, item === trigger);
  for (const panel of all("[data-catalog-section]", menu)) {
    setActive(panel, panel.dataset.catalogSection === trigger.dataset.catalogTrigger);
  }
}

for (const trigger of all(".js-catalog [data-catalog-trigger]")) {
  trigger.addEventListener("pointerenter", () => selectCategory(trigger));
  trigger.addEventListener("focus", () => selectCategory(trigger));
}

for (const form of all("form[data-storefront-local]")) {
  form.addEventListener("submit", (event) => event.preventDefault());
}

document.addEventListener("click", (event) => {
  const target = event.target;
  const catalog = target.closest(".js-catalog-btn");
  const manufacturer = target.closest(".js-main-menu-item[href='#']");
  const city = target.closest(".b-geo-select__city-btn");
  const popup = target.closest("[data-popup-trigger]");
  const close = target.closest(".js-close-modal, .js-close-window, .js-modal-overlay");
  const tab = target.closest("button.js-tab[data-tab], button.js-short-tab[data-tab]");
  const group = target.closest(".js-filter-button-container");

  if (catalog) {
    const open = !catalog.classList.contains(active);
    closePanels();
    setActive(catalog, open);
    setActive(one(".js-catalog", catalog.parentElement), open);
    catalog.setAttribute("aria-expanded", String(open));
  } else if (manufacturer) {
    const menu = one(".js-dropdown-menu", manufacturer.parentElement);
    const open = menu && !menu.classList.contains(active);
    closePanels();
    setActive(menu, open);
    manufacturer.setAttribute("aria-expanded", String(open));
  } else if (city) {
    const dropdown = one(".js-city-dropdown", city.closest(".b-geo-select"));
    const open = dropdown && !dropdown.classList.contains(active);
    closePanels();
    setActive(dropdown, open);
    if (open) one('input[name="query"]', dropdown)?.focus();
  } else if (close) {
    closePanels();
  } else if (tab) {
    const key = tab.dataset.tab;
    for (const button of all(".js-main-tab")) setActive(button, button.dataset.tab === key);
    for (const content of all(".js-tab-content")) setActive(content, content.dataset.tab === key);
  } else if (group) {
    const panel = group.closest(".js-filter-container");
    const open = panel && !panel.classList.contains(active);
    setActive(panel, open);
    setActive(one(".js-filter-button", group), open);
  } else if (popup) {
    const key = popup.dataset.popupTrigger;
    const panel = document.getElementById(`popup-modal-menu-${key}`)
      ?? document.getElementById(`side-${key}-menu`)
      ?? all("[data-popup-modal]").find((element) => element.dataset.popupModal === key);
    if (panel) {
      event.preventDefault();
      closePanels();
      setActive(panel.closest(".b-modal") ?? panel, true);
      one(".js-search-input-mobile", panel)?.focus();
    }
  } else if (!target.closest(".js-catalog, .b-geo-select, .b-mobile-popup, .js-dropdown-menu, .b-modal__content")) {
    closePanels();
  }
  if (target.closest('a[href="#"]')) event.preventDefault();
});

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") closePanels();
});

const chatAnchor = one(".webim_button");
if (chatAnchor) mountAssistant(chatAnchor);

function mountAssistant(anchor) {
  const launcher = document.createElement("button");
  launcher.type = "button";
  launcher.className = "webim-html-button-main storefront-chat-launcher";
  launcher.setAttribute("aria-label", "Помощник Softline");
  launcher.innerHTML = `<svg viewBox="0 0 34 32" width="25" height="25" aria-hidden="true" fill="currentColor">
    <path d="M25.7 0h-17.5c-4.5 0-8.2 3.7-8.2 8.2v21.6c0 0.8 0.5 1.6 1.2 2 0.3 0.2 0.7 0.3 1 0.3 0.4 0 0.8-0.1 1.1-0.3l5.8-2.9h16.6c4.5 0 8.2-3.7 8.2-8.2v-12.5c0.1-4.5-3.6-8.2-8.2-8.2zM25.2 22.3h-8.5c-1 0-1.8-0.8-1.8-1.8s0.8-1.8 1.8-1.8h8.5c1 0 1.8 0.8 1.8 1.8s-0.8 1.8-1.8 1.8zM25.2 16.1h-16.4c-1 0-1.8-0.8-1.8-1.8s0.8-1.8 1.8-1.8h16.4c1 0 1.8 0.8 1.8 1.8s-0.8 1.8-1.8 1.8zM25.2 9.9h-16.4c-1 0-1.8-0.8-1.8-1.8s0.8-1.8 1.8-1.8h16.4c1 0 1.8 0.8 1.8 1.8s-0.8 1.8-1.8 1.8z"></path>
  </svg>`;
  const dialog = document.createElement("dialog");
  dialog.className = "webim-chat storefront-chat";
  dialog.setAttribute("aria-label", "Помощник Softline");
  dialog.innerHTML = `
    <div class="webim-top-header">
      <div><strong>Помощник Softline</strong><span>Менеджер Store</span></div>
      <button type="button" aria-label="История чатов" aria-expanded="false">⋮</button>
      <button type="button" aria-label="Закрыть">×</button>
    </div>
    <div class="storefront-chat-history" hidden></div>
    <ul class="webim-messages" aria-live="polite"></ul>
    <form class="webim-dialogue-message-block">
      <textarea name="message" aria-label="Сообщение" placeholder="Сообщение" maxlength="500" required></textarea>
      <button type="submit" aria-label="Отправить">
        <svg viewBox="0 0 15 15" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor">
          <path d="m.58 5.51 13.75-5-4.84 13.91-3.74-3.5 4.35-5.85-6.02 4.18z"></path>
        </svg>
      </button>
    </form>`;
  anchor.replaceWith(launcher, dialog);
  const messages = one(".webim-messages", dialog);
  const history = one(".storefront-chat-history", dialog);
  const historyButton = one('button[aria-label="История чатов"]', dialog);
  const form = one("form", dialog);
  const input = form.elements.message;
  const submit = one('button[type="submit"]', form);
  const storageKey = "softline-storefront-chats";
  const lifetime = 7 * 24 * 60 * 60 * 1000;
  let chats = [];
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey) ?? "[]");
    if (Array.isArray(saved)) chats = saved
      .filter((item) => item.updated > Date.now() - lifetime && Array.isArray(item.messages))
      .slice(0, 3);
  } catch {
    // The widget stays available when browser storage is disabled.
  }
  const newChat = () => ({
    id: Array.from(crypto.getRandomValues(new Uint32Array(4)), (value) => value.toString(16)).join("-"),
    updated: Date.now(),
    messages: [],
  });
  let chat = chats[0] ?? newChat();

  function persist() {
    chat.updated = Date.now();
    chats = [chat, ...chats.filter(({ id, updated }) => id !== chat.id && updated > Date.now() - lifetime)].slice(0, 3);
    try {
      localStorage.setItem(storageKey, JSON.stringify(chats));
    } catch {
      // The active conversation remains in memory.
    }
  }

  function showChat() {
    history.hidden = true;
    historyButton.setAttribute("aria-expanded", "false");
    messages.replaceChildren();
    for (const { role, text } of [{ role: "assistant", text: "Здравствуйте! Чем вам помочь?" }, ...chat.messages]) {
      const item = document.createElement("li");
      item.className = `webim-message storefront-chat-${role}`;
      item.textContent = text;
      messages.append(item);
    }
    messages.scrollTop = messages.scrollHeight;
  }

  launcher.addEventListener("click", () => {
    dialog.show();
    launcher.hidden = true;
    showChat();
    input.focus();
  });
  one('button[aria-label="Закрыть"]', dialog).addEventListener("click", () => dialog.close());
  dialog.addEventListener("close", () => {
    launcher.hidden = false;
    launcher.focus();
  });
  dialog.addEventListener("keydown", (event) => {
    if (event.key === "Escape") dialog.close();
  });
  historyButton.addEventListener("click", () => {
    if (submit.disabled) return;
    if (!history.hidden) {
      showChat();
      return;
    }
    history.replaceChildren();
    for (const entry of [null, ...chats]) {
      const item = document.createElement("button");
      item.type = "button";
      item.textContent = entry?.messages[0]?.text ?? "Новый чат";
      item.addEventListener("click", () => {
        chat = entry ?? newChat();
        showChat();
        input.focus();
      });
      history.append(item);
    }
    history.hidden = false;
    historyButton.setAttribute("aria-expanded", "true");
  });
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const q = input.value.trim();
    if (!q || submit.disabled) return;
    const assistant_history = chat.messages.slice(-8);
    chat.messages.push({ role: "user", text: q });
    input.value = "";
    submit.disabled = true;
    persist();
    showChat();
    try {
      const result = await fetch("/v1/search", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ q, assistant_history, session_id: chat.id, strategy: "llm" }),
      });
      if (!result.ok) throw new Error(`Search HTTP ${result.status}`);
      const { assistant_message } = await result.json();
      if (!assistant_message) throw new Error("Missing assistant message");
      chat.messages.push({ role: "assistant", text: assistant_message });
    } catch {
      chat.messages.push({ role: "assistant", text: "Поиск временно недоступен" });
    }
    persist();
    showChat();
    submit.disabled = false;
    if (dialog.open) input.focus();
  });
}

for (const dropdown of all(".js-city-dropdown")) {
  one('input[name="query"]', dropdown)?.addEventListener("input", (event) => {
    const query = event.target.value.toLocaleLowerCase("ru").trim();
    for (const city of all(".b-geo-select__item", dropdown)) {
      city.hidden = !city.textContent.toLocaleLowerCase("ru").includes(query);
    }
  });
  dropdown.addEventListener("change", (event) => {
    const city = event.target.closest('input[name="city"]');
    const name = city?.closest("label")?.querySelector(".b-geo-select__name")?.textContent.trim();
    if (!name) return;
    for (const label of all(".b-geo-select__city-current")) label.textContent = name;
    closePanels();
  });
}

function storefrontLink(value) {
  const url = new URL(value, "https://store.softline.ru");
  if (!["http:", "https:"].includes(url.protocol)) return "#";
  const local = (document.body.dataset.storefrontRoutes || "").split(" ");
  return url.hostname === "store.softline.ru" && local.includes(url.pathname)
    ? url.pathname + url.search + url.hash
    : url.href;
}

for (const input of all('.js-search-form input[name="query"], .js-mobile-search-form input[name="query"]')) {
  const form = input.form;
  const clear = one(".js-search-clear", form);
  const output = one(".js-search-prediction-result", form.closest(".js-search") ?? form.parentElement);
  if (form.getAttribute("action") !== "/search/") continue;
  let pending;
  let controller;

  function clearSuggestions() {
    window.clearTimeout(pending);
    controller?.abort();
    setActive(output, false);
    output?.replaceChildren();
  }

  function updateSuggestions() {
    clearSuggestions();
    const q = input.value.trim();
    setActive(clear, q.length > 0);
    if (!output || q.length < 2) return;
    pending = window.setTimeout(async () => {
      controller = new AbortController();
      try {
        const response = await fetch(`/v1/suggest?q=${encodeURIComponent(q)}`, { signal: controller.signal });
        if (!response.ok) return;
        const suggestions = await response.json();
        for (const suggestion of suggestions) {
          const url = new URL("/search/", location.origin);
          url.search = new URLSearchParams({ query: q, kind: suggestion.kind, target_id: suggestion.target_id });
          const link = document.createElement("a");
          link.href = url.pathname + url.search;
          link.className = "b-suggests__item b-suggests__link";
          link.textContent = suggestion.label;
          output.append(link);
        }
        setActive(output, suggestions.length > 0);
      } catch (error) {
        if (error.name !== "AbortError") clearSuggestions();
      }
    }, 180);
  }

  input.addEventListener("input", updateSuggestions);
  clear?.addEventListener("click", () => {
    input.value = "";
    updateSuggestions();
    input.focus();
  });
  document.addEventListener("click", (event) => {
    if (!event.target.closest(".js-search, .js-mobile-search-form")) clearSuggestions();
  });
  setActive(clear, !!input.value);
}

function selectProductView() {
  const products = one("#product_list_container");
  if (!products) return;
  const grid = one("#button-grid-view");
  const line = one("#button-line-view");
  function change(list) {
    products.classList.toggle("b-catalog-grid", !list);
    products.classList.toggle("b-catalog-list", list);
    setActive(grid, !list);
    setActive(line, list);
  }
  grid?.addEventListener("click", () => change(false));
  line?.addEventListener("click", () => change(true));
}

selectProductView();

const searchPage = one(".b-search__title");
if (["/search/", "/search/index.html"].includes(location.pathname) && searchPage) {
  const params = new URLSearchParams(location.search);
  const q = params.get("query")?.trim() ?? "";
  if (q) void loadSearch(q, params, searchPage);
}

async function loadSearch(q, params, heading) {
  const products = one("#product_list_container");
  const template = one(".b-catalog-grid__item", products).cloneNode(true);
  const title = heading.textContent.replace(/«[^»]*»/, `«${q}»`);
  const count = one(".js-count-results-products");
  const filters = one("#filters_container");
  const reset = one(".js-remove-all-filters", filters);
  const filterTemplates = all(".js-filter-container", filters).slice(0, 2).map((node) => node.cloneNode(true));
  const sort = all(".js-sort-select");
  const fields = ["category_id", "vendor_id"];
  const filterState = {};
  let request;

  for (const directory of ["vendor", "category", "publication"]) {
    one(`#${directory}-list-container`)?.replaceChildren();
    one(`#${directory}-paging-container`)?.replaceChildren();
  }
  for (const tab of all(".b-search__tabs .js-main-tab:not([data-tab='products'])")) {
    tab.disabled = true;
    one(".b-product-tabs__tab-text_count", tab).textContent = "0";
  }
  for (const input of all('form[action="/search/"] input[name="query"]')) input.value = q;
  one("#product-paging-container .b-page-product-list__pagination")?.remove();
  for (const panel of all(".js-filter-container", filters)) panel.remove();
  products.replaceChildren();

  function drawFilters(facets) {
    reset.style.display = Object.keys(filterState).length ? "" : "none";
    for (const panel of all(".js-filter-container", filters)) panel.remove();
    for (const [index, options] of [facets?.categories ?? [], facets?.vendors ?? []].entries()) {
      const panel = filterTemplates[index]?.cloneNode(true);
      const list = panel && one(".js-checkbox-filter", panel);
      const item = list && one(".js-checkbox-filter-item", list);
      if (!item || !options.length) continue;
      one(".js-filter-btn", panel).textContent = index === 0 ? "Категория" : "Производитель";
      list.replaceChildren(...options.map((option) => {
        const row = item.cloneNode(true);
        const input = one("input", row);
        input.removeAttribute("id");
        one("label", row)?.removeAttribute("for");
        input.dataset.searchFilter = fields[index];
        input.value = option.id;
        input.checked = filterState[fields[index]] === option.id;
        one(".js-checkbox-label", row).textContent = option.label;
        return row;
      }));
      all(".js-search-in-filter", panel).forEach((input) => input.closest(".b-catalog-filter__search")?.remove());
      filters.append(panel);
    }
  }

  async function update() {
    request?.abort();
    request = new AbortController();
    const selected = params.get("kind") && params.get("target_id")
      ? { kind: params.get("kind"), target_id: params.get("target_id") } : undefined;
    products.setAttribute("aria-busy", "true");
    try {
      const response = await fetch("/v1/search", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          q, limit: 24, strategy: "standard", sort: sort[0]?.value ?? "relevance",
          filters: filterState, selected_target: selected,
        }),
        signal: request.signal,
      });
      if (!response.ok) throw new Error(`Search HTTP ${response.status}`);
      const result = await response.json();
      count.textContent = result.direct_count;
      heading.textContent = result.direct_count ? title : `По запросу «${q}» ничего не найдено`;
      const cards = [...result.top4, ...result.more_results].map((card) => storefrontCard(template, card));
      products.replaceChildren();
      for (let i = 0; i < cards.length; i += 3) {
        const row = document.createElement("div");
        row.className = "b-catalog-grid__row";
        row.append(...cards.slice(i, i + 3));
        products.append(row);
      }
      drawFilters(result.facets);
    } catch (error) {
      if (error.name === "AbortError") return;
      heading.textContent = "Поиск временно недоступен";
      count.textContent = "0";
      products.replaceChildren();
    }
    products.removeAttribute("aria-busy");
  }

  for (const select of sort) {
    select.addEventListener("change", () => {
      for (const other of sort) other.value = select.value;
      void update();
    });
  }
  filters?.addEventListener("change", (event) => {
    const input = event.target;
    const name = input.dataset.searchFilter;
    if (!name) return;
    if (input.checked) filterState[name] = input.value;
    else delete filterState[name];
    void update();
  });
  reset.addEventListener("click", (event) => {
    event.preventDefault();
    for (const filter of fields) delete filterState[filter];
    void update();
  });
  await update();
}

function storefrontCard(template, card) {
  const result = template.cloneNode(true);
  const link = storefrontLink(card.url);
  for (const node of all(".b-product-item__actions, .b-product-item__info-row, .b-product-item__specs, .b-product-item__delivery, .b-product-item__in-stock, .b-quantity, meta, link, source", result)) {
    node.remove();
  }
  for (const anchor of all("a", result)) anchor.href = link;
  const title = one(".b-product-item__description", result);
  title.textContent = card.name;
  title.title = card.name;
  one(".b-product-item__vendor", result).textContent = card.vendor;
  const image = one(".b-product-item__image", result);
  image.removeAttribute("srcset");
  image.removeAttribute("width");
  image.removeAttribute("height");
  image.alt = card.name;
  if (card.image_url) image.src = storefrontLink(card.image_url);
  else image.remove();
  const price = one(".b-product-item__current-price", result);
  price.textContent = card.price_minor == null ? "" : new Intl.NumberFormat("ru-RU", {
    style: "currency", currency: card.currency, maximumFractionDigits: card.price_minor % 100 === 0 ? 0 : 2,
  }).format(card.price_minor / 100);
  const buy = one(".js-add-to-cart", result);
  if (buy) {
    const action = document.createElement("a");
    action.href = link;
    action.className = buy.className;
    action.textContent = buy.textContent.trim();
    buy.replaceWith(action);
  }
  return result;
}
