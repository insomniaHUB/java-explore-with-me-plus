package ru.practicum.ewm.comments;

import client.StatsClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.ewm.events.model.EventState;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "events.db.tests", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${PUBLIC_TEST_DB_URL:jdbc:postgresql://localhost:55434/public_events_test}",
        "spring.datasource.username=${EVENTS_TEST_DB_USER:dbuser}",
        "spring.datasource.password=${EVENTS_TEST_DB_PASSWORD:12345}",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@AutoConfigureMockMvc
class PublicCommentsPostgresTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 12, 0);
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @MockBean
    private StatsClient statsClient;
    private long ownerId;
    private long categoryId;
    private long eventId;

    @BeforeEach
    void prepareDatabase() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).endsWith("_test");
        jdbc.update("DELETE FROM compilation_events");
        jdbc.update("DELETE FROM compilations");
        jdbc.update("DELETE FROM comments");
        jdbc.update("DELETE FROM requests");
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM categories");
        jdbc.update("DELETE FROM users");
        ownerId = createUser("Owner");
        categoryId = jdbc.queryForObject("INSERT INTO categories(name) VALUES ('Music') RETURNING id", Long.class);
        eventId = createEvent(EventState.PUBLISHED);
    }

    @Test
    void returnsOnlyRequestedEventNewestFirstWithAuthorNames() throws Exception {
        long older = createComment(eventId, createUser("First"), "Older", NOW.minusDays(1));
        long newer = createComment(eventId, createUser("Second"), "Newer", NOW);
        createComment(createEvent(EventState.PUBLISHED), ownerId, "Another event", NOW.plusDays(1));

        mvc.perform(get("/events/{id}/comments", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(newer))
                .andExpect(jsonPath("$[0].text").value("Newer"))
                .andExpect(jsonPath("$[0].authorName").value("Second"))
                .andExpect(jsonPath("$[0].eventId").value(eventId))
                .andExpect(jsonPath("$[0].created").value("2026-10-14 12:00:00"))
                .andExpect(jsonPath("$[1].id").value(older))
                .andExpect(jsonPath("$[1].authorName").value("First"));
        verifyNoInteractions(statsClient);
    }

    @Test
    void usesExactOffsetAndStableIdOrderWhenDatesMatch() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(createComment(eventId, ownerId, "Comment " + i, NOW));
        }

        mvc.perform(get("/events/{id}/comments", eventId).param("from", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(ids.get(4)))
                .andExpect(jsonPath("$[1].id").value(ids.get(3)));
    }

    @Test
    void eventWithoutCommentsReturnsEmptyList() throws Exception {
        mvc.perform(get("/events/{id}/comments", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void offsetBeyondResultsReturnsEmptyList() throws Exception {
        createComment(eventId, ownerId, "A comment", NOW);

        mvc.perform(get("/events/{id}/comments", eventId).param("from", "99").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @ParameterizedTest
    @EnumSource(value = EventState.class, names = {"PENDING", "CANCELED"})
    void doesNotExposeCommentsOfUnpublishedEvents(EventState state) throws Exception {
        long hiddenEventId = createEvent(state);
        createComment(hiddenEventId, ownerId, "Hidden comment", NOW);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        mvc.perform(get("/events/{id}/comments", hiddenEventId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        verifyNoInteractions(statsClient);
    }

    @Test
    void missingEventReturns404() throws Exception {
        mvc.perform(get("/events/999999/comments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));
    }

    @Test
    void queryCountDoesNotGrowWithPageSizeOrNumberOfAuthors() throws Exception {
        for (int i = 0; i < 5; i++) {
            createComment(eventId, createUser("Author" + i), "Comment " + i, NOW.plusSeconds(i));
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        mvc.perform(get("/events/{id}/comments", eventId).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);

        statistics.clear();
        mvc.perform(get("/events/{id}/comments", eventId).param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        verifyNoInteractions(statsClient);
    }

    @Test
    void newlyCreatedCommentIsVisibleThroughPublicGet() throws Exception {
        long authorId = createUser("Reader");
        JsonNode created = objectMapper.readTree(mvc.perform(post("/users/{id}/comments", authorId)
                        .param("eventId", String.valueOf(eventId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"A new comment\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        mvc.perform(get("/events/{id}/comments", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(created.path("id").asLong()))
                .andExpect(jsonPath("$[0].text").value("A new comment"))
                .andExpect(jsonPath("$[0].authorName").value("Reader"));
        verifyNoInteractions(statsClient);
    }

    private long createUser(String name) {
        return jdbc.queryForObject("INSERT INTO users(name, email) VALUES (?, ?) RETURNING id", Long.class,
                name, name.toLowerCase() + "@test.ru");
    }

    private long createEvent(EventState state) {
        return jdbc.queryForObject("""
                INSERT INTO events(annotation, description, title, category_id, initiator_id, lat, lon,
                                   event_date, created_on, published_on, state)
                VALUES ('An annotation long enough for validation', 'A description long enough for validation',
                        'Test event', ?, ?, 1, 2, ?, ?, ?, ?) RETURNING id
                """, Long.class, categoryId, ownerId, NOW.plusDays(2), NOW.minusDays(1),
                state == EventState.PUBLISHED ? NOW : null, state.name());
    }

    private long createComment(long targetEventId, long authorId, String text, LocalDateTime created) {
        return jdbc.queryForObject("""
                INSERT INTO comments(text, event_id, author_id, created_on) VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, text, targetEventId, authorId, created);
    }
}
