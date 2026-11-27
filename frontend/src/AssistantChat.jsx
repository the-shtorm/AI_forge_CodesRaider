import { useEffect, useRef, useState } from "react";
import ProductImage from "./ProductImage";
import { requestAssistant } from "./assistant";
import { entityHref, eventSession, newId } from "./api";
import { cardEvent, trackEvents } from "./events";
import { appendMessage, loadChats, pruneChats, saveChats } from "./chatHistory";

const greeting = "Расскажите, для чего нужна программа. Помогу подобрать ПО под вашу задачу и бюджет.";
const suggestions = ["Офис без подписки", "Как восстановить файлы после сбоя?"];
const chatDate = new Intl.DateTimeFormat("ru-RU", { day: "numeric", month: "short" });

const storageKey = "softline-assistant-chats-v1";

export default function AssistantChat({ navigate, profileId }) {
  const [chats, setChats] = useState(() => loadChats(storageKey));
  const [activeId, setActiveId] = useState(chats[0]?.id ?? null);
  const [expanded, setExpanded] = useState(false);
  const [draft, setDraft] = useState("");
  const [pending, setPending] = useState(false);
  const request = useRef(null);
  const conversation = useRef(null);
  const input = useRef(null);
  const lastImpression = useRef(null);
  const activeChat = chats.find(chat => chat.id === activeId);
  const messages = activeChat?.messages ?? [];

  useEffect(() => () => request.current?.abort(), []);
  useEffect(() => { saveChats(storageKey, chats); }, [chats]);
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
  }, []);
  useEffect(() => {
    if (expanded && conversation.current) conversation.current.scrollTop = conversation.current.scrollHeight;
  }, [activeChat, expanded, pending]);

  useEffect(() => {
    const answer = activeChat?.messages.at(-1);
    const search = answer?.search;
    if (!expanded || !search || search.searchId === lastImpression.current || !answer.products?.length) return;
    lastImpression.current = search.searchId;
    if (eventSession(search) === search.session) {
      trackEvents(search.session, answer.products.map(item => cardEvent("impression", item, "top4", item.position, search.searchId)));
    }
  }, [activeChat, expanded]);

  function chooseChat(id) {
    setActiveId(id || null);
    setDraft("");
    setExpanded(true);
  }

  async function send(text) {
    const question = text.trim();
    if (!question || request.current) return;
    const id = activeChat?.id ?? newId();
    const previous = messages.findLast(message => message.search)?.search;
    const session = previous && eventSession(previous) === previous.session ? previous.session : newId();
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
      const answer = await requestAssistant(question, history, session, controller.signal, profileId);
      if (!controller.signal.aborted) {
        const products = answer.products.map(({ offer_id, name, family_key, role, position }) =>
          ({ offer_id, name, family_key, role, position }));
        const search = answer.searchId
          ? { searchId: answer.searchId, session, filters: answer.appliedFilters, assistant: true, at: Date.now() }
          : undefined;
        if (search && previous?.session === session) {
          trackEvents(session, [{ type: "reformulation", search_id: previous.searchId, next_search_id: search.searchId }]);
        }
        setChats(current => appendMessage(current, id, { role: "assistant", text: answer.text, products, search }));
      }
    } catch {
      if (!controller.signal.aborted) setChats(current => appendMessage(current, id, { role: "assistant", error: true, text: "Помощник временно недоступен. Попробуйте ещё раз." }));
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
                {message.products.map(item => {
                  const href = entityHref({ kind: "offer", id: item.offer_id }, message.search?.filters);
                  return <li key={item.offer_id}><div className="assistant-product-image"><ProductImage /></div>
                    <a href={href} onClick={event => {
                      const search = message.search;
                      const attributed = search && eventSession(search) === search.session;
                      if (attributed) trackEvents(search.session, [cardEvent("click", item, "top4", item.position, search.searchId)]);
                      navigate(event, href, attributed ? { ...search, familyKey: item.family_key } : null);
                      setExpanded(false);
                    }}>{item.name}</a></li>;
                })}
              </ul>}
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
