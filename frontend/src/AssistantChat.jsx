import { useEffect, useRef, useState } from "react";
import { recordEvent, useCardImpression } from "./searchEvents";
import ProductImage from "./ProductImage";
import { demoAssistant, requestAssistant } from "./assistant";
import { safeLink } from "./catalog";
import { appendMessage, loadChats, pruneChats, saveChats } from "./chatHistory";

const greeting = "Расскажите, для чего нужна программа. Помогу подобрать ПО под вашу задачу и бюджет.";
const suggestions = ["Офис без подписки", "Как восстановить файлы после сбоя?"];
const chatDate = new Intl.DateTimeFormat("ru-RU", { day: "numeric", month: "short" });

function AssistantProduct({ item, sessionId, searchId, position }) {
  const url = safeLink(item.url);
  const source = safeLink(item.source);
  // Older saved conversations contain only direct products and no block coordinates.
  const { element, click } = useCardImpression(sessionId, searchId, { ...item, role: item.role ?? "direct" },
    item.block ?? "top4", item.position ?? position);
  return <li ref={element}><div className="assistant-product-image"><ProductImage item={item} /></div>
    <div>{url ? <a onClick={click} href={url} target="_blank" rel="noreferrer">{item.name}</a> : <span>{item.name}</span>}
      {item.role === "complement" && <p>Подходит к {item.anchorName}. {source && <a href={source} target="_blank" rel="noreferrer">Источник проверки</a>}</p>}
      {item.role === "related" && <p>{item.recommendation_reason}</p>}
    </div>
  </li>;
}

export default function AssistantChat({ demo, profileId }) {
  const storageKey = `softline-assistant-chats-v1${demo ? "-demo" : ""}`;
  const [chats, setChats] = useState(() => loadChats(storageKey));
  const [activeId, setActiveId] = useState(chats[0]?.id ?? null);
  const [expanded, setExpanded] = useState(false);
  const [draft, setDraft] = useState("");
  const [pending, setPending] = useState(false);
  const request = useRef(null);
  const conversation = useRef(null);
  const input = useRef(null);
  const activeChat = chats.find(chat => chat.id === activeId);
  const messages = activeChat?.messages ?? [];

  useEffect(() => () => request.current?.abort(), []);
  useEffect(() => { saveChats(storageKey, chats); }, [chats, storageKey]);
  useEffect(() => {
    function clearExpired() {
      setChats(current => {
        const fresh = pruneChats(current);
        return fresh.length === current.length ? current : fresh;
      });
    }
    function syncTabs(event) {
      if (event.key === storageKey || event.key === null) setChats(loadChats(storageKey));
    }
    const timer = window.setInterval(clearExpired, 60_000);
    window.addEventListener("focus", clearExpired);
    window.addEventListener("storage", syncTabs);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener("focus", clearExpired);
      window.removeEventListener("storage", syncTabs);
    };
  }, [storageKey]);
  useEffect(() => {
    if (expanded && conversation.current) conversation.current.scrollTop = conversation.current.scrollHeight;
  }, [activeChat, expanded, pending]);

  function chooseChat(id) {
    setActiveId(id || null);
    setDraft("");
    setExpanded(true);
  }

  async function send(text) {
    const question = text.trim();
    if (!question || request.current) return;
    const id = activeChat?.id ?? globalThis.crypto?.randomUUID?.() ?? `assistant-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const history = messages.filter(message => !message.error).slice(-8).map(({ role, text: body }) => ({ role, text: body }));
    setActiveId(id);
    setExpanded(true);
    setDraft("");
    setChats(current => appendMessage(current, id, { role: "user", text: question }));
    const controller = new AbortController();
    request.current = controller;
    setPending(true);
    input.current?.focus();
    try {
      const answer = demo ? demoAssistant(question, history) : await requestAssistant(question, history, id, controller.signal, profileId);
      if (!controller.signal.aborted) {
        const previous = messages.findLast(message => message.searchId);
        if (!demo && previous && answer.searchId)
          recordEvent(id, "reformulation", { search_id: previous.searchId, next_search_id: answer.searchId });
        setChats(current => appendMessage(current, id, { role: "assistant", text: answer.text, products: answer.products,
          upsell: answer.upsell, searchId: answer.searchId }));
      }
    } catch {
      if (!controller.signal.aborted) setChats(current => appendMessage(current, id, { role: "assistant", error: true, text: "Помощник пока недоступен. Можно продолжить обычный поиск или открыть демо чата." }));
    } finally {
      if (request.current === controller) request.current = null;
      if (!controller.signal.aborted) setPending(false);
    }
  }

  return (
    <aside className="assistant-panel" aria-labelledby="assistant-title">
      <div className="assistant-heading">
        <span className="assistant-icon" aria-hidden="true">✦</span>
        <h2 id="assistant-title">Чат-помощник</h2>
        {demo && <span className="assistant-demo">Демо</span>}
        <button className="text-button assistant-toggle" type="button" aria-expanded={expanded} aria-controls="assistant-content"
          onClick={() => setExpanded(!expanded)}>{expanded ? "Свернуть" : "Развернуть"}</button>
      </div>
      <div className="assistant-body" id="assistant-content" hidden={!expanded}>
        {!!chats.length && <div className="assistant-history">
          <label className="sr-only" htmlFor="assistant-chat-history">История чатов</label>
          <select id="assistant-chat-history" disabled={pending} value={activeChat?.id ?? ""} onChange={event => chooseChat(event.target.value)}>
            <option value="">История чатов</option>
            {chats.map(chat => <option key={chat.id} value={chat.id}>
              {chatDate.format(chat.updatedAt)} · {chat.messages.find(message => message.role === "user").text.slice(0, 42)}
            </option>)}
          </select>
          <button className="text-button" type="button" onClick={() => chooseChat(null)} disabled={pending || !activeChat}>Новый чат</button>
        </div>}
        <div className="assistant-messages" ref={conversation} role="log" aria-label="Диалог с помощником">
          <p className="message message-assistant">{greeting}</p>
          {messages.map((message, index) => (
            <div className={`message message-${message.role}`} key={index}>
              <p>{message.text}</p>
              {!!message.products?.length && <ul className="assistant-products">
                {message.products.map((item, position) =>
                  <AssistantProduct key={item.offer_id} item={item} sessionId={demo ? null : activeChat.id}
                    searchId={message.searchId} position={position + 1} />)}
              </ul>}
              {!!message.upsell?.length && <section className="assistant-recommendations">
                <h3>{message.products?.length ? "Рекомендации к покупке" : "Предложения каталога"}</h3>
                <ul className="assistant-products">{message.upsell.map(item => <AssistantProduct key={item.offer_id}
                  item={item} sessionId={demo ? null : activeChat.id} searchId={message.searchId} />)}</ul>
              </section>}
              {message.error && <a className="assistant-error-link" href="?demo=1">Открыть демо</a>}
            </div>
          ))}
          {pending && <p className="assistant-status" role="status">Подбираю варианты…</p>}
        </div>
        {!messages.length && <div className="assistant-suggestions">
          {suggestions.map(text => <button className="button secondary" type="button" key={text} onClick={() => send(text)}>{text}</button>)}
        </div>}
      </div>
      <form className="assistant-form" onSubmit={event => { event.preventDefault(); send(draft); }}>
        <label className="sr-only" htmlFor="assistant-question">Вопрос помощнику</label>
        <input ref={input} id="assistant-question" type="text" placeholder="Задайте вопрос о ПО" value={draft} maxLength={1000}
          onChange={event => setDraft(event.target.value)} autoComplete="off" />
        <button className="button primary" type="submit" disabled={pending || !draft.trim()}>Отправить</button>
      </form>
    </aside>
  );
}
