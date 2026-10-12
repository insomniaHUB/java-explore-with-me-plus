package ru.practicum.ewm.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import ru.practicum.ewm.events.metrics.ConfirmedRequestsProvider;
import ru.practicum.ewm.events.metrics.EventViewsProvider;
import ru.practicum.ewm.events.repository.EventRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Нужна отдельная БД с именем, заканчивающимся на _test. Данные в ней очищаются перед каждым тестом.
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
class EventPostgresTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockBean
    private ConfirmedRequestsProvider confirmedRequests;
    @MockBean
    private EventViewsProvider views;
    @MockBean
    private Clock eventClock;
    private long userId;
    private long categoryId;

    @BeforeEach
    void prepareDatabase() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).endsWith("_test");
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM categories");
        jdbc.update("DELETE FROM users");
        when(eventClock.instant()).thenReturn(Instant.parse("2026-10-06T12:00:00Z"));
        when(eventClock.getZone()).thenReturn(ZoneOffset.UTC);
        when(confirmedRequests.countConfirmedRequests(anySet())).thenReturn(Map.of());
        when(views.countUniqueViews(anySet())).thenReturn(Map.of());
        userId = response(post("/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Event owner\",\"email\":\"owner@example.com\"}"), 201).get("id").asLong();
        categoryId = response(post("/admin/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Concerts\"}"), 201).get("id").asLong();
    }

    @Test
    void createsAndCommitsEventWithDefaultsWithoutCallingProviders() throws Exception {
        JsonNode event = create(userId, eventBody("First event"));
        long id = event.get("id").asLong();
        assertThat(event.get("state").asText()).isEqualTo("PENDING");
        assertThat(event.get("paid").asBoolean()).isFalse();
        assertThat(event.get("participantLimit").asInt()).isZero();
        assertThat(event.get("requestModeration").asBoolean()).isTrue();
        assertThat(event.get("views").asLong()).isZero();
        assertThat(event.get("confirmedRequests").asLong()).isZero();
        assertThat(event.get("createdOn").asText()).isEqualTo("2026-10-06 12:00:00");
        assertThat(event.get("publishedOn").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT initiator_id FROM events WHERE id = ?", Long.class, id))
                .isEqualTo(userId);
        assertThat(jdbc.queryForObject("SELECT category_id FROM events WHERE id = ?", Long.class, id))
                .isEqualTo(categoryId);
        assertThat(jdbc.queryForObject("SELECT event_date FROM events WHERE id = ?", LocalDateTime.class, id))
                .isEqualTo(LocalDateTime.of(2026, 10, 7, 12, 0));
        verifyNoInteractions(confirmedRequests, views);
    }

    @Test
    void completesPrivateAndAdminLifecycleWithCounters() throws Exception {
        long id = create(userId, eventBody("Lifecycle event")).get("id").asLong();
        when(confirmedRequests.countConfirmedRequests(Set.of(id))).thenReturn(Map.of(id, 2L));
        when(views.countUniqueViews(Set.of(id))).thenReturn(Map.of(id, 7L));
        JsonNode cancelled = response(patch("/users/{userId}/events/{id}", userId, id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"stateAction\":\"CANCEL_REVIEW\"}"), 200);
        assertThat(cancelled.get("state").asText()).isEqualTo("CANCELED");
        JsonNode pending = response(patch("/users/{userId}/events/{id}", userId, id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"stateAction\":\"SEND_TO_REVIEW\"}"), 200);
        assertThat(pending.get("state").asText()).isEqualTo("PENDING");
        JsonNode published = response(patch("/admin/events/{id}", id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"stateAction\":\"PUBLISH_EVENT\"}"), 200);
        assertThat(published.get("state").asText()).isEqualTo("PUBLISHED");
        assertThat(published.get("publishedOn").asText()).isEqualTo("2026-10-06 12:00:00");
        JsonNode own = response(get("/users/{userId}/events/{id}", userId, id), 200);
        assertThat(own.get("confirmedRequests").asLong()).isEqualTo(2);
        assertThat(own.get("views").asLong()).isEqualTo(7);
        mvc.perform(patch("/users/{userId}/events/{id}", userId, id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"New title\"}"))
                .andExpect(status().isConflict());
        mvc.perform(patch("/admin/events/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stateAction\":\"REJECT_EVENT\"}"))
                .andExpect(status().isConflict());
        mvc.perform(patch("/admin/events/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stateAction\":\"PUBLISH_EVENT\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void updatesFalseZeroCategoryAndLocationWhileNullFieldsStayUnchanged() throws Exception {
        ObjectNode body = eventBody("Original title").put("paid", true).put("participantLimit", 10);
        long id = create(userId, body).get("id").asLong();
        long otherCategory = response(post("/admin/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Theatre\"}"), 201).get("id").asLong();
        ObjectNode update = objectMapper.createObjectNode().putNull("title").put("paid", false)
                .put("participantLimit", 0).put("requestModeration", false).put("category", otherCategory);
        update.putObject("location").put("lat", 10).put("lon", 20);
        JsonNode result = response(patch("/users/{userId}/events/{id}", userId, id)
                .contentType(MediaType.APPLICATION_JSON).content(update.toString()), 200);
        assertThat(result.get("title").asText()).isEqualTo("Original title");
        assertThat(result.get("paid").asBoolean()).isFalse();
        assertThat(result.get("requestModeration").asBoolean()).isFalse();
        assertThat(result.get("location").get("lat").asDouble()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT participant_limit FROM events WHERE id = ?", Integer.class, id))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT category_id FROM events WHERE id = ?", Long.class, id))
                .isEqualTo(otherCategory);
    }

    @Test
    void offsetIsExactListsAreBatchedAndAssociationsDoNotCauseNPlusOne() throws Exception {
        long first = create(userId, eventBody("First event")).get("id").asLong();
        long second = create(userId, eventBody("Second event")).get("id").asLong();
        long third = create(userId, eventBody("Third event")).get("id").asLong();
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        JsonNode result = response(get("/users/{userId}/events", userId).param("from", "1").param("size", "2"), 200);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.get(0).get("id").asLong()).isEqualTo(second);
        assertThat(result.get(1).get("id").asLong()).isEqualTo(third);
        assertThat(result.get(0).get("id").asLong()).isNotEqualTo(first);
        // Проверка пользователя + одна выборка с JOIN FETCH. Ни COUNT списка, ни N+1.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        verify(confirmedRequests).countConfirmedRequests(Set.of(second, third));
        verify(views).countUniqueViews(Set.of(second, third));
        verifyNoMoreInteractions(confirmedRequests, views);
    }

    @Test
    void adminFiltersAreOptionalCombinedAndDateBoundariesInclusive() throws Exception {
        long first = create(userId, eventBody("First event")).get("id").asLong();
        long second = create(userId, eventBody("Second event")).get("id").asLong();
        response(patch("/admin/events/{id}", first).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stateAction\":\"REJECT_EVENT\"}"), 200);
        JsonNode all = response(get("/admin/events").param("from", "1").param("size", "1"), 200);
        assertThat(all.get(0).get("id").asLong()).isEqualTo(second);
        JsonNode filtered = response(get("/admin/events").param("users", Long.toString(userId))
                .param("categories", Long.toString(categoryId)).param("states", "CANCELED")
                .param("rangeStart", "2026-10-07 12:00:00").param("rangeEnd", "2026-10-07 12:00:00"), 200);
        assertThat(filtered.size()).isEqualTo(1);
        assertThat(filtered.get(0).get("id").asLong()).isEqualTo(first);
        mvc.perform(get("/admin/events").param("users", "999999"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/admin/events").param("rangeStart", "2026-10-08 12:00:00")
                        .param("rangeEnd", "2026-10-07 12:00:00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void countersFailureRollsBackPatchInRealDatabase() throws Exception {
        long id = create(userId, eventBody("Original title")).get("id").asLong();
        when(views.countUniqueViews(anySet())).thenThrow(new ResourceAccessException("stats offline"));
        mvc.perform(patch("/admin/events/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Changed title\",\"stateAction\":\"PUBLISH_EVENT\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value("SERVICE_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT title FROM events WHERE id = ?", String.class, id))
                .isEqualTo("Original title");
        assertThat(jdbc.queryForObject("SELECT state FROM events WHERE id = ?", String.class, id))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT published_on FROM events WHERE id = ?", LocalDateTime.class, id))
                .isNull();
    }

    @Test
    void limitCannotDropBelowConfirmedButZeroRemovesLimit() throws Exception {
        long id = create(userId, eventBody("Limited event").put("participantLimit", 10)).get("id").asLong();
        when(confirmedRequests.countConfirmedRequests(Set.of(id))).thenReturn(Map.of(id, 5L));
        mvc.perform(patch("/admin/events/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantLimit\":4}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT participant_limit FROM events WHERE id = ?", Integer.class, id))
                .isEqualTo(10);
        response(patch("/admin/events/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"participantLimit\":0}"), 200);
        assertThat(jdbc.queryForObject("SELECT participant_limit FROM events WHERE id = ?", Integer.class, id))
                .isZero();
    }

    @Test
    void anotherUserCannotReadOrEditEventAndMissingReferencesDoNotCreateRows() throws Exception {
        long id = create(userId, eventBody("Private event")).get("id").asLong();
        long otherUser = response(post("/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Other user\",\"email\":\"other@example.com\"}"), 201).get("id").asLong();
        mvc.perform(get("/users/{userId}/events/{id}", otherUser, id)).andExpect(status().isNotFound());
        mvc.perform(patch("/users/{userId}/events/{id}", otherUser, id)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/users/{userId}/events", userId).contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody("Unknown category").put("category", 999999).toString()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/users/999999/events").contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody("Unknown user").toString()))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class)).isEqualTo(1);
        verifyNoInteractions(confirmedRequests, views);
    }

    @Test
    void usedCategoryIsProtectedAndEmptyCategoryCanBeDeleted() throws Exception {
        create(userId, eventBody("Event"));
        mvc.perform(delete("/admin/categories/{id}", categoryId)).andExpect(status().isConflict());
        long emptyCategory = response(post("/admin/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Empty\"}"), 201).get("id").asLong();
        mvc.perform(delete("/admin/categories/{id}", emptyCategory)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM categories", Long.class)).isEqualTo(1);
    }

    @Test
    void commonHandlerReturns400ForZeroSizeInExistingControllers() throws Exception {
        mvc.perform(get("/admin/users").param("size", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value("BAD_REQUEST"));
        mvc.perform(get("/categories").param("size", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value("BAD_REQUEST"));
    }

    @Test
    void eventLockPreventsConcurrentWriterUntilTransactionEnds() throws Exception {
        long id = create(userId, eventBody("Lock test")).get("id").asLong();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            transaction.executeWithoutResult(status -> {
                assertThat(eventRepository.findByIdForUpdate(id)).isPresent();
                Future<?> other = executor.submit(() -> transaction.executeWithoutResult(otherStatus -> {
                    jdbc.execute("SET LOCAL lock_timeout = '300ms'");
                    eventRepository.findByIdForUpdate(id);
                }));
                assertThatThrownBy(() -> other.get(5, TimeUnit.SECONDS))
                        .rootCause().hasMessageContaining("lock timeout");
            });
            transaction.executeWithoutResult(status ->
                    assertThat(eventRepository.findByIdForUpdate(id)).isPresent());
        } finally {
            executor.shutdownNow();
        }
    }

    private JsonNode create(long initiator, ObjectNode body) throws Exception {
        return response(post("/users/{userId}/events", initiator)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()), 201);
    }

    private JsonNode response(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        String body = mvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private ObjectNode eventBody(String title) {
        ObjectNode body = objectMapper.createObjectNode()
                .put("title", title).put("annotation", "An annotation long enough for validation")
                .put("description", "A description long enough for validation")
                .put("category", categoryId).put("eventDate", "2026-10-07 12:00:00");
        body.putObject("location").put("lat", 55.7).put("lon", 37.6);
        return body;
    }
}
