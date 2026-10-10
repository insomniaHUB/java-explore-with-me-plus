package ru.practicum.ewm.events.metrics;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import ru.practicum.ewm.exception.ServiceUnavailableException;

import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EventMetricsService {
    private final ObjectProvider<ConfirmedRequestsProvider> requestsProvider;
    private final ObjectProvider<EventViewsProvider> viewsProvider;

    public EventMetrics load(Set<Long> eventIds) {
        return new EventMetrics(loadConfirmedRequests(eventIds), loadViews(eventIds));
    }

    public Map<Long, Long> loadConfirmedRequests(Set<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        ConfirmedRequestsProvider requests = requestsProvider.getIfAvailable();
        if (requests == null) {
            throw new ServiceUnavailableException("Не подключён поставщик счётчиков заявок");
        }
        try {
            return checkCounts(requests.countConfirmedRequests(eventIds));
        } catch (RestClientException exception) {
            throw new ServiceUnavailableException("Не удалось получить количество заявок", exception);
        }
    }

    public Map<Long, Long> loadViews(Set<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        EventViewsProvider views = viewsProvider.getIfAvailable();
        if (views == null) {
            throw new ServiceUnavailableException("Не подключён поставщик счётчиков просмотров");
        }
        try {
            return checkCounts(views.countUniqueViews(eventIds));
        } catch (RestClientException exception) {
            throw new ServiceUnavailableException("Сервис статистики недоступен", exception);
        }
    }

    private Map<Long, Long> checkCounts(Map<Long, Long> counts) {
        if (counts == null || counts.entrySet().stream()
                .anyMatch(entry -> entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0)) {
            throw new ServiceUnavailableException("Получен некорректный ответ поставщика счётчиков");
        }
        return counts;
    }
}
