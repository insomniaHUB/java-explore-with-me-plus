package ru.practicum.ewm.requests.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.events.metrics.ConfirmedRequestsProvider;
import ru.practicum.ewm.requests.model.RequestStatus;
import ru.practicum.ewm.requests.repository.EventRequestCount;
import ru.practicum.ewm.requests.repository.RequestRepository;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RequestConfirmedRequestsProvider implements ConfirmedRequestsProvider {
    private final RequestRepository requestRepository;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Long> countConfirmedRequests(Set<Long> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return Map.of();
        }
        return requestRepository.countByEventIdsAndStatus(eventIds, RequestStatus.CONFIRMED).stream()
                .collect(Collectors.toMap(EventRequestCount::getEventId, EventRequestCount::getTotal));
    }
}
