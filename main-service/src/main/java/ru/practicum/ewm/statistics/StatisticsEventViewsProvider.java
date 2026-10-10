package ru.practicum.ewm.statistics;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.practicum.ewm.events.metrics.EventViewsProvider;

import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class StatisticsEventViewsProvider implements EventViewsProvider {
    private final EventStatisticsService statisticsService;

    @Override
    public Map<Long, Long> countUniqueViews(Set<Long> eventIds) {
        return statisticsService.countUniqueViews(eventIds);
    }
}
