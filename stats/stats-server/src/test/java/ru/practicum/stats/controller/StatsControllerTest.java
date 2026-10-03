package ru.practicum.stats.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.practicum.dto.ViewStatsDto;
import ru.practicum.stats.repository.HitRepository;
import ru.practicum.stats.service.StatsService;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StatsController.class)
@Import(StatsService.class)
class StatsControllerTest {

    private static final String START_VALUE = "2026-10-01 10:00:00";
    private static final String END_VALUE = "2026-10-01 12:00:00";
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 1, 10, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 1, 12, 0);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HitRepository hitRepository;

    @Test
    void returnsStatsWithDefaultParameters() throws Exception {
        when(hitRepository.findStats(START, END))
                .thenReturn(List.of(new ViewStatsDto("ewm-main-service", "/events/1", 3L)));

        mockMvc.perform(statsRequest())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        [{"app":"ewm-main-service","uri":"/events/1","hits":3}]
                        """, true));

        verify(hitRepository).findStats(START, END);
        verifyNoMoreInteractions(hitRepository);
    }

    @Test
    void acceptsRepeatedUrisAndUniqueFlag() throws Exception {
        List<String> uris = List.of("/events/1", "/events/2");
        when(hitRepository.findUniqueStatsByUris(START, END, uris))
                .thenReturn(List.of(new ViewStatsDto("ewm-main-service", "/events/1", 2L)));

        mockMvc.perform(statsRequest().param("uris", "/events/1", "/events/2").param("unique", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hits").value(2));

        verify(hitRepository).findUniqueStatsByUris(START, END, uris);
        verifyNoMoreInteractions(hitRepository);
    }

    @Test
    void acceptsCommaSeparatedUris() throws Exception {
        List<String> uris = List.of("/events/1", "/events/2");
        when(hitRepository.findStatsByUris(START, END, uris)).thenReturn(List.of());

        mockMvc.perform(statsRequest().param("uris", "/events/1,/events/2"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(hitRepository).findStatsByUris(START, END, uris);
        verifyNoMoreInteractions(hitRepository);
    }

    @Test
    void treatsEmptyUrisAsNoFilter() throws Exception {
        when(hitRepository.findUniqueStats(START, END)).thenReturn(List.of());

        mockMvc.perform(statsRequest().param("uris", "").param("unique", "true"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(hitRepository).findUniqueStats(START, END);
        verifyNoMoreInteractions(hitRepository);
    }

    @Test
    void returnsEmptyArrayWhenNoHitsMatch() throws Exception {
        when(hitRepository.findStats(START, END)).thenReturn(List.of());

        mockMvc.perform(statsRequest())
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void acceptsEqualDates() throws Exception {
        when(hitRepository.findStats(START, START)).thenReturn(List.of());

        mockMvc.perform(get("/stats").param("start", START_VALUE).param("end", START_VALUE))
                .andExpect(status().isOk());

        verify(hitRepository).findStats(START, START);
    }

    @Test
    void rejectsReversedPeriodWithoutQueryingDatabase() throws Exception {
        mockMvc.perform(get("/stats").param("start", END_VALUE).param("end", START_VALUE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("Начало периода должно быть раньше или равно окончанию"));

        verifyNoInteractions(hitRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "end"})
    void rejectsMissingRequiredDate(String missingParameter) throws Exception {
        MockHttpServletRequestBuilder request = get("/stats");
        if (!missingParameter.equals("start")) {
            request.param("start", START_VALUE);
        }
        if (!missingParameter.equals("end")) {
            request.param("end", END_VALUE);
        }

        mockMvc.perform(request).andExpect(status().isBadRequest());

        verifyNoInteractions(hitRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-date", "2026-02-30 10:00:00", ""})
    void rejectsInvalidStart(String value) throws Exception {
        mockMvc.perform(get("/stats").param("start", value).param("end", END_VALUE))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(hitRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-date", "2026-02-30 10:00:00", ""})
    void rejectsInvalidEnd(String value) throws Exception {
        mockMvc.perform(get("/stats").param("start", START_VALUE).param("end", value))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(hitRepository);
    }

    @Test
    void rejectsInvalidUniqueFlag() throws Exception {
        mockMvc.perform(statsRequest().param("unique", "not-a-boolean"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(hitRepository);
    }

    private MockHttpServletRequestBuilder statsRequest() {
        return get("/stats").param("start", START_VALUE).param("end", END_VALUE);
    }
}
