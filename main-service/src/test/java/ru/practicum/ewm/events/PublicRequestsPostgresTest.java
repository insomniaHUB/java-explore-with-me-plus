package ru.practicum.ewm.events;

import client.StatsClient;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.practicum.dto.EndpointHitDto;
import ru.practicum.ewm.events.metrics.ConfirmedRequestsProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
class PublicRequestsPostgresTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ConfirmedRequestsProvider confirmedRequests;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @MockBean
    private StatsClient statsClient;
    @MockBean
    private Clock eventClock;
    private final List<EndpointHitDto> hits = new ArrayList<>();
    private long ownerId;
    private long categoryId;

    @BeforeEach
    void prepareDatabaseAndStatisticsTransport() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).endsWith("_test");
        jdbc.update("DELETE FROM compilation_events");
        jdbc.update("DELETE FROM compilations");
        jdbc.update("DELETE FROM requests");
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM categories");
        jdbc.update("DELETE FROM users");
        hits.clear();
        when(eventClock.instant()).thenReturn(Instant.parse("2026-10-08T12:00:00Z"));
        when(eventClock.getZone()).thenReturn(ZoneOffset.UTC);
        when(statsClient.save(any())).thenAnswer(invocation -> {
            hits.add(invocation.getArgument(0));
            return ResponseEntity.status(201).build();
        });
        when(statsClient.get(any(), any(), anyList(), anyBoolean())).thenAnswer(invocation -> {
            assertThat((Boolean) invocation.getArgument(3)).isTrue();
            List<String> uris = invocation.getArgument(2);
            List<Map<String, Object>> response = new ArrayList<>();
            for (String uri : uris) {
                long count = hits.stream().filter(hit -> uri.equals(hit.uri()))
                        .map(EndpointHitDto::ip).distinct().count();
                if (count > 0) {
                    response.add(Map.of("app", "ewm-main-service", "uri", uri, "hits", count));
                }
            }
            return ResponseEntity.ok(response);
        });
        assertThat(mockingDetails(confirmedRequests).isMock()).isFalse();
        ownerId = createUser("Owner");
        categoryId = json(post("/admin/categories"), Map.of("name", "Music"), 201).path("id").asLong();
    }

    @Test
    void confirmationAndCancellationUpdateAvailabilityAndCompilationCounters() throws Exception {
        long eventId = createPublishedEvent("Request integration concert", 1, true);
        long guestId = createUser("Guest");
        long otherId = createUser("Other");
        long requestId = createRequest(guestId, eventId).path("id").asLong();
        long otherRequestId = createRequest(otherId, eventId).path("id").asLong();

        mvc.perform(get("/events").param("onlyAvailable", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        moderate(eventId, requestId, "CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT status FROM requests WHERE id = ?", String.class, otherRequestId))
                .isEqualTo("REJECTED");

        mvc.perform(get("/events/{id}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedRequests").value(1))
                .andExpect(jsonPath("$.views").value(1));
        mvc.perform(get("/events").param("onlyAvailable", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        JsonNode compilation = json(post("/admin/compilations"),
                Map.of("title", "Concerts", "events", List.of(eventId)), 201);
        assertThat(compilation.path("events").get(0).path("confirmedRequests").asLong()).isEqualTo(1);
        JsonNode updated = json(patch("/admin/events/{id}", eventId), Map.of("title", "Updated concert"), 200);
        assertThat(updated.path("confirmedRequests").asLong()).isEqualTo(1);

        mvc.perform(patch("/users/{userId}/requests/{requestId}/cancel", guestId, requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
        mvc.perform(get("/events").param("onlyAvailable", "true").param("sort", "VIEWS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].confirmedRequests").value(0));
        mvc.perform(get("/compilations/{id}", compilation.path("id").asLong()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].confirmedRequests").value(0));
    }

    @Test
    void realConfirmedProviderUsesOneSqlAndCountsOnlyConfirmedRequests() throws Exception {
        long first = createPublishedEvent("First concert", 5, true);
        long second = createPublishedEvent("Second concert", 0, false);
        long third = createPublishedEvent("Third concert", 5, true);
        long guestId = createUser("Guest");
        long otherId = createUser("Other");
        long confirmedId = createRequest(guestId, first).path("id").asLong();
        moderate(first, confirmedId, "CONFIRMED");
        assertThat(createRequest(otherId, first).path("status").asText()).isEqualTo("PENDING");
        assertThat(createRequest(guestId, second).path("status").asText()).isEqualTo("CONFIRMED");
        long canceledId = createRequest(otherId, second).path("id").asLong();
        mvc.perform(patch("/users/{userId}/requests/{requestId}/cancel", otherId, canceledId))
                .andExpect(status().isOk());
        long rejectedId = createRequest(guestId, third).path("id").asLong();
        moderate(third, rejectedId, "REJECTED");

        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        clearInvocations(statsClient);

        Map<Long, Long> counts = confirmedRequests.countConfirmedRequests(Set.of(first, second, third, 999999L));

        assertThat(counts).isEqualTo(Map.of(first, 1L, second, 1L));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
        verifyNoInteractions(statsClient);
    }

    private long createUser(String name) throws Exception {
        return json(post("/admin/users"), Map.of("name", name, "email", name.toLowerCase() + "@test.ru"), 201)
                .path("id").asLong();
    }

    private long createPublishedEvent(String title, int limit, boolean moderation) throws Exception {
        Map<String, Object> body = Map.of(
                "annotation", "A concert with enough text for validation",
                "description", "A detailed concert description for integration tests",
                "category", categoryId,
                "title", title,
                "location", Map.of("lat", 1.0, "lon", 2.0),
                "eventDate", "2026-10-10 12:00:00",
                "paid", false,
                "participantLimit", limit,
                "requestModeration", moderation);
        long id = json(post("/users/{userId}/events", ownerId), body, 201).path("id").asLong();
        JsonNode published = json(patch("/admin/events/{eventId}", id), Map.of("stateAction", "PUBLISH_EVENT"), 200);
        assertThat(published.path("state").asText()).isEqualTo("PUBLISHED");
        return id;
    }

    private JsonNode createRequest(long userId, long eventId) throws Exception {
        return json(post("/users/{userId}/requests", userId).param("eventId", String.valueOf(eventId)), null, 201);
    }

    private void moderate(long eventId, long requestId, String status) throws Exception {
        json(patch("/users/{userId}/events/{eventId}/requests", ownerId, eventId),
                Map.of("requestIds", List.of(requestId), "status", status), 200);
    }

    private JsonNode json(MockHttpServletRequestBuilder request, Object body, int expectedStatus) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return objectMapper.readTree(mvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString());
    }
}
