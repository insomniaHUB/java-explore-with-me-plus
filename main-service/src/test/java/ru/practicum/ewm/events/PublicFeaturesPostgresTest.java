package ru.practicum.ewm.events;

import client.StatsClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.ResourceAccessException;
import ru.practicum.dto.EndpointHitDto;
import ru.practicum.ewm.events.metrics.ConfirmedRequestsProvider;
import ru.practicum.ewm.events.model.EventState;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Отдельная БД: тест не использует таблицы и очистку EventPostgresTest.
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
class PublicFeaturesPostgresTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 7, 12, 0);
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @MockBean
    private ConfirmedRequestsProvider confirmedRequests;
    @MockBean
    private StatsClient statsClient;
    @MockBean
    private Clock eventClock;
    private final Map<Long, Long> confirmed = new HashMap<>();
    private final Map<Long, Long> views = new HashMap<>();
    private final List<EndpointHitDto> hits = new ArrayList<>();
    private long userId;
    private long categoryId;

    @BeforeEach
    void prepareDatabaseAndCounterTransport() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).endsWith("_test");
        jdbc.update("DELETE FROM compilation_events");
        jdbc.update("DELETE FROM compilations");
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM categories");
        jdbc.update("DELETE FROM users");
        confirmed.clear();
        views.clear();
        hits.clear();
        userId = jdbc.queryForObject("INSERT INTO users(name, email) VALUES ('Owner', 'owner@test.ru') RETURNING id",
                Long.class);
        categoryId = jdbc.queryForObject("INSERT INTO categories(name) VALUES ('Music') RETURNING id", Long.class);
        when(eventClock.instant()).thenReturn(Instant.parse("2026-10-07T12:00:00Z"));
        when(eventClock.getZone()).thenReturn(ZoneOffset.UTC);
        when(confirmedRequests.countConfirmedRequests(anySet())).thenAnswer(invocation -> {
            Set<Long> ids = invocation.getArgument(0);
            return ids.stream().filter(confirmed::containsKey).collect(Collectors.toMap(id -> id, confirmed::get));
        });
        when(statsClient.save(any())).thenAnswer(invocation -> {
            hits.add(invocation.getArgument(0));
            return ResponseEntity.status(201).build();
        });
        when(statsClient.get(any(), any(), anyList(), anyBoolean())).thenAnswer(invocation -> {
            assertThat((Boolean) invocation.getArgument(3)).isTrue();
            List<String> uris = invocation.getArgument(2);
            List<Map<String, Object>> response = new ArrayList<>();
            for (String uri : uris) {
                long id = Long.parseLong(uri.substring("/events/".length()));
                long count = views.getOrDefault(id, 0L) + hits.stream().filter(hit -> uri.equals(hit.uri()))
                        .map(EndpointHitDto::ip).distinct().count();
                if (count > 0) {
                    response.add(Map.of("app", "ewm-main-service", "uri", uri, "hits", count));
                }
            }
            return ResponseEntity.ok(response);
        });
    }

    @Test
    void defaultSearchReturnsOnlyPublishedFutureEventsWithExactOffsetAndOneSql() throws Exception {
        event("Past", EventState.PUBLISHED, NOW.minusDays(1), 0);
        event("Pending", EventState.PENDING, NOW.plusDays(1), 0);
        event("Canceled", EventState.CANCELED, NOW.plusDays(1), 0);
        long third = event("Third", EventState.PUBLISHED, NOW.plusDays(3), 0);
        long first = event("First", EventState.PUBLISHED, NOW.plusDays(1), 0);
        long second = event("Second", EventState.PUBLISHED, NOW.plusDays(2), 0);
        confirmed.put(second, 2L);
        views.put(second, 7L);
        sqlStatistics().clear();
        JsonNode result = response(get("/events").param("from", "1").param("size", "2"), 200);
        assertThat(ids(result)).containsExactly(second, third);
        assertThat(result.get(0).get("confirmedRequests").asLong()).isEqualTo(2);
        assertThat(result.get(0).get("views").asLong()).isEqualTo(7);
        assertThat(result.get(1).get("views").asLong()).isZero();
        assertThat(sqlStatistics().getPrepareStatementCount()).isEqualTo(1);
        verify(confirmedRequests).countConfirmedRequests(Set.of(second, third));
        verify(statsClient).get(any(), any(), argThat(uris -> Set.copyOf(uris)
                .equals(Set.of("/events/" + second, "/events/" + third))), eq(true));
        verify(statsClient).save(argThat(hit -> hit.uri().equals("/events")));
        verifyNoMoreInteractions(confirmedRequests, statsClient);
        assertThat(ids(result)).doesNotContain(first);
    }

    @Test
    void searchCombinesCaseInsensitiveTextCategoryPaidAndInclusiveDates() throws Exception {
        long match = event("Matching", EventState.PUBLISHED, NOW.minusDays(1), 0);
        jdbc.update("UPDATE events SET description = ?, paid = true WHERE id = ?", "Большой КОНЦЕРТ вечером", match);
        event("Wrong paid flag", EventState.PUBLISHED, NOW.minusDays(1), 0);
        long wrongCategory = event("Other category", EventState.PUBLISHED, NOW.minusDays(1), 0);
        long otherCategory = jdbc.queryForObject("INSERT INTO categories(name) VALUES ('Sport') RETURNING id", Long.class);
        jdbc.update("UPDATE events SET description = ?, paid = true, category_id = ? WHERE id = ?",
                "Большой КОНЦЕРТ вечером", otherCategory, wrongCategory);
        JsonNode result = response(get("/events").param("text", "концерт")
                .param("categories", Long.toString(categoryId)).param("paid", "true")
                .param("rangeStart", "2026-10-06 12:00:00").param("rangeEnd", "2026-10-06 12:00:00"), 200);
        assertThat(ids(result)).containsExactly(match);
        assertThat(result.get(0).get("paid").asBoolean()).isTrue();
    }

    @Test
    void textSearchTreatsSqlWildcardsLiterallyAndOneSidedRangeDoesNotForceFuture() throws Exception {
        long match = event("Literal", EventState.PUBLISHED, NOW.minusDays(1), 0);
        jdbc.update("UPDATE events SET annotation = ? WHERE id = ?", "Discount 100%_off today", match);
        event("No wildcard", EventState.PUBLISHED, NOW.minusDays(1), 0);
        JsonNode result = response(get("/events").param("text", "%_")
                .param("rangeEnd", "2026-10-07 12:00:00"), 200);
        assertThat(ids(result)).containsExactly(match);
    }

    @Test
    void availabilityIsAppliedBeforeOffsetAndZeroLimitIsUnlimited() throws Exception {
        long full = event("Full", EventState.PUBLISHED, NOW.plusDays(1), 1);
        long unlimited = event("Unlimited", EventState.PUBLISHED, NOW.plusDays(2), 0);
        long available = event("Available", EventState.PUBLISHED, NOW.plusDays(3), 2);
        confirmed.put(full, 1L);
        confirmed.put(unlimited, 999L);
        confirmed.put(available, 1L);
        sqlStatistics().clear();
        JsonNode result = response(get("/events").param("onlyAvailable", "true")
                .param("from", "1").param("size", "1"), 200);
        assertThat(ids(result)).containsExactly(available);
        assertThat(sqlStatistics().getPrepareStatementCount()).isEqualTo(2);
        verify(confirmedRequests).countConfirmedRequests(Set.of(full, unlimited, available));
        verify(statsClient).get(any(), any(), eq(List.of("/events/" + available)), eq(true));
        verify(statsClient).save(any());
        verifyNoMoreInteractions(confirmedRequests, statsClient);
    }

    @Test
    void viewsAreSortedBeforePaginationAndCountersAreBatched() throws Exception {
        long low = event("Low", EventState.PUBLISHED, NOW.plusDays(1), 0);
        long high = event("High", EventState.PUBLISHED, NOW.plusDays(2), 0);
        long middle = event("Middle", EventState.PUBLISHED, NOW.plusDays(3), 0);
        views.put(low, 1L);
        views.put(high, 99L);
        views.put(middle, 20L);
        sqlStatistics().clear();
        JsonNode result = response(get("/events").param("sort", "VIEWS").param("from", "1").param("size", "1"), 200);
        assertThat(ids(result)).containsExactly(middle);
        assertThat(result.get(0).get("views").asLong()).isEqualTo(20);
        assertThat(sqlStatistics().getPrepareStatementCount()).isEqualTo(2);
        verify(confirmedRequests).countConfirmedRequests(Set.of(middle));
        verify(statsClient).get(any(), any(), argThat(uris -> uris.size() == 3), eq(true));
        verify(statsClient).save(any());
        verifyNoMoreInteractions(confirmedRequests, statsClient);
    }

    @Test
    void combinesAvailabilityAndViewsWithoutCountingFullEventsOrRepeatingRequests() throws Exception {
        long full = event("Full popular", EventState.PUBLISHED, NOW.plusDays(1), 1);
        long zero = event("Zero views", EventState.PUBLISHED, NOW.plusDays(2), 0);
        long popular = event("Popular", EventState.PUBLISHED, NOW.plusDays(3), 2);
        confirmed.put(full, 1L);
        views.put(full, 100L);
        views.put(popular, 5L);
        JsonNode result = response(get("/events").param("onlyAvailable", "true").param("sort", "VIEWS"), 200);
        assertThat(ids(result)).containsExactly(popular, zero);
        verify(confirmedRequests).countConfirmedRequests(Set.of(full, zero, popular));
        verify(statsClient).get(any(), any(), argThat(uris -> Set.copyOf(uris)
                .equals(Set.of("/events/" + zero, "/events/" + popular))), eq(true));
        verify(statsClient).save(any());
        verifyNoMoreInteractions(confirmedRequests, statsClient);
    }

    @Test
    void detailIncludesCurrentUniqueIpAndListHitDoesNotIncreaseIndividualViews() throws Exception {
        long id = event("Published", EventState.PUBLISHED, NOW.plusDays(1), 0);
        response(get("/events"), 200);
        JsonNode first = response(fromIp(get("/events/" + id), "2001:db8::1"), 200);
        JsonNode repeat = response(fromIp(get("/events/" + id), "2001:db8::1"), 200);
        JsonNode other = response(fromIp(get("/events/" + id), "2001:db8::2"), 200);
        assertThat(first.get("views").asLong()).isEqualTo(1);
        assertThat(repeat.get("views").asLong()).isEqualTo(1);
        assertThat(other.get("views").asLong()).isEqualTo(2);
        assertThat(other.get("state").asText()).isEqualTo("PUBLISHED");
        assertThat(other.get("eventDate").asText()).isEqualTo("2026-10-08 12:00:00");
        assertThat(hits).allSatisfy(hit -> {
            assertThat(hit.app()).isEqualTo("ewm-main-service");
            assertThat(hit.timestamp()).isEqualTo(NOW);
        });
    }

    @Test
    void emptyResultsDoNotLoadCountersAndUnpublishedDetailsAre404() throws Exception {
        long id = event("Pending", EventState.PENDING, NOW.plusDays(1), 0);
        assertThat(response(get("/events").param("sort", "VIEWS"), 200)).isEmpty();
        verify(statsClient).save(argThat(hit -> hit.uri().equals("/events")));
        verifyNoMoreInteractions(statsClient);
        mvc.perform(get("/events/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/events/9999999")).andExpect(status().isNotFound());
        verifyNoInteractions(confirmedRequests);
        assertThat(hits).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"from,-1", "size,0", "sort,UNKNOWN", "rangeStart,2026-02-30 12:00:00"})
    void publicSearchRejectsInvalidParametersBeforeCallingDependencies(String parameter, String value) throws Exception {
        mvc.perform(get("/events").param(parameter, value)).andExpect(status().isBadRequest());
        verifyNoInteractions(statsClient, confirmedRequests);
    }

    @Test
    void reversedRangeAndInvalidCompilationPaginationReturn400() throws Exception {
        mvc.perform(get("/events").param("rangeStart", "2026-10-08 12:00:00")
                        .param("rangeEnd", "2026-10-07 12:00:00")).andExpect(status().isBadRequest());
        mvc.perform(get("/compilations").param("from", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/compilations").param("size", "0")).andExpect(status().isBadRequest());
        verifyNoInteractions(statsClient, confirmedRequests);
    }

    @Test
    void compilationLifecyclePreservesPatchNullFalseEmptyAndNeverDeletesEvents() throws Exception {
        long event = event("Included", EventState.PUBLISHED, NOW.plusDays(1), 0);
        views.put(event, 7L);
        confirmed.put(event, 2L);
        JsonNode created = response(post("/admin/compilations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Concerts\",\"pinned\":true,\"events\":[" + event + "," + event + "]}"), 201);
        long id = created.get("id").asLong();
        assertThat(created.get("events")).hasSize(1);
        assertThat(created.get("events").get(0).get("views").asLong()).isEqualTo(7);
        assertThat(created.get("events").get(0).get("confirmedRequests").asLong()).isEqualTo(2);
        JsonNode unchanged = response(patch("/admin/compilations/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":null,\"pinned\":null,\"events\":null}"), 200);
        assertThat(unchanged.get("pinned").asBoolean()).isTrue();
        assertThat(unchanged.get("events")).hasSize(1);
        JsonNode updated = response(patch("/admin/compilations/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pinned\":false,\"events\":[]}"), 200);
        assertThat(updated.get("pinned").asBoolean()).isFalse();
        assertThat(updated.get("events")).isEmpty();
        assertThat(updated.get("title").asText()).isEqualTo("Concerts");
        assertThat(response(get("/compilations/" + id), 200).get("events")).isEmpty();
        mvc.perform(delete("/admin/compilations/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/compilations/" + id)).andExpect(status().isNotFound());
        mvc.perform(delete("/admin/compilations/" + id)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class)).isEqualTo(1);
    }

    @Test
    void compilationListUsesTwoSqlQueriesAndOneCounterBatchForWholePage() throws Exception {
        long firstEvent = event("First", EventState.PUBLISHED, NOW.plusDays(1), 0);
        long secondEvent = event("Second", EventState.PUBLISHED, NOW.plusDays(2), 0);
        compilation("Skipped", false, firstEvent);
        long first = compilation("Selected one", false, firstEvent, secondEvent);
        compilation("Pinned", true, firstEvent);
        long second = compilation("Selected two", false, secondEvent);
        clearInvocations(confirmedRequests, statsClient);
        sqlStatistics().clear();
        JsonNode result = response(get("/compilations").param("pinned", "false")
                .param("from", "1").param("size", "2"), 200);
        assertThat(ids(result)).containsExactly(first, second);
        assertThat(result.get(0).get("events")).hasSize(2);
        assertThat(result.get(1).get("events")).hasSize(1);
        assertThat(sqlStatistics().getPrepareStatementCount()).isEqualTo(2);
        verify(confirmedRequests).countConfirmedRequests(Set.of(firstEvent, secondEvent));
        verify(statsClient).get(any(), any(), argThat(uris -> uris.size() == 2), eq(true));
        verifyNoMoreInteractions(confirmedRequests, statsClient);
    }

    @Test
    void deletingPopulatedCompilationRemovesLinksAndKeepsEvents() throws Exception {
        long first = event("First", EventState.PUBLISHED, NOW.plusDays(1), 0);
        long second = event("Second", EventState.PUBLISHED, NOW.plusDays(2), 0);
        long id = compilation("To delete", false, first, second);
        sqlStatistics().clear();
        assertThat(response(get("/compilations/" + id), 200).get("events")).hasSize(2);
        assertThat(sqlStatistics().getPrepareStatementCount()).isEqualTo(1);
        clearInvocations(confirmedRequests, statsClient);
        mvc.perform(delete("/admin/compilations/" + id)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM compilation_events", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM compilations", Long.class)).isZero();
        verifyNoInteractions(confirmedRequests, statsClient);
    }

    @Test
    void emptyCompilationDefaultsAndUnknownEventsDoNotCreatePartialRows() throws Exception {
        JsonNode empty = response(post("/admin/compilations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Empty\"}"), 201);
        assertThat(empty.get("pinned").asBoolean()).isFalse();
        assertThat(empty.get("events")).isEmpty();
        mvc.perform(post("/admin/compilations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Missing\",\"events\":[9999999]}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/admin/compilations/" + empty.get("id").asLong()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Changed\",\"events\":[9999999]}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT title FROM compilations", String.class)).isEqualTo("Empty");
        assertThat(response(get("/compilations").param("pinned", "true"), 200)).isEmpty();
        verifyNoInteractions(confirmedRequests, statsClient);
    }

    @Test
    void invalidCompilationBodyReturns400WithoutWriting() throws Exception {
        for (String body : List.of("{}", "{\"title\":\" \"}", "{\"title\":\"Valid\",\"events\":[null]}")) {
            mvc.perform(post("/admin/compilations").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(patch("/admin/compilations/9999999").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"\"}")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM compilations", Long.class)).isZero();
    }

    @Test
    void statsFailureReturns503AndCompilationPatchDoesNotSaveChanges() throws Exception {
        long event = event("Included", EventState.PUBLISHED, NOW.plusDays(1), 0);
        long id = compilation("Original", true, event);
        doThrow(new ResourceAccessException("offline")).when(statsClient).get(any(), any(), anyList(), anyBoolean());
        mvc.perform(patch("/admin/compilations/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Changed\",\"pinned\":false}"))
                .andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT title FROM compilations WHERE id = ?", String.class, id))
                .isEqualTo("Original");
        assertThat(jdbc.queryForObject("SELECT pinned FROM compilations WHERE id = ?", Boolean.class, id)).isTrue();
        mvc.perform(get("/events/" + event)).andExpect(status().isServiceUnavailable());
    }

    private long event(String title, EventState state, LocalDateTime date, int limit) {
        return jdbc.queryForObject("INSERT INTO events(annotation, description, title, category_id, initiator_id, "
                        + "lat, lon, event_date, created_on, published_on, paid, participant_limit, request_moderation, state) "
                        + "VALUES (?, ?, ?, ?, ?, 1, 2, ?, ?, ?, false, ?, true, ?) RETURNING id", Long.class,
                "An annotation long enough for validation", "A description long enough for validation", title,
                categoryId, userId, date, NOW.minusDays(2), state == EventState.PUBLISHED ? NOW.minusDays(1) : null,
                limit, state.name());
    }

    private long compilation(String title, boolean pinned, long... events) throws Exception {
        var body = objectMapper.createObjectNode().put("title", title).put("pinned", pinned);
        var eventIds = body.putArray("events");
        for (long id : events) {
            eventIds.add(id);
        }
        return response(post("/admin/compilations").contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()), 201).get("id").asLong();
    }

    private JsonNode response(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        return objectMapper.readTree(mvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString());
    }

    private List<Long> ids(JsonNode response) {
        List<Long> ids = new ArrayList<>();
        response.forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }

    private MockHttpServletRequestBuilder fromIp(MockHttpServletRequestBuilder request, String ip) {
        return request.with(servletRequest -> {
            servletRequest.setRemoteAddr(ip);
            return servletRequest;
        });
    }

    private org.hibernate.stat.Statistics sqlStatistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }
}
