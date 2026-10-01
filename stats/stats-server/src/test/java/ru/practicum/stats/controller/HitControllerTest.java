package ru.practicum.stats.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.dto.EndpointHitDto;
import ru.practicum.stats.model.EndpointHit;
import ru.practicum.stats.repository.HitRepository;
import ru.practicum.stats.service.StatsService;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StatsController.class)
@Import(StatsService.class)
class HitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private HitRepository hitRepository;

    @Test
    void savesRequestFieldsAndReturnsCreatedWithoutBody() throws Exception {
        mockMvc.perform(post("/hit").contentType(MediaType.APPLICATION_JSON).content(validHit().toString()))
                .andExpect(status().isCreated())
                .andExpect(content().string(""));

        ArgumentCaptor<EndpointHit> captor = ArgumentCaptor.forClass(EndpointHit.class);
        verify(hitRepository).save(captor.capture());
        EndpointHit hit = captor.getValue();
        assertThat(hit.getId()).isNull();
        assertThat(hit.getApp()).isEqualTo("ewm-main-service");
        assertThat(hit.getUri()).isEqualTo("/events/1");
        assertThat(hit.getIp()).isEqualTo("192.163.0.1");
        assertThat(hit.getTimestamp()).isEqualTo(LocalDateTime.of(2022, 9, 6, 11, 0, 23));
        verifyNoMoreInteractions(hitRepository);
    }

    @Test
    void ignoresClientId() throws Exception {
        ObjectNode request = validHit().put("id", 123L);
        assertThat(objectMapper.treeToValue(request, EndpointHitDto.class).id()).isNull();

        mockMvc.perform(post("/hit").contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isCreated());

        ArgumentCaptor<EndpointHit> captor = ArgumentCaptor.forClass(EndpointHit.class);
        verify(hitRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNull();
    }

    @Test
    void serializesDtoTimestampInApiFormat() throws Exception {
        EndpointHitDto dto = new EndpointHitDto(1L, "app", "/events", "::1",
                LocalDateTime.of(2022, 9, 6, 11, 0, 23));

        ObjectNode json = objectMapper.valueToTree(dto);

        assertThat(json.get("timestamp").asText()).isEqualTo("2022-09-06 11:00:23");
        assertThat(json.get("id").asLong()).isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"app", "uri", "ip", "timestamp"})
    void rejectsMissingOrNullFields(String field) throws Exception {
        ObjectNode request = validHit();
        request.remove(field);
        assertBadRequest(request);
        request.putNull(field);
        assertBadRequest(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"app", "uri", "ip"})
    void rejectsBlankStrings(String field) throws Exception {
        assertBadRequest(validHit().put(field, ""));
        assertBadRequest(validHit().put(field, "   "));
    }

    @ParameterizedTest
    @CsvSource({"app,255", "uri,512", "ip,45"})
    void rejectsStringsExceedingDatabaseColumnLength(String field, int maximum) throws Exception {
        assertBadRequest(validHit().put(field, "a".repeat(maximum + 1)));
    }

    @Test
    void acceptsDatabaseLengthBoundariesAndIpv6() throws Exception {
        ObjectNode request = validHit().put("app", "a".repeat(255))
                .put("uri", "/" + "a".repeat(511))
                .put("ip", "ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255");
        mockMvc.perform(post("/hit").contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-date", "2026-02-30 10:00:00", "2022-09-06T11:00:23", ""})
    void rejectsInvalidTimestamp(String timestamp) throws Exception {
        assertBadRequest(validHit().put("timestamp", timestamp));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{broken", "[]"})
    void rejectsMissingOrMalformedBody(String body) throws Exception {
        mockMvc.perform(post("/hit").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(hitRepository);
    }

    private void assertBadRequest(ObjectNode request) throws Exception {
        mockMvc.perform(post("/hit").contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(hitRepository);
    }

    private ObjectNode validHit() {
        return objectMapper.createObjectNode()
                .put("app", "ewm-main-service")
                .put("uri", "/events/1")
                .put("ip", "192.163.0.1")
                .put("timestamp", "2022-09-06 11:00:23");
    }
}
