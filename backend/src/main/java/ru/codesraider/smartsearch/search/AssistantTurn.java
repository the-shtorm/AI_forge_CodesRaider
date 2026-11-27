package ru.codesraider.smartsearch.search;

import java.util.*;
import java.util.regex.Pattern;

import ru.codesraider.smartsearch.search.SearchCatalog.Row;

/** State for a single assistant request. Only server-journaled card IDs participate in repetition. */
record AssistantTurn(String session, List<Map<String, String>> history, int cardLimit) {
    static final String CLARIFY = "Пока не понимаю, какой товар вам нужен. Опишите, пожалуйста, что вы хотите сделать и с чем работаете.";
    static final String FILTERED = "По этим условиям подходящих товаров не нашлось. Какие требования обязательны, а какие можно изменить?";
    private static final Pattern AGAIN = Pattern.compile(
            "(?:еще(?: раз| варианты)?|другие варианты|что еще|покажи (?:еще|другие варианты)|есть еще варианты)");
    private static final Pattern PREFIX = Pattern.compile("^(?:а |тогда |лучше |теперь )+");
    private static final Pattern UNSUPPORTED = Pattern.compile(
            "(?U)\\d|\\b(?:без|кроме|исключая|только|не|вместо|или)\\b");

    static AssistantTurn from(Map<String, Object> request) {
        var history = new ArrayList<Map<String, String>>();
        if (request.get("assistant_history") instanceof List<?> turns) {
            for (Object entry : turns) {
                var message = (Map<?, ?>) entry; // SearchInput already validated roles, sizes and types.
                history.add(Map.of("role", (String) message.get("role"), "text", (String) message.get("text")));
            }
        }
        return new AssistantTurn((String) request.get("session_id"), history, ((Number) request.getOrDefault("assistant_card_limit", 3)).intValue());
    }

    // A whole new shopping question starts a new topic. Pure filters and requests for more
    // options inherit the most recent user topic and its subsequent refinements.
    String resolve(String question, java.util.function.Supplier<String> lastQuery) {
        String qualifier = qualifier(question);
        if (qualifier == null) {
            // A buyer may give a budget before answering our question about the intended product.
            if (!history.isEmpty() && history.getLast().get("role").equals("assistant")
                    && history.getLast().get("text").equals(CLARIFY)) {
                String prior = history.reversed().stream().filter(m -> m.get("role").equals("user"))
                        .map(m -> m.get("text")).findFirst().orElse("");
                String filter = qualifier(prior);
                if (filter != null && !filter.isEmpty()) return refine(filter, question);
            }
            return question;
        }
        String persisted = lastQuery.get();
        if (persisted != null) return refine(persisted, qualifier);
        var refinements = new ArrayDeque<String>();
        refinements.addFirst(qualifier);
        for (var message : history.reversed()) {
            if (!message.get("role").equals("user")) continue;
            String prior = message.get("text");
            String value = qualifier(prior);
            if (value == null) {
                for (String next : refinements) prior = refine(prior, next);
                return prior;
            }
            refinements.addFirst(value);
        }
        return question;
    }

    private static String refine(String prior, String addition) {
        if (addition.isBlank()) return prior;
        prior = SearchInput.normalize(prior);
        var updated = new Requirements();
        updated.extractQuery(SearchInput.normalize(addition));
        if (updated.values.containsKey("max_price_minor")) prior = Requirements.take(prior, Requirements.BUDGET, m -> {});
        if (updated.values.containsKey("platform")) prior = Requirements.take(prior, Requirements.PLATFORM, m -> {});
        if (prior.endsWith(" " + addition)) return prior;
        return (prior + " " + addition).strip().replaceAll(" +", " ");
    }

    private static String qualifier(String question) {
        String text = PREFIX.matcher(SearchInput.normalize(question)).replaceFirst("").replaceAll("[?!.]+$", "").strip();
        if (AGAIN.matcher(text).matches()) return "";
        if (text.matches("windows|linux|macos")) text = "для " + text;
        var constraints = new Requirements();
        String rest = constraints.extractQuery(text);
        return rest.isBlank() && !text.isBlank() ? text : null;
    }

    private static final Pattern VAGUE = Pattern.compile("(?U)(?:(?:мне |нам |ну )?(?:нуж\\w*|хочу|хотим|подбер\\w*|покаж\\w*) )?"
            + "(?:(?:что[- ](?:то|нибудь)|че[- ]нибудь|программ[ау]|товар|помощь|решение)(?: подходящ\\w*)? ?)?"
            + "(?:для (?:работы|офиса|дома|меня|нас)|что посоветуете|помоги(?:те)?)?[?!. ]*");
    private static final Pattern CONTENT = Pattern.compile("(?U)^(?:(?:пожалуйста|можешь|можете) )?"
            + "(?:(?:напиш\\w*|сочин\\w*|расскаж\\w*|придум\\w*) (?:мне |нам )?(?:стих|рассказ|анекдот|сказк|песн|истори)"
            + "|(?:какая|какова) погода|(?:что такое|как (?:работает|устроен)) )");

    private static final Pattern SERVICE_OR_CONTENT = Pattern.compile("(?U)\\b(?:в (?:ближайшем |соседнем )?(?:копицентре|салоне)"
            + "|(?:скачать|загрузить|посмотреть|найти) (?:готовую |готовый )?(?:запись|афишу|фильм|сериал|видео с инструкцией))\\b");

    boolean needsClarification(String question) {
        return VAGUE.matcher(question).matches() || CONTENT.matcher(question).find()
                || SERVICE_OR_CONTENT.matcher(question).find();
    }

    boolean canRewrite(String terms) {
        return terms.length() <= 1000 && !terms.isBlank() && !TaskGoals.literalRequest(terms) && !UNSUPPORTED.matcher(terms).find();
    }

    private static final Pattern REFERENCE = Pattern.compile("(?U)\\b(?:этим|это|этого|этот|эти|такой|такого|таком|"
            + "его|него|нее|ними|них|предыдущ[а-я]*|this|that|those|these|them|it)\\b");

    record Message(String role, String text) {}

    List<Message> promptHistory(String question) {
        if (!REFERENCE.matcher(question).find()) return List.of();
        return history.stream().skip(Math.max(0, history.size() - 4))
                .map(m -> new Message(m.get("role"), m.get("text").substring(0, Math.min(400, m.get("text").length()))))
                .toList();
    }

    List<String> cards(List<Row> direct) {
        return direct.stream().limit(cardLimit).map(row -> row.str("offer_id")).sorted().toList();
    }

    /** Each choice only reorders representatives which already passed the current SKU predicates. */
    String diversify(List<Row> direct, String question, ModelClient model, Map<String, Object> diagnostics) {
        if (direct.size() <= cardLimit) {
            return "Вы снова видите те же товары. Других совпадений при этих условиях не нашлось. Что вы хотели бы изменить в подборе?";
        }
        var shown = direct.subList(0, cardLimit);
        var fresh = direct.subList(cardLimit, direct.size());
        var options = List.of("NEXT_MATCH", "OTHER_VENDOR", "LOWER_PRICE");
        String op = model.choose("Choose how to show other equally eligible catalog products. Use LOWER_PRICE if the buyer "
                        + "wants to spend less, OTHER_VENDOR for a different manufacturer, otherwise NEXT_MATCH.",
                Map.of("question", question, "shown", shown.stream().map(r -> r.str("name")).toList()), options, 0.4);
        diagnostics.put("chat_order", op == null ? "NEXT_MATCH" : op);
        if (op != null) diagnostics.put("chat_model_order", op);
        if ("OTHER_VENDOR".equals(op)) {
            var vendors = shown.stream().map(row -> row.str("vendor_id")).toList();
            fresh.sort(Comparator.comparing(row -> vendors.contains(row.str("vendor_id"))));
        } else if ("LOWER_PRICE".equals(op)) {
            fresh.sort(Comparator.comparing(row -> Row.positive(row.get("price_minor")),
                    Comparator.nullsLast(Comparator.naturalOrder())));
        }
        Collections.rotate(direct, -cardLimit);
        return "Вот другие совпадения из каталога по тем же условиям.";
    }
}
