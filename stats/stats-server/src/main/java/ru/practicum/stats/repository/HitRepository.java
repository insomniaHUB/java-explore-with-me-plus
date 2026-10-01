package ru.practicum.stats.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.practicum.dto.ViewStatsDto;
import ru.practicum.stats.model.EndpointHit;

import java.time.LocalDateTime;
import java.util.List;

public interface HitRepository extends JpaRepository<EndpointHit, Long> {
    @Query("""
            SELECT new ru.practicum.dto.ViewStatsDto(h.app, h.uri, COUNT(h))
            FROM EndpointHit h
            WHERE h.timestamp >= :start AND h.timestamp <= :end
            GROUP BY h.app, h.uri
            ORDER BY COUNT(h) DESC
            """)
    List<ViewStatsDto> findStats(@Param("start") LocalDateTime start,
                                 @Param("end") LocalDateTime end);

    @Query("""
            SELECT new ru.practicum.dto.ViewStatsDto(h.app, h.uri, COUNT(DISTINCT h.ip))
            FROM EndpointHit h
            WHERE h.timestamp >= :start AND h.timestamp <= :end
            GROUP BY h.app, h.uri
            ORDER BY COUNT(DISTINCT h.ip) DESC
            """)
    List<ViewStatsDto> findUniqueStats(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    @Query("""
            SELECT new ru.practicum.dto.ViewStatsDto(h.app, h.uri, COUNT(h))
            FROM EndpointHit h
            WHERE h.timestamp >= :start AND h.timestamp <= :end AND h.uri IN :uris
            GROUP BY h.app, h.uri
            ORDER BY COUNT(h) DESC
            """)
    List<ViewStatsDto> findStatsByUris(@Param("start") LocalDateTime start,
                                        @Param("end") LocalDateTime end,
                                        @Param("uris") List<String> uris);

    @Query("""
            SELECT new ru.practicum.dto.ViewStatsDto(h.app, h.uri, COUNT(DISTINCT h.ip))
            FROM EndpointHit h
            WHERE h.timestamp >= :start AND h.timestamp <= :end AND h.uri IN :uris
            GROUP BY h.app, h.uri
            ORDER BY COUNT(DISTINCT h.ip) DESC
            """)
    List<ViewStatsDto> findUniqueStatsByUris(@Param("start") LocalDateTime start,
                                        @Param("end") LocalDateTime end,
                                        @Param("uris") List<String> uris);
}
