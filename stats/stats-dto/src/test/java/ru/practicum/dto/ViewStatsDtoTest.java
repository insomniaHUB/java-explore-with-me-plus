package ru.practicum.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ViewStatsDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializesListFromSpecExample() throws Exception {
        String json = mapper.createArrayNode()
                .add(statsNode("ewm-main-service", "/events/1", 6))
                .add(statsNode("ewm-main-service", "/events", 2))
                .toString();

        List<ViewStatsDto> stats = List.of(mapper.readValue(json, ViewStatsDto[].class));

        assertThat(stats).containsExactly(
                new ViewStatsDto("ewm-main-service", "/events/1", 6L),
                new ViewStatsDto("ewm-main-service", "/events", 2L)
        );
    }

    @Test
    void serializesAllFields() throws Exception {
        ViewStatsDto dto = new ViewStatsDto("ewm-main-service", "/events/1", 6L);

        String json = mapper.writeValueAsString(dto);

        assertThat(mapper.readValue(json, ViewStatsDto.class)).isEqualTo(dto);
        assertThat(json).contains("\"app\":\"ewm-main-service\"", "\"uri\":\"/events/1\"", "\"hits\":6");
    }

    private ObjectNode statsNode(String app, String uri, long hits) {
        return mapper.createObjectNode()
                .put("app", app)
                .put("uri", uri)
                .put("hits", hits);
    }
}
