package ru.practicum.stats.repository;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.dto.ViewStatsDto;
import ru.practicum.stats.service.StatsService;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Отдельная тестовая PostgreSQL; включается параметром -Dstats.db.tests=true.
@EnabledIfSystemProperty(named = "stats.db.tests", matches = "true")
@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=${STATS_TEST_DB_URL:jdbc:postgresql://localhost:5433/stats_get_test}",
        "spring.datasource.username=${STATS_TEST_DB_USER:dbuser}",
        "spring.datasource.password=${STATS_TEST_DB_PASSWORD:12345}",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(StatsService.class)
class HitRepositoryTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 1, 10, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 1, 12, 0);

    @Autowired
    private StatsService statsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void prepareHits() {
        jdbcTemplate.update("""
                INSERT INTO hits (app, uri, ip, timestamp) VALUES
                    ('app-a', '/events/1', '192.0.2.1', '2026-10-01 10:00:00'),
                    ('app-a', '/events/1', '192.0.2.1', '2026-10-01 11:00:00'),
                    ('app-a', '/events/1', '192.0.2.2', '2026-10-01 12:00:00'),
                    ('app-a', '/events/2', '192.0.2.1', '2026-10-01 10:30:00'),
                    ('app-a', '/events/2', '192.0.2.1', '2026-10-01 11:30:00'),
                    ('app-b', '/events/1', '192.0.2.1', '2026-10-01 11:00:00'),
                    ('app-a', '/events/1', '192.0.2.3', '2026-10-01 09:59:59'),
                    ('app-a', '/events/1', '192.0.2.4', '2026-10-01 12:00:01'),
                    ('app-a', '/outside', '192.0.2.1', '2026-10-01 09:00:00')
                """);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aggregatesByAppAndUriWithinInclusivePeriod(boolean unique) {
        List<ViewStatsDto> result = statsService.getStats(START, END, null, unique);

        assertThat(result).containsExactlyInAnyOrder(
                new ViewStatsDto("app-a", "/events/1", unique ? 2L : 3L),
                new ViewStatsDto("app-a", "/events/2", unique ? 1L : 2L),
                new ViewStatsDto("app-b", "/events/1", 1L));
        assertThat(result).extracting(ViewStatsDto::hits).isSortedAccordingTo(Comparator.reverseOrder());
        assertSingleQueryWithoutLoadingHits();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void filtersByUriWithoutCombiningApps(boolean unique) {
        List<ViewStatsDto> result = statsService.getStats(START, END, List.of("/events/1"), unique);

        assertThat(result).containsExactly(
                new ViewStatsDto("app-a", "/events/1", unique ? 2L : 3L),
                new ViewStatsDto("app-b", "/events/1", 1L));
        assertSingleQueryWithoutLoadingHits();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsMultipleUrisAndIgnoresUnknownUri(boolean unique) {
        List<ViewStatsDto> result = statsService.getStats(
                START, END, List.of("/events/2", "/missing"), unique);

        assertThat(result).containsExactly(new ViewStatsDto("app-a", "/events/2", unique ? 1L : 2L));
        assertSingleQueryWithoutLoadingHits();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void returnsEmptyListForUnknownUri(boolean unique) {
        assertThat(statsService.getStats(START, END, List.of("/missing"), unique)).isEmpty();

        assertSingleQueryWithoutLoadingHits();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void treatsEmptyUriListAsNoFilter(boolean unique) {
        List<ViewStatsDto> result = statsService.getStats(START, END, List.of(), unique);

        assertThat(result).containsExactlyInAnyOrder(
                new ViewStatsDto("app-a", "/events/1", unique ? 2L : 3L),
                new ViewStatsDto("app-a", "/events/2", unique ? 1L : 2L),
                new ViewStatsDto("app-b", "/events/1", 1L));
        assertSingleQueryWithoutLoadingHits();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsEqualPeriodBoundaries(boolean unique) {
        assertThat(statsService.getStats(START, START, null, unique))
                .containsExactly(new ViewStatsDto("app-a", "/events/1", 1L));

        assertSingleQueryWithoutLoadingHits();
    }

    @Test
    void rejectsReversedPeriodWithoutQueryingDatabase() {
        assertThatThrownBy(() -> statsService.getStats(END, START, null, false))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    private void assertSingleQueryWithoutLoadingHits() {
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }
}
