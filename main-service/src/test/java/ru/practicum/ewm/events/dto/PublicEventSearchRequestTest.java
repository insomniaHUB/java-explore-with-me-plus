package ru.practicum.ewm.events.dto;

import client.StatsClient;
import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicEventSearchRequestTest.SearchProbeController.class)
@Import(PublicEventSearchRequestTest.SearchProbeController.class)
class PublicEventSearchRequestTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StatsClient statsClient;

    @Test
    void bindsDefaultSearchParametersAndRegistersStatsClient() throws Exception {
        mockMvc.perform(get("/test/event-search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.onlyAvailable").value(false))
                .andExpect(jsonPath("$.sort").value("EVENT_DATE"));

        assertThat(statsClient).isNotNull();
    }

    @Test
    void bindsFiltersIncludingExplicitFalse() throws Exception {
        mockMvc.perform(get("/test/event-search")
                        .param("text", "Концерт")
                        .param("categories", "1", "2")
                        .param("paid", "false")
                        .param("rangeStart", "2026-10-06 12:00:00")
                        .param("rangeEnd", "2026-10-07 12:00:00")
                        .param("onlyAvailable", "true")
                        .param("sort", "VIEWS")
                        .param("from", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Концерт"))
                .andExpect(jsonPath("$.categories[0]").value(1))
                .andExpect(jsonPath("$.categories[1]").value(2))
                .andExpect(jsonPath("$.paid").value(false))
                .andExpect(jsonPath("$.onlyAvailable").value(true))
                .andExpect(jsonPath("$.sort").value("VIEWS"))
                .andExpect(jsonPath("$.from").value(1))
                .andExpect(jsonPath("$.size").value(2));
    }

    @ParameterizedTest
    @CsvSource({"from,-1", "size,0", "size,-1", "sort,UNKNOWN", "rangeStart,2026-02-30 12:00:00"})
    void rejectsInvalidQueryParameters(String name, String value) throws Exception {
        mockMvc.perform(get("/test/event-search").param(name, value))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsReversedDateRange() throws Exception {
        mockMvc.perform(get("/test/event-search")
                        .param("rangeStart", "2026-10-07 12:00:00")
                        .param("rangeEnd", "2026-10-06 12:00:00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsEqualDatesAndOneSidedRange() throws Exception {
        mockMvc.perform(get("/test/event-search")
                        .param("rangeStart", "2026-10-06 12:00:00")
                        .param("rangeEnd", "2026-10-06 12:00:00"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/test/event-search").param("rangeEnd", "2026-10-06 12:00:00"))
                .andExpect(status().isOk());
    }

    @RestController
    static class SearchProbeController {

        @GetMapping("/test/event-search")
        PublicEventSearchRequest search(@Valid @ModelAttribute PublicEventSearchRequest request) {
            return request;
        }
    }
}
