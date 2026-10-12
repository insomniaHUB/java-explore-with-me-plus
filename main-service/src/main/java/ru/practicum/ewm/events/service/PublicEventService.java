package ru.practicum.ewm.events.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.events.dto.EventFullDto;
import ru.practicum.ewm.events.dto.EventShortDto;
import ru.practicum.ewm.events.dto.PublicEventSearchRequest;
import ru.practicum.ewm.events.dto.PublicEventSort;
import ru.practicum.ewm.events.mapper.EventMapper;
import ru.practicum.ewm.events.metrics.EventMetrics;
import ru.practicum.ewm.events.metrics.EventMetricsService;
import ru.practicum.ewm.events.model.Event;
import ru.practicum.ewm.events.model.EventState;
import ru.practicum.ewm.events.repository.EventRepository;
import ru.practicum.ewm.events.repository.PublicEventCandidate;
import ru.practicum.ewm.events.repository.PublicEventQueryRepository;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.statistics.EventStatisticsService;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicEventService {
    private final PublicEventQueryRepository queryRepository;
    private final EventRepository eventRepository;
    private final EventMapper eventMapper;
    private final EventMetricsService metricsService;
    private final EventStatisticsService statisticsService;
    private final Clock eventClock;

    public List<EventShortDto> find(PublicEventSearchRequest request, String ip) {
        List<EventShortDto> result;
        if (!request.isOnlyAvailable() && request.getSort() == PublicEventSort.EVENT_DATE) {
            List<Event> events = queryRepository.findPage(request, LocalDateTime.now(eventClock));
            EventMetrics metrics = metricsService.load(eventIds(events));
            result = shortDtos(events, metrics);
        } else {
            result = findWithCounters(request);
        }
        statisticsService.recordHit("/events", ip);
        return result;
    }

    public EventFullDto get(Long id, String ip) {
        Event event = eventRepository.findById(id).filter(found -> found.getState() == EventState.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("Опубликованное событие не найдено"));
        // Текущий просмотр должен учитываться в ответе; повторный IP не увеличивает unique-счётчик.
        statisticsService.recordHit("/events/" + id, ip);
        EventMetrics metrics = metricsService.load(Set.of(id));
        return eventMapper.toFullDto(event, metrics.confirmed(id), metrics.views(id));
    }

    private List<EventShortDto> findWithCounters(PublicEventSearchRequest request) {
        List<PublicEventCandidate> candidates = queryRepository.findCandidates(request, LocalDateTime.now(eventClock));
        Set<Long> ids = candidates.stream().map(PublicEventCandidate::id).collect(Collectors.toSet());
        Map<Long, Long> confirmed = request.isOnlyAvailable() ? metricsService.loadConfirmedRequests(ids) : Map.of();
        List<PublicEventCandidate> available = candidates.stream()
                .filter(event -> !request.isOnlyAvailable() || event.participantLimit() == 0
                        || confirmed.getOrDefault(event.id(), 0L) < event.participantLimit())
                .toList();
        if (request.getFrom() >= available.size()) {
            return List.of();
        }
        Set<Long> availableIds = available.stream().map(PublicEventCandidate::id).collect(Collectors.toSet());
        Map<Long, Long> views = request.getSort() == PublicEventSort.VIEWS
                ? metricsService.loadViews(availableIds) : Map.of();
        Comparator<PublicEventCandidate> order = Comparator.comparing(PublicEventCandidate::eventDate)
                .thenComparing(PublicEventCandidate::id);
        if (request.getSort() == PublicEventSort.VIEWS) {
            order = Comparator.<PublicEventCandidate>comparingLong(event -> views.getOrDefault(event.id(), 0L))
                    .reversed().thenComparing(order);
        }
        List<Long> pageIds = available.stream().sorted(order).skip(request.getFrom()).limit(request.getSize())
                .map(PublicEventCandidate::id).toList();
        if (pageIds.isEmpty()) {
            return List.of();
        }
        Set<Long> pageIdSet = Set.copyOf(pageIds);
        EventMetrics metrics = new EventMetrics(request.isOnlyAvailable() ? confirmed
                : metricsService.loadConfirmedRequests(pageIdSet), request.getSort() == PublicEventSort.VIEWS ? views
                : metricsService.loadViews(pageIdSet));
        Map<Long, Event> events = eventRepository.findByIdIn(pageIdSet).stream()
                .collect(Collectors.toMap(Event::getId, event -> event));
        List<Event> page = pageIds.stream().map(events::get).filter(Objects::nonNull).toList();
        return shortDtos(page, metrics);
    }

    private Set<Long> eventIds(List<Event> events) {
        return events.stream().map(Event::getId).collect(Collectors.toSet());
    }

    private List<EventShortDto> shortDtos(List<Event> events, EventMetrics metrics) {
        return events.stream().map(event -> eventMapper.toShortDto(event,
                metrics.confirmed(event.getId()), metrics.views(event.getId()))).toList();
    }
}
