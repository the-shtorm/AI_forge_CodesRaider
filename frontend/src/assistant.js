import { demoSearch, fetchSearch, skipsUpsell } from "./catalog.js";
import { upsellCards } from "./upsell.js";

const topics = {
  office: {
    pattern: /офис|документ|таблиц|презентац|\b(?:word|excel|office)\b/i,
    query: "EP2-06605",
    text: "Для документов и таблиц подойдёт Microsoft Office для дома и бизнеса 2024. Это бессрочная лицензия для Windows. На сколько компьютеров нужно установить пакет?",
  },
  backup: {
    pattern: /резерв|восстанов|бэкап|backup/i,
    query: "PS16NL",
    text: "Для резервного копирования и восстановления файлов посмотрите серверную лицензию Кибер Бэкап. Какую ОС и сколько устройств нужно защитить?",
  },
  meetings: {
    pattern: /видеовстреч|видеосвяз|вебинар|конференц|мтс линк/i,
    query: "мтс",
    text: "Для видеовстреч и вебинаров есть несколько тарифов МТС Линк. Сколько участников будет на одной встрече? Это поможет выбрать тариф.",
  },
  operatingSystem: {
    pattern: /astra|астра|операционн\S* систем/i,
    query: "OS2001X8618BOXSKTSR01-PO12",
    text: "Для задачи, связанной с Astra Linux, в каталоге есть Astra Linux Special Edition 1.8. Уточните, нужна сама ОС или программа, совместимая с ней?",
  },
};

function topicFor(text) {
  return Object.entries(topics).find(([, topic]) => topic.pattern.test(text))?.[0];
}

function desiredPlatform(text) {
  return text.match(/\b(?:Astra Linux|Windows|Linux|macOS)\b|виндоус|виндовс|линукс|макос/i)?.[0];
}

function desiredBudget(text) {
  const match = text.match(/до\s+(\d+(?:[ \u00a0]\d{3})*)(?:\s*(тыс(?:яч)?\.?|к(?=\s|$)))?/i);
  if (!match) return undefined;
  const amount = Number(match[1].replace(/\s/g, "")) * (match[2] ? 100000 : 100);
  return Number.isSafeInteger(amount) ? amount : undefined;
}

function assistantCard({ item, anchor, block, position }) {
  const { offer_id, family_key, name, url, image_url, role, recommendation_reason } = item;
  return { offer_id, family_key, name, url, image_url, role, block, position, recommendation_reason,
    anchorName: anchor?.name, source: role === "complement" ? item.checks[0].source_url : undefined };
}

function assistantCards(result) {
  const top = result.top4 ?? [];
  return {
    products: top.flatMap((item, index) => item.role === "direct"
      ? [assistantCard({ item, block: "top4", position: index + 1 })] : []),
    upsell: upsellCards(result).map(assistantCard),
  };
}

export function demoAssistant(question, history) {
  const earlierQuestions = history.filter(turn => turn.role === "user").map(turn => turn.text);
  const previousTopic = earlierQuestions.map(topicFor).findLast(Boolean);
  const currentTopic = topicFor(question);
  const platformFollowUp = currentTopic === "operatingSystem" && !/операционн\S* систем|нужна ос/i.test(question);
  const topic = platformFollowUp && previousTopic ? previousTopic : currentTopic ?? previousTopic;
  const context = [...earlierQuestions, question];
  const platform = context.map(desiredPlatform).findLast(Boolean);
  const budget = context.map(desiredBudget).findLast(value => value !== undefined);
  const filters = { max_price_minor: budget };
  const cards = assistantCards(demoSearch({ q: topic ? topics[topic].query : question, filters }));
  if (skipsUpsell(question)) cards.upsell = [];
  if (!topic) {
    return { text: cards.products.length ? "Вот что нашлось в демокаталоге." : "Для какой задачи вы подбираете товар?", ...cards };
  }
  if (!cards.products.length) {
    return { text: "В демокаталоге нет подходящего варианта в указанном бюджете. Можно увеличить бюджет или уточнить обязательные требования?", ...cards };
  }
  if (topic === "office" && platform && !/^(windows|виндоус|виндовс)$/i.test(platform)) {
    const selectedPlatform = /линукс/i.test(platform) ? "Linux" : /макос/i.test(platform) ? "macOS" : platform;
    const other = assistantCards(demoSearch({ q: topics.office.query, filters: { ...filters, platform: selectedPlatform } }));
    return { text: `В демокаталоге Office 2024 указан для Windows. Для ${platform} у меня нет подтверждённого варианта. Подойдёт веб-версия офисного пакета?`,
      products: [], upsell: skipsUpsell(question) ? [] : other.upsell };
  }
  if (topic === "backup" && platform) {
    return { text: `Кибер Бэкап подходит по назначению, но карточка этой лицензии не подтверждает поддержку ${platform}. Уточните версию ОС и редакцию у поставщика перед выбором.`, ...cards };
  }
  if (topic === "office" && /^\s*(?:на\s+)?\d+\s*(?:пк|компьютер|устройств)/i.test(question)) {
    return { text: "Office 2024 подходит по задаче. Для нескольких компьютеров проверьте число устройств, покрываемых конкретной лицензией, и сравните нужное количество лицензий.", ...cards };
  }
  return { text: topics[topic].text, ...cards };
}

export async function requestAssistant(question, history, sessionId, signal, profileId) {
  const result = await fetchSearch("/v1/search", {
    method: "POST", signal,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ q: question, strategy: "llm", session_id: sessionId, assistant_history: history,
      context: profileId ? { profile_id: profileId } : undefined }),
  });
  const cards = assistantCards(result);
  const text = result.assistant_message || (cards.products.length
    ? "Вот что нашёл поиск в каталоге по вашему запросу."
    : "В каталоге не нашлось совпадений. Попробуйте уточнить название или назначение товара.");
  return { text, ...cards, searchId: result.search_id };
}
