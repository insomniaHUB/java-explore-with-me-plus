package ru.practicum.ewm.statistics;

import client.StatsClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import ru.practicum.dto.EndpointHitDto;
import ru.practicum.dto.ViewStatsDto;
import ru.practicum.ewm.exception.ServiceUnavailableException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EventStatisticsService {
    private static final String APP_NAME = "ewm-main-service";
    private static final TypeReference<List<ViewStatsDto>> STATS_TYPE = new TypeReference<>() {};

    private final StatsClient statsClient;
    private final ObjectMapper objectMapper;
    private final Clock eventClock;

    public void recordHit(String uri, String ip) {
        try {
            statsClient.save(new EndpointHitDto(APP_NAME, uri, ip, LocalDateTime.now(eventClock)));
        } catch (RestClientException exception) {
            throw new ServiceUnavailableException("Не удалось сохранить обращение в статистике", exception);
        }
    }

    public Map<Long, Long> countUniqueViews(Set<Long> eventIds) {
        return getViews(eventIds, LocalDateTime.of(1970, 1, 1, 0, 0), LocalDateTime.now(eventClock), true);
    }

    public Map<Long, Long> getViews(Collection<Long> eventIds,
                                     LocalDateTime start,
                                     LocalDateTime end,
                                     boolean unique) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }

        Map<String, Long> eventIdsByUri = new LinkedHashMap<>();
        Map<Long, Long> viewsByEventId = new LinkedHashMap<>();
        for (Long eventId : eventIds) {
            eventIdsByUri.put("/events/" + eventId, eventId);
            viewsByEventId.put(eventId, 0L);
        }

        List<ViewStatsDto> stats;
        try {
            Object response = statsClient.get(start, end, List.copyOf(eventIdsByUri.keySet()), unique).getBody();
            if (response == null) {
                throw new ServiceUnavailableException("Сервис статистики вернул пустое тело ответа");
            }
            stats = objectMapper.convertValue(response, STATS_TYPE);
        } catch (RestClientException | IllegalArgumentException exception) {
            throw new ServiceUnavailableException("Не удалось получить статистику просмотров", exception);
        }
        for (ViewStatsDto stat : stats) {
            if (stat == null || stat.app() == null || stat.uri() == null
                    || stat.hits() == null || stat.hits() < 0) {
                throw new ServiceUnavailableException("Сервис статистики вернул некорректный счётчик");
            }
            Long eventId = eventIdsByUri.get(stat.uri());
            if (eventId != null && APP_NAME.equals(stat.app())) {
                viewsByEventId.put(eventId, stat.hits());
            }
        }
        return viewsByEventId;
    }
}
