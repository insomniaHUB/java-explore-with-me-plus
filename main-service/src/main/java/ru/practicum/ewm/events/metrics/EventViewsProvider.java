package ru.practicum.ewm.events.metrics;

import java.util.Map;
import java.util.Set;

/** Контракт для части статистики: уникальные IP по URI /events/{id}; сбой — исключение, не пустая Map. */
public interface EventViewsProvider {
    Map<Long, Long> countUniqueViews(Set<Long> eventIds);
}
