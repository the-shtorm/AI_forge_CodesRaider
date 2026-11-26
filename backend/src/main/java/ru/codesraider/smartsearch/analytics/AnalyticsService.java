package ru.codesraider.smartsearch.analytics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Service;

@Service
class AnalyticsService {
    private static final String COHORT = """
            WITH cohort AS (
                SELECT * FROM search_log WHERE ts>=? AND ts<? AND (? IS NULL OR source=?)
                    AND (? IS NULL OR index_version=?)
            )
            """;
    private static final String METRICS = COHORT + """
            , sessions AS (
                SELECT source,session_id,MAX(CASE WHEN direct_count=0 THEN ts END) last_failure
                FROM cohort WHERE session_id IS NOT NULL GROUP BY source,session_id
            ), ended AS (
                SELECT s.source,s.session_id,
                       (SELECT MAX(x.ts) FROM search_log x
                        WHERE x.source=s.source AND x.session_id=s.session_id AND x.direct_count=0) last_failure
                FROM sessions s WHERE s.last_failure IS NOT NULL
                 AND NOT EXISTS (SELECT 1 FROM search_log x
                                 WHERE x.source=s.source AND x.session_id=s.session_id AND x.ts>?)
                 AND NOT EXISTS (SELECT 1 FROM event e
                                 WHERE e.source=s.source AND e.session_id=s.session_id AND e.ts>?)
            )
            SELECT
              (SELECT count(*) FROM cohort WHERE direct_count=0),
              (SELECT count(*) FROM cohort),
              (SELECT count(*) FROM ended s WHERE NOT EXISTS (
                  SELECT 1 FROM event e WHERE e.source=s.source AND e.session_id=s.session_id
                  AND e.ts>=s.last_failure AND e.type IN ('click','order'))),
              (SELECT count(*) FROM ended),
              (SELECT count(*) FROM sessions s WHERE EXISTS (
                  SELECT 1 FROM event e JOIN cohort c ON e.search_id=c.search_id
                  WHERE e.source=s.source AND e.session_id=s.session_id AND e.type='order')),
              (SELECT count(*) FROM sessions)
            """;
    private static final String FAILURES = COHORT + """
            , classified AS (
                SELECT q,ts,CASE
                    WHEN direct_count=0 THEN CASE json_extract(diagnostics_json,'$.failure_reason')
                        WHEN 'missing_synonym' THEN 'missing_synonym'
                        WHEN 'catalog_gap' THEN 'catalog_gap'
                        WHEN 'ranking' THEN 'ranking'
                        WHEN 'unavailable' THEN 'unavailable'
                        WHEN 'filter_conflict' THEN 'filter_conflict'
                        ELSE 'unknown' END
                    WHEN EXISTS (SELECT 1 FROM event e WHERE e.search_id=cohort.search_id AND e.type='reformulation')
                    THEN 'ranking'
                  END reason
                FROM cohort
            ), examples AS (
                SELECT reason,q,MAX(ts) last_seen,count(*) occurrences
                FROM classified WHERE reason IS NOT NULL GROUP BY reason,q
            )
            SELECT reason,q,rank,quantity FROM (
                SELECT reason,q,row_number() OVER (PARTITION BY reason ORDER BY last_seen DESC,q) rank,
                       sum(occurrences) OVER (PARTITION BY reason) quantity
                FROM examples
            ) WHERE rank<=3 ORDER BY quantity DESC,reason,rank
            """;
    private static final Map<String, String> ACTIONS = Map.of(
            "missing_synonym", "Проверить и дополнить словарь синонимов.",
            "ranking", "Проверить ранжирование на переформулировках.",
            "catalog_gap", "Проверить наличие искомого товара в каталоге.",
            "unavailable", "Проверить статус наличия и запасные предложения.",
            "filter_conflict", "Проверить сочетание условий и предложить ослабить фильтр.",
            "unknown", "Проверить примеры вручную и уточнить причину.");
    private final EventStore store;

    AnalyticsService(EventStore store) {
        this.store = store;
    }

    Report report(Instant from, Instant to, String source, String version) {
        String start = Event.timestamp(from);
        String end = Event.timestamp(to);
        String cutoff = Event.timestamp(Instant.now().minus(Duration.ofMinutes(30)));
        return store.transaction(sql -> {
            var metrics = sql.queryForObject(METRICS, (row, number) -> List.of(
                    Metric.of(row.getLong(1), row.getLong(2)), Metric.of(row.getLong(3), row.getLong(4)),
                    Metric.of(row.getLong(5), row.getLong(6))), start, end, source, source, version, version, cutoff, cutoff);
            var groups = new LinkedHashMap<String, Failure>();
            sql.query(FAILURES, row -> {
                String reason = row.getString("reason");
                long count = row.getLong("quantity");
                groups.computeIfAbsent(reason, ignored -> new Failure(reason, count,
                        new ArrayList<>(), ACTIONS.getOrDefault(reason, ACTIONS.get("unknown"))))
                        .queryExamples().add(row.getString("q"));
            }, start, end, source, source, version, version);
            return new Report(new Period(start, end, source, version),
                    metrics.get(0), metrics.get(1), metrics.get(2), List.copyOf(groups.values()));
        });
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Period(String from, String to, String source, String indexVersion) {}

    record Metric(long numerator, long denominator, Double value) {
        static Metric of(long numerator, long denominator) {
            return new Metric(numerator, denominator, denominator == 0 ? null : (double) numerator / denominator);
        }
    }

    record Failure(String reason, long count, List<String> queryExamples, String action) {}
    record Report(Period period, Metric zeroResultRate, Metric abandonmentRate, Metric conversionRate, List<Failure> failures) {}
}
