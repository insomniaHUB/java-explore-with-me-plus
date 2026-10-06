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
        if (eventIds.isEmpty()) {
            return new EventMetrics(Map.of(), Map.of());
        }
        ConfirmedRequestsProvider requests = requestsProvider.getIfAvailable();
        EventViewsProvider views = viewsProvider.getIfAvailable();
        if (requests == null || views == null) {
            throw new ServiceUnavailableException("Не подключены поставщики счётчиков заявок и просмотров");
        }
        try {
            return new EventMetrics(checkCounts(requests.countConfirmedRequests(eventIds)),
                    checkCounts(views.countUniqueViews(eventIds)));
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
