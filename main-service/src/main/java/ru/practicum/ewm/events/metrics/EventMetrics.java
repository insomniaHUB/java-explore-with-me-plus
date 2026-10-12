package ru.practicum.ewm.events.metrics;

import java.util.Map;

public record EventMetrics(Map<Long, Long> confirmedRequests, Map<Long, Long> views) {
    public EventMetrics {
        confirmedRequests = Map.copyOf(confirmedRequests);
        views = Map.copyOf(views);
    }

    public long confirmed(Long eventId) {
        return confirmedRequests.getOrDefault(eventId, 0L);
    }

    public long views(Long eventId) {
        return views.getOrDefault(eventId, 0L);
    }
}
