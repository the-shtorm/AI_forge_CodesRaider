package ru.codesraider.smartsearch;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import ru.codesraider.smartsearch.analytics.EventStore;
import ru.codesraider.smartsearch.search.SearchIndex;

@Component
class SearchReadiness implements HealthIndicator {
    private final SearchIndex index;
    private final EventStore events;

    SearchReadiness(SearchIndex index, EventStore events) {
        this.index = index;
        this.events = events;
    }

    boolean ready() {
        return index.ready() && events.isWritable();
    }

    @Override
    public Health health() {
        return (ready() ? Health.up() : Health.down()).build();
    }
}
