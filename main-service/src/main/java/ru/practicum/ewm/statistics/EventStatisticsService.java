package ru.practicum.ewm.statistics;

import client.StatsClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.practicum.dto.EndpointHitDto;
import ru.practicum.dto.ViewStatsDto;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EventStatisticsService {
    private static final String APP_NAME = "ewm-main-service";
    private static final TypeReference<List<ViewStatsDto>> STATS_TYPE = new TypeReference<>() {};

    private final StatsClient statsClient;
    private final ObjectMapper objectMapper;

    public void recordHit(String uri, String ip) {
        statsClient.save(new EndpointHitDto(APP_NAME, uri, ip, LocalDateTime.now()));
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

        Object response = statsClient.get(start, end, List.copyOf(eventIdsByUri.keySet()), unique).getBody();
        if (response == null) {
            throw new IllegalStateException("Сервис статистики вернул пустое тело ответа");
        }
        List<ViewStatsDto> stats = objectMapper.convertValue(response, STATS_TYPE);
        for (ViewStatsDto stat : stats) {
            Long eventId = eventIdsByUri.get(stat.uri());
            if (eventId != null && APP_NAME.equals(stat.app())) {
                viewsByEventId.put(eventId, stat.hits());
            }
        }
        return viewsByEventId;
    }
}
