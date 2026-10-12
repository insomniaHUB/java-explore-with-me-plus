package ru.practicum.ewm.requests;

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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.practicum.ewm.events.metrics.ConfirmedRequestsProvider;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.requests.service.RequestService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class RequestPostgresTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private RequestService requestService;
    @Autowired
    private ConfirmedRequestsProvider confirmedRequestsProvider;
    @MockBean
    private Clock eventClock;
    private long ownerId;
    private long categoryId;

    @BeforeEach
    void prepareDatabase() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).endsWith("_test");
        jdbc.update("DELETE FROM requests");
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM categories");
        jdbc.update("DELETE FROM users");
        when(eventClock.instant()).thenReturn(Instant.parse("2026-10-06T12:00:00.123456789Z"));
        when(eventClock.getZone()).thenReturn(ZoneOffset.UTC);
        ownerId = user("owner");
        categoryId = jdbc.queryForObject("INSERT INTO categories (name) VALUES ('Concerts') RETURNING id", Long.class);
    }

    @Test
    void confirmsUpToLimitAndRejectsRemainingPendingRequests() throws Exception {
        long eventId = event(2, true, "PUBLISHED");
        long first = createRequest(user("first"), eventId, "PENDING");
        long second = createRequest(user("second"), eventId, "PENDING");
        long third = createRequest(user("third"), eventId, "PENDING");

        JsonNode firstResult = changeStatus(eventId, "CONFIRMED", first);
        assertThat(firstResult.get("confirmedRequests").get(0).get("id").asLong()).isEqualTo(first);
        assertThat(statusInDb(third)).isEqualTo("PENDING");

        JsonNode secondResult = changeStatus(eventId, "CONFIRMED", second);
        assertThat(secondResult.get("confirmedRequests").get(0).get("id").asLong()).isEqualTo(second);
        assertThat(secondResult.get("rejectedRequests").size()).isZero();
        assertThat(statusInDb(third)).isEqualTo("REJECTED");
        assertThat(confirmedRequestsProvider.countConfirmedRequests(Set.of(eventId))).isEqualTo(Map.of(eventId, 2L));

        mvc.perform(patch("/users/{userId}/events/{eventId}/requests", ownerId, eventId)
                        .contentType(MediaType.APPLICATION_JSON).content(statusBody("CONFIRMED", third)))
                .andExpect(status().isConflict());
    }

    @Test
    void createdTimeIsKeptAfterCancelAndListDoesNotLoadAssociations() throws Exception {
        long eventId = event(0, true, "PUBLISHED");
        long requester = user("requester");
        JsonNode created = json(post("/users/{userId}/requests", requester).param("eventId", Long.toString(eventId)), 201);
        assertThat(created.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(created.get("created").asText()).isEqualTo("2026-10-06T12:00:00.123");
        createRequest(requester, event(0, true, "PUBLISHED"), "CONFIRMED");
        createRequest(requester, event(0, true, "PUBLISHED"), "CONFIRMED");

        JsonNode cancelled = json(patch("/users/{userId}/requests/{requestId}/cancel",
                requester, created.get("id").asLong()), 200);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELED");
        assertThat(cancelled.get("created").asText()).isEqualTo(created.get("created").asText());

        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        JsonNode own = json(get("/users/{userId}/requests", requester), 200);
        assertThat(own.size()).isEqualTo(3);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void concurrentRequestsDoNotExceedParticipantLimit() throws Exception {
        long eventId = event(1, false, "PUBLISHED");
        long first = user("first");
        long second = user("second");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (long requester : List.of(first, second)) {
                Callable<Boolean> task = () -> {
                    start.await();
                    try {
                        requestService.create(requester, eventId);
                        return true;
                    } catch (ConflictException e) {
                        return false;
                    }
                };
                results.add(executor.submit(task));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
        } catch (ExecutionException e) {
            throw new AssertionError(e.getCause());
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM requests WHERE event_id = ? AND status = 'CONFIRMED'",
                Long.class, eventId)).isEqualTo(1L);
    }

    private long user(String name) {
        return jdbc.queryForObject("INSERT INTO users (name, email) VALUES (?, ?) RETURNING id",
                Long.class, name, name + "@example.com");
    }

    private long event(int participantLimit, boolean requestModeration, String state) {
        return jdbc.queryForObject("""
                        INSERT INTO events (annotation, description, title, category_id, initiator_id, lat, lon,
                                            event_date, created_on, published_on, paid, participant_limit,
                                            request_moderation, state)
                        VALUES ('Annotation of the event', 'Description of the event', 'Event', ?, ?, 55.75, 37.62,
                                ?, ?, ?, FALSE, ?, ?, ?)
                        RETURNING id
                        """, Long.class, categoryId, ownerId, LocalDateTime.of(2026, 10, 10, 12, 0),
                LocalDateTime.of(2026, 10, 6, 10, 0), LocalDateTime.of(2026, 10, 6, 11, 0),
                participantLimit, requestModeration, state);
    }

    private long createRequest(long requester, long eventId, String expectedStatus) throws Exception {
        JsonNode created = json(post("/users/{userId}/requests", requester)
                .param("eventId", Long.toString(eventId)), 201);
        assertThat(created.get("status").asText()).isEqualTo(expectedStatus);
        return created.get("id").asLong();
    }

    private JsonNode changeStatus(long eventId, String newStatus, long requestId) throws Exception {
        return json(patch("/users/{userId}/events/{eventId}/requests", ownerId, eventId)
                .contentType(MediaType.APPLICATION_JSON).content(statusBody(newStatus, requestId)), 200);
    }

    private String statusBody(String newStatus, long requestId) {
        var body = objectMapper.createObjectNode().put("status", newStatus);
        body.putArray("requestIds").add(requestId);
        return body.toString();
    }

    private String statusInDb(long requestId) {
        return jdbc.queryForObject("SELECT status FROM requests WHERE id = ?", String.class, requestId);
    }

    private JsonNode json(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        String body = mvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}
