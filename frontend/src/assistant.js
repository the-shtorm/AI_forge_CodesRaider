import { demoSearch, fetchSearch } from "./catalog.js";

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

export function demoAssistant(question, history) {
  const earlierQuestions = history.filter(turn => turn.role === "user").map(turn => turn.text);
  const previousTopic = earlierQuestions.map(topicFor).findLast(Boolean);
  const currentTopic = topicFor(question);
  const platformFollowUp = currentTopic === "operatingSystem" && !/операционн\S* систем|нужна ос/i.test(question);
  const topic = platformFollowUp && previousTopic ? previousTopic : currentTopic ?? previousTopic;
  if (!topic) {
    return { text: "Для какой задачи вы подбираете программу? Например, для работы с документами, резервного копирования или видеовстреч.", products: [] };
  }

  const context = [...earlierQuestions, question];
  const platform = context.map(desiredPlatform).findLast(Boolean);
  const budget = context.map(desiredBudget).findLast(value => value !== undefined);
  const filters = { max_price_minor: budget };
  const items = demoSearch({ q: topics[topic].query, filters }).top4.filter(item => item.role === "direct").slice(0, 3);
  if (!items.length) {
    return { text: "В демокаталоге нет подходящего варианта в указанном бюджете. Можно увеличить бюджет или уточнить обязательные требования?", products: [] };
  }
  if (topic === "office" && platform && !/^(windows|виндоус|виндовс)$/i.test(platform)) {
    return { text: `В демокаталоге Office 2024 указан для Windows. Для ${platform} у меня нет подтверждённого варианта. Подойдёт веб-версия офисного пакета?`, products: [] };
  }
  if (topic === "backup" && platform) {
    return { text: `Кибер Бэкап подходит по назначению, но карточка этой лицензии не подтверждает поддержку ${platform}. Уточните версию ОС и редакцию у поставщика перед выбором.`, products: items };
  }
  if (topic === "office" && /^\s*(?:на\s+)?\d+\s*(?:пк|компьютер|устройств)/i.test(question)) {
    return { text: "Office 2024 подходит по задаче. Для нескольких компьютеров проверьте число устройств, покрываемых конкретной лицензией, и сравните нужное количество лицензий.", products: items };
  }
  return { text: topics[topic].text, products: items };
}

export async function requestAssistant(question, history, sessionId, signal, profileId) {
  const result = await fetchSearch("/v1/search", {
    method: "POST", signal,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ q: question, strategy: "llm", session_id: sessionId, assistant_history: history,
      context: profileId ? { profile_id: profileId } : undefined }),
  });
  const products = (result.top4 ?? []).filter(item => item.role === "direct").slice(0, 3);
  const text = result.assistant_message || (products.length
    ? "Вот что нашёл поиск в каталоге по вашему запросу."
    : "В каталоге не нашлось совпадений. Попробуйте уточнить название или назначение товара.");
  return { text, products };
}
