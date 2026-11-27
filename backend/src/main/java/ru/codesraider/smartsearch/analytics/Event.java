package ru.codesraider.smartsearch.analytics;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

import ru.codesraider.smartsearch.web.ApiProblem;
import tools.jackson.databind.JsonNode;

record Event(String id, String source, String session, Instant time, String type, String search,
             String nextSearch, String offer, String family, String role, String block, Long position,
             String targetKind, String targetId) {
    private static final Set<String> SOURCES = Set.of("demo", "seed");
    private static final Set<String> KINDS = Set.of("offer", "family", "vendor", "category", "collection");
    static final String NON_BLANK = "(?Us).*\\S.*";
    private static final Set<String> COMMON = Set.of("event_id", "session_id", "ts", "source", "type");
    private static final Set<String> CARD_FIELDS = Set.of("block", "search_id", "offer_id", "family_key", "role", "position");
    static final Set<String> CARD_BLOCKS = Set.of("top4", "more_results", "alternatives", "upsell");
    private static final DateTimeFormatter STORAGE_TIME = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    static Event parse(JsonNode node) {
        if (!node.isObject()) throw ApiProblem.invalid("Каждое событие должно быть объектом.");
        final String id = text(node, "event_id");
        final String session = text(node, "session_id");
        final Instant time = instant(text(node, "ts"));
        final String source = node.has("source") ? validateSource(text(node, "source")) : "demo";
        final String type = text(node, "type");
        final String block = node.has("block") ? text(node, "block") : null;
        final boolean card = "impression".equals(type) || ("click".equals(type) && block != null && CARD_BLOCKS.contains(block));
        Set<String> extra = card ? CARD_FIELDS : switch (type) {
            case "click" -> switch (block == null ? "" : block) {
                case "details" -> Set.of("block", "search_id", "offer_id", "family_key");
                case "navigation" -> Set.of("block", "search_id", "target_kind", "target_id");
                default -> throw ApiProblem.invalid("Некорректный блок клика.");
            };
            case "reformulation" -> Set.of("search_id", "next_search_id");
            case "add_to_cart" -> Set.of("offer_id", "search_id");
            case "order" -> Set.of("offer_id");
            default -> throw ApiProblem.invalid("Неизвестный тип события.");
        };
        var allowed = new HashSet<>(COMMON);
        allowed.addAll(extra);
        if (!allowed.containsAll(node.propertyNames())) {
            throw ApiProblem.invalid("Неожиданное поле для данного типа события.");
        }
        Long position = null;
        String role = null;
        if (card) {
            if (block == null || !CARD_BLOCKS.contains(block)) throw ApiProblem.invalid("Некорректный блок карточки.");
            role = text(node, "role");
            if (!Set.of("direct", "complement", "alternative", "related").contains(role)) throw ApiProblem.invalid("Некорректная роль карточки.");
            var value = node.path("position");
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 1) {
                throw ApiProblem.invalid("Позиция карточки должна быть положительным целым числом.");
            }
            position = value.asLong();
        }
        String targetKind = extra.contains("target_kind") ? text(node, "target_kind") : null;
        if (targetKind != null && !KINDS.contains(targetKind)) {
            throw ApiProblem.invalid("Некорректный вид цели клика.");
        }
        String search = null;
        boolean nullableSearch = "add_to_cart".equals(type) && (!node.has("search_id") || node.path("search_id").isNull());
        if (extra.contains("search_id") && !nullableSearch) {
            search = text(node, "search_id");
        }
        return new Event(id, source, session, time, type, search,
                extra.contains("next_search_id") ? text(node, "next_search_id") : null,
                extra.contains("offer_id") ? text(node, "offer_id") : null,
                extra.contains("family_key") ? text(node, "family_key") : null, role, block, position, targetKind,
                extra.contains("target_id") ? text(node, "target_id") : null);
    }

    private static String text(JsonNode node, String key) {
        var value = node.path(key);
        if (!value.isString() || !value.asString().matches(NON_BLANK)) {
            throw ApiProblem.invalid("Укажите непустое текстовое поле " + key + ".");
        }
        return value.asString();
    }

    static String validateSource(String source) {
        if (!SOURCES.contains(source)) throw ApiProblem.invalid("Допустимые источники событий: demo и seed.");
        return source;
    }

    static Instant instant(String text) {
        try {
            if (text == null || !text.matches("\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:[Zz]|[+-]\\d{2}:\\d{2})")) {
                throw ApiProblem.invalid("Укажите дату и время RFC 3339 со смещением.");
            }
            Instant result = OffsetDateTime.parse(text).toInstant();
            if (timestamp(result).length() != 30) throw ApiProblem.invalid("Год UTC должен содержать четыре цифры.");
            return result;
        } catch (DateTimeParseException exception) {
            throw ApiProblem.invalid("Укажите дату и время RFC 3339 со смещением.");
        }
    }

    static String timestamp(Instant time) {
        return STORAGE_TIME.format(time);
    }
}
