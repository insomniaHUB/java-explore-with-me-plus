package ru.practicum.stats.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.dto.EndpointHitDto;
import ru.practicum.dto.ViewStatsDto;
import ru.practicum.stats.mapper.EndpointHitMapper;
import ru.practicum.stats.repository.HitRepository;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class StatsService {

    private final HitRepository hitRepository;

    public StatsService(HitRepository hitRepository) {
        this.hitRepository = hitRepository;
    }

    @Transactional
    public void saveHit(EndpointHitDto hit) {
        hitRepository.save(EndpointHitMapper.toEntity(hit));
    }

    @Transactional(readOnly = true)
    public List<ViewStatsDto> getStats(
            LocalDateTime start,
            LocalDateTime end,
            List<String> uris,
            boolean unique) {

        if (start.isAfter(end)) {
            throw new IllegalArgumentException("Начало периода должно быть раньше или равно окончанию");
        }

        boolean hasUris = uris != null && !uris.isEmpty();

        if (!hasUris && !unique) {
            return hitRepository.findStats(start, end);
        }

        if (!hasUris) {
            return hitRepository.findUniqueStats(start, end);
        }

        if (!unique) {
            return hitRepository.findStatsByUris(start, end, uris);
        }

        return hitRepository.findUniqueStatsByUris(start, end, uris);

    }
}
