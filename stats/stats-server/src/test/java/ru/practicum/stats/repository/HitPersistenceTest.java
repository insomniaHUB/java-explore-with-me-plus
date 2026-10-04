package ru.practicum.stats.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.dto.EndpointHitDto;
import ru.practicum.stats.service.StatsService;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Запускать только с отдельной тестовой PostgreSQL: -Dstats.db.tests=true.
@EnabledIfSystemProperty(named = "stats.db.tests", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${STATS_TEST_DB_URL:jdbc:postgresql://localhost:5433/stats_get_test}",
        "spring.datasource.username=${STATS_TEST_DB_USER:dbuser}",
        "spring.datasource.password=${STATS_TEST_DB_PASSWORD:12345}"
})
@AutoConfigureMockMvc
@Transactional
class HitPersistenceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StatsService statsService;

    @Test
    void savesEveryHitWithGeneratedIdAndMakesItAvailableForStats() throws Exception {
        String body = "{\"app\":\"post-hit-test\",\"uri\":\"/hit-test/events/1\",\"ip\":\"192.163.0.1\","
                + "\"timestamp\":\"2022-09-06 11:00:23\"}";

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/hit").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(content().string(""));
        }

        List<EndpointHitDto> rows = jdbcTemplate.query(
                "SELECT id, app, uri, ip, timestamp FROM hits WHERE app = ? ORDER BY id",
                (rs, rowNum) -> new EndpointHitDto(rs.getLong("id"), rs.getString("app"),
                        rs.getString("uri"), rs.getString("ip"),
                        rs.getObject("timestamp", LocalDateTime.class)), "post-hit-test");
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(EndpointHitDto::id).doesNotHaveDuplicates().allMatch(id -> id > 0);
        assertThat(rows).allSatisfy(hit -> {
            assertThat(hit.app()).isEqualTo("post-hit-test");
            assertThat(hit.uri()).isEqualTo("/hit-test/events/1");
            assertThat(hit.ip()).isEqualTo("192.163.0.1");
            assertThat(hit.timestamp()).isEqualTo(LocalDateTime.of(2022, 9, 6, 11, 0, 23));
        });

        mockMvc.perform(get("/stats").param("start", "2022-09-06 11:00:23")
                        .param("end", "2022-09-06 11:00:23").param("uris", "/hit-test/events/1"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [{"app":"post-hit-test","uri":"/hit-test/events/1","hits":2}]
                        """, true));
    }

    @Test
    void clientIdCannotOverwriteExistingHitEvenWhenCallingServiceDirectly() throws Exception {
        Long existingId = jdbcTemplate.queryForObject("""
                INSERT INTO hits (app, uri, ip, timestamp)
                VALUES ('original-hit', '/original', '::1', '2022-09-06 11:00:23') RETURNING id
                """, Long.class);

        String body = "{\"id\":" + existingId + ",\"app\":\"http-hit\",\"uri\":\"/new\",\"ip\":\"::1\","
                + "\"timestamp\":\"2022-09-06 11:00:23\"}";
        mockMvc.perform(post("/hit").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        statsService.saveHit(new EndpointHitDto(existingId, "service-hit", "/new", "::1",
                LocalDateTime.of(2022, 9, 6, 11, 0, 23)));

        assertThat(jdbcTemplate.queryForObject("SELECT app FROM hits WHERE id = ?", String.class, existingId))
                .isEqualTo("original-hit");
        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM hits WHERE app IN ('http-hit', 'service-hit')", Long.class))
                .hasSize(2).doesNotContain(existingId).doesNotHaveDuplicates();
    }
}
