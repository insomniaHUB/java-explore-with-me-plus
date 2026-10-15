package ru.practicum.ewm.comments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "events.db.tests", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${EVENTS_TEST_DB_URL:jdbc:postgresql://localhost:55434/events_test}",
        "spring.datasource.username=${EVENTS_TEST_DB_USER:dbuser}",
        "spring.datasource.password=${EVENTS_TEST_DB_PASSWORD:12345}",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@AutoConfigureMockMvc
class AdminCommentPostgresTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    private long firstAuthor;
    private long secondAuthor;
    private long firstEvent;
    private long secondEvent;

    @BeforeEach
    void prepareDatabase() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).endsWith("_test");
        jdbc.update("DELETE FROM comments");
        jdbc.update("DELETE FROM requests");
        jdbc.update("DELETE FROM compilation_events");
        jdbc.update("DELETE FROM compilations");
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM categories");
        jdbc.update("DELETE FROM users");
        long owner = user("owner");
        firstAuthor = user("first");
        secondAuthor = user("second");
        long category = jdbc.queryForObject("INSERT INTO categories (name) VALUES ('Concerts') RETURNING id", Long.class);
        firstEvent = event(owner, category);
        secondEvent = event(owner, category);
    }

    @Test
    void filtersByAuthorEventAndDateRangeWithExactOffset() throws Exception {
        long oldest = comment(firstAuthor, firstEvent, LocalDateTime.of(2026, 10, 1, 10, 0));
        long middle = comment(firstAuthor, secondEvent, LocalDateTime.of(2026, 10, 2, 10, 0));
        long newest = comment(secondAuthor, firstEvent, LocalDateTime.of(2026, 10, 3, 10, 0));

        assertThat(ids(get("/admin/comments"))).containsExactly(newest, middle, oldest);
        assertThat(ids(get("/admin/comments").param("users", Long.toString(firstAuthor))))
                .containsExactly(middle, oldest);
        assertThat(ids(get("/admin/comments").param("events", Long.toString(firstEvent))))
                .containsExactly(newest, oldest);
        assertThat(ids(get("/admin/comments")
                .param("rangeStart", "2026-10-02 10:00:00")
                .param("rangeEnd", "2026-10-03 10:00:00")))
                .containsExactly(newest, middle);
        assertThat(ids(get("/admin/comments").param("from", "1").param("size", "1")))
                .containsExactly(middle);
    }

    @Test
    void listLoadsAuthorsWithoutAdditionalQueries() throws Exception {
        comment(firstAuthor, firstEvent, LocalDateTime.of(2026, 10, 1, 10, 0));
        comment(secondAuthor, firstEvent, LocalDateTime.of(2026, 10, 2, 10, 0));
        comment(firstAuthor, secondEvent, LocalDateTime.of(2026, 10, 3, 10, 0));
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        JsonNode result = json(get("/admin/comments"), 200);

        assertThat(result.size()).isEqualTo(3);
        assertThat(result.get(0).get("authorName").asText()).isEqualTo("first");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void deletesAnyCommentAndReturns404Afterwards() throws Exception {
        long commentId = comment(secondAuthor, firstEvent, LocalDateTime.of(2026, 10, 1, 10, 0));

        mvc.perform(delete("/admin/comments/{commentId}", commentId)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE id = ?", Long.class, commentId))
                .isZero();
        mvc.perform(delete("/admin/comments/{commentId}", commentId)).andExpect(status().isNotFound());
        mvc.perform(get("/admin/comments/{commentId}", commentId)).andExpect(status().isNotFound());
    }

    private long user(String name) {
        return jdbc.queryForObject("INSERT INTO users (name, email) VALUES (?, ?) RETURNING id",
                Long.class, name, name + "@example.com");
    }

    private long event(long initiator, long category) {
        return jdbc.queryForObject("""
                        INSERT INTO events (annotation, description, title, category_id, initiator_id, lat, lon,
                                            event_date, created_on, published_on, paid, participant_limit,
                                            request_moderation, state)
                        VALUES ('Annotation of the event', 'Description of the event', 'Event', ?, ?, 55.75, 37.62,
                                ?, ?, ?, FALSE, 0, TRUE, 'PUBLISHED')
                        RETURNING id
                        """, Long.class, category, initiator, LocalDateTime.of(2026, 12, 1, 12, 0),
                LocalDateTime.of(2026, 9, 1, 10, 0), LocalDateTime.of(2026, 9, 1, 11, 0));
    }

    private long comment(long author, long eventId, LocalDateTime created) {
        return jdbc.queryForObject("""
                        INSERT INTO comments (text, event_id, author_id, created_on)
                        VALUES ('Comment text', ?, ?, ?)
                        RETURNING id
                        """, Long.class, eventId, author, created);
    }

    private List<Long> ids(MockHttpServletRequestBuilder request) throws Exception {
        JsonNode result = json(request, 200);
        List<Long> ids = new ArrayList<>();
        result.forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }

    private JsonNode json(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        String body = mvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}
