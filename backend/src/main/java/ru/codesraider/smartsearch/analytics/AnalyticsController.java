package ru.codesraider.smartsearch.analytics;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ru.codesraider.smartsearch.web.ApiProblem;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping(path = "/v1")
class AnalyticsController {
    private final EventStore events;
    private final AnalyticsService analytics;

    AnalyticsController(EventStore events, AnalyticsService analytics) {
        this.events = events;
        this.analytics = analytics;
    }

    @PostMapping(path = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void events(@RequestBody JsonNode payload) {
        events.ingest(payload);
    }

    @GetMapping(path = "/analytics", produces = MediaType.APPLICATION_JSON_VALUE)
    AnalyticsService.Report analytics(@RequestParam("from") String from, @RequestParam("to") String to,
                                       @RequestParam(name = "source", required = false) String source,
                                       @RequestParam(name = "index_version", required = false) String version) {
        var start = Event.instant(from);
        var end = Event.instant(to);
        if (!start.isBefore(end)) throw ApiProblem.invalid("Начало периода должно быть раньше окончания.");
        if (source != null) Event.validateSource(source);
        if (version != null && !version.matches(Event.NON_BLANK)) {
            throw ApiProblem.invalid("Укажите непустую версию индекса.");
        }
        return analytics.report(start, end, source, version);
    }
}
