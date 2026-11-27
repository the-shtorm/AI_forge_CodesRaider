package ru.codesraider.smartsearch.analytics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import ru.codesraider.smartsearch.storage.Sqlite;
import ru.codesraider.smartsearch.web.ApiProblem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class EventStore {
    private static final String ATTRIBUTE = """
            UPDATE event AS purchase SET search_id=(
                SELECT click.search_id FROM event click
                WHERE click.source=purchase.source AND click.session_id=purchase.session_id
                  AND click.offer_id=purchase.offer_id AND click.type='click' AND click.search_id IS NOT NULL
                  AND (click.ts,click.event_id) < (purchase.ts,purchase.event_id)
                ORDER BY click.ts DESC,click.event_id DESC LIMIT 1
            )
            WHERE purchase.type='order' AND
            """;
    private final Path path;
    private final JsonMapper json;
    private Database database;

    public EventStore(@Value("${search.events-db:${EVENTS_DB:data/events/events.db}}") String path, JsonMapper json) {
        this.path = Path.of(path).toAbsolutePath();
        this.json = json;
    }

    public boolean isWritable() {
        try {
            return transaction(sql -> {
                // Exercise both tables and SQLite write permissions without changing any rows.
                sql.update("UPDATE search_log SET search_id=search_id WHERE 0");
                sql.update("UPDATE event SET event_id=event_id WHERE 0");
                return true;
            });
        } catch (ApiProblem unavailable) {
            return false;
        }
    }

    private synchronized Database database() {
        if (database != null) return database;
        try {
            Files.createDirectories(path.getParent());
            var source = Sqlite.dataSource(path, false);
            try (var connection = source.getConnection()) {
                Sqlite.ensureEvents(connection);
            }
            database = new Database(new JdbcTemplate(source), new TransactionTemplate(new JdbcTransactionManager(source)));
            return database;
        } catch (IOException | SQLException | DataAccessException exception) {
            throw ApiProblem.events(exception);
        }
    }

    public String lastChatQuery(String session, String version) {
        try {
            var previous = database().sql().queryForList("""
                    SELECT CASE WHEN index_version=? THEN json_extract(diagnostics_json,'$.chat_query') END
                    FROM search_log WHERE source='demo' AND session_id=?
                      AND json_type(diagnostics_json,'$.chat_cards')='array'
                    ORDER BY ts DESC, rowid DESC LIMIT 1
                    """, String.class, version, session);
            return previous.isEmpty() ? null : previous.getFirst();
        } catch (DataAccessException exception) {
            throw ApiProblem.searchJournal(ApiProblem.events(exception));
        }
    }

    public boolean repeatsChatCards(String session, String version, List<String> cards, Instant now) {
        if (cards.isEmpty()) return false;
        try {
            var previous = database().sql().queryForList("""
                    SELECT CASE WHEN index_version=? THEN json_extract(diagnostics_json,'$.chat_cards') END FROM search_log
                    WHERE source='demo' AND session_id=? AND ts>=? AND ts<=?
                      AND json_type(diagnostics_json,'$.chat_cards')='array'
                    ORDER BY ts DESC, rowid DESC LIMIT 2
                    """, String.class, version, session, Event.timestamp(now.minusSeconds(120)), Event.timestamp(now));
            String key = json.writeValueAsString(cards);
            return previous.size() == 2 && previous.stream().allMatch(key::equals);
        } catch (DataAccessException exception) {
            throw ApiProblem.searchJournal(ApiProblem.events(exception));
        }
    }

    public void logSearch(Map<String, ?> request, Map<String, ?> response, Map<String, Object> diagnostics,
                          Map<String, String> detailOffers, Instant time, long elapsedMs) {
        var shown = new LinkedHashMap<String, Object>();
        shown.put("primary_target", response.get("primary_target"));
        var top4 = (List<?>) response.get("top4");
        shown.put("top4", shownCards(top4));
        shown.put("more_results", shownCards((List<?>) response.get("more_results")));
        shown.put("alternatives", shownCards((List<?>) response.get("alternatives")));
        if (response.get("upsell") instanceof List<?> upsell) shown.put("upsell", shownCards(upsell));
        shown.put("details", detailOffers);
        var selected = request.get("selected_target") instanceof Map<?, ?> target ? target : Map.of();
        try {
            database().sql().update("""
                    INSERT INTO search_log(search_id,source,session_id,ts,q,normalized_q,selected_target_kind,selected_target_id,
                      context_json,applied_filters_json,direct_count,shown_family_count,shortfall_reason,mode,index_version,
                      elapsed_ms,shown_json,diagnostics_json)
                    VALUES (?,'demo',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, response.get("search_id"), request.get("session_id"), Event.timestamp(time),
                    request.get("q"), response.get("normalized_q"), selected.get("kind"), selected.get("target_id"),
                    json.writeValueAsString(request.get("context") == null ? Map.of() : request.get("context")),
                    json.writeValueAsString(response.get("applied_filters")), response.get("direct_count"), top4.size(),
                    response.get("shortfall_reason"), response.get("mode"), response.get("index_version"), elapsedMs,
                    json.writeValueAsString(shown), json.writeValueAsString(diagnostics));
        } catch (DataAccessException exception) {
            throw ApiProblem.events(exception);
        }
    }

    void ingest(JsonNode payload) {
        if (!payload.isArray() || payload.isEmpty()) throw ApiProblem.invalid("Передайте непустой массив событий.");
        var events = new ArrayList<Event>();
        for (var node : payload) events.add(Event.parse(node));
        transaction(sql -> {
            for (var event : events) write(sql, event);
            return null;
        });
    }

    <T> T transaction(Function<JdbcTemplate, T> action) {
        try {
            var db = database();
            return db.transactions().execute(status -> action.apply(db.sql()));
        } catch (DataAccessException | TransactionException exception) {
            throw ApiProblem.events(exception);
        }
    }

    private void write(JdbcTemplate sql, Event event) {
        if (Boolean.TRUE.equals(sql.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM event WHERE event_id=?)", Boolean.class, event.id()))) return;
        if (event.search() != null) validateEvent(validateSearch(sql, event, event.search()), event);
        if (event.nextSearch() != null) validateSearch(sql, event, event.nextSearch());
        String timestamp = Event.timestamp(event.time());
        sql.update("""
                INSERT INTO event(event_id,source,session_id,ts,type,search_id,next_search_id,offer_id,family_key,
                    role,block,position,target_kind,target_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(event_id) DO NOTHING
                """, event.id(), event.source(), event.session(), timestamp, event.type(), event.search(),
                event.nextSearch(), event.offer(), event.family(), event.role(), event.block(), event.position(),
                event.targetKind(), event.targetId());
        if ("order".equals(event.type())) {
            sql.update(ATTRIBUTE + " purchase.event_id=?", event.id());
        } else if ("click".equals(event.type()) && event.offer() != null) {
            sql.update(ATTRIBUTE + """
                    purchase.source=? AND purchase.session_id=? AND purchase.offer_id=?
                    AND (purchase.ts,purchase.event_id) > (?,?)
                    """, event.source(), event.session(), event.offer(), timestamp, event.id());
        }
    }

    private LoggedSearch validateSearch(JdbcTemplate sql, Event event, String searchId) {
        var search = sql.query("SELECT source,session_id,shown_json FROM search_log WHERE search_id=?",
                (row, number) -> new LoggedSearch(row.getString(1), row.getString(2), row.getString(3)), searchId)
                .stream().findFirst().orElse(null);
        if (search == null || !event.source().equals(search.source())
                || (search.session() != null && !event.session().equals(search.session()))) {
            throw ApiProblem.invalid("Поиск события не найден для этого источника и сессии.");
        }
        return search;
    }

    private void validateEvent(LoggedSearch search, Event event) {
        if (event.block() == null) return;
        JsonNode shown = json.readTree(search.shown());
        boolean valid = switch (event.block()) {
            case "navigation" -> same(shown.path("primary_target"), "kind", event.targetKind())
                    && same(shown.path("primary_target"), "id", event.targetId());
            case "details" -> same(shown.path("details"), event.offer(), event.family())
                    || Event.CARD_BLOCKS.stream().flatMap(name -> shown.path(name).valueStream())
                            .anyMatch(card -> same(card, "offer_id", event.offer()) && same(card, "family_key", event.family()));
            default -> {
                JsonNode cards = shown.path(event.block());
                if (event.position() > cards.size()) yield false;
                JsonNode card = cards.path(event.position().intValue() - 1);
                yield same(card, "offer_id", event.offer()) && same(card, "family_key", event.family())
                        && same(card, "role", event.role());
            }
        };
        if (!valid) throw ApiProblem.invalid("Событие не соответствует показанным карточкам или навигационной цели.");
    }

    private static boolean same(JsonNode node, String key, String expected) {
        var value = node.path(key);
        return value.isString() && Objects.equals(value.asString(), expected);
    }

    private static List<Map<String, Object>> shownCards(List<?> cards) {
        return cards.stream().map(value -> {
            var card = (Map<?, ?>) value;
            Map<String, Object> entry = new LinkedHashMap<>();
            for (String field : List.of("offer_id", "family_key", "role", "anchor_offer_id", "name", "url", "available",
                    "attributes", "reason_codes", "checks", "differences", "recommendation_kind", "recommendation_reason")) {
                if (card.containsKey(field)) entry.put(field, card.get(field));
            }
            return entry;
        }).toList();
    }

    public JsonNode shownCard(String searchId, String offerId) {
        return transaction(sql -> sql.query("""
                SELECT l.q,card.value FROM search_log l,json_each(l.shown_json) block,json_each(block.value) card
                WHERE l.search_id=? AND block.key IN ('top4','more_results','alternatives','upsell')
                  AND json_extract(card.value,'$.offer_id')=? LIMIT 1
                """, (rs, n) -> {
                    var card = (tools.jackson.databind.node.ObjectNode) json.readTree(rs.getString(2));
                    card.put("query", rs.getString(1));
                    return (JsonNode) card;
                }, searchId, offerId).stream().findFirst().orElse(null));
    }

    private record LoggedSearch(String source, String session, String shown) {}
    private record Database(JdbcTemplate sql, TransactionTemplate transactions) {}
}
