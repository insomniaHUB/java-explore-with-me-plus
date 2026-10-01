package client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.practicum.dto.EndpointHitDto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class StatsClient {
    private final RestClient restClient;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public StatsClient(@Value("${stats-server.url}") String serverUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(serverUrl)
                .build();
    }


    public ResponseEntity<Object> save(EndpointHitDto endpointHitDto) {
        return restClient.post()
                .uri("/hit")
                .body(endpointHitDto)
                .retrieve()
                .toEntity(Object.class);
    }

    public ResponseEntity<Object> get(LocalDateTime start,
                                      LocalDateTime end,
                                      List<String> uris,
                                      boolean unique) {
        return restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/stats")
                            .queryParam("start", start.format(FORMATTER))
                            .queryParam("end", end.format(FORMATTER))
                            .queryParam("unique", unique);

                    if (uris != null && !uris.isEmpty()) {
                        uriBuilder.queryParam("uris", uris);
                    }

                    return uriBuilder.build();
                })
                .retrieve()
                .toEntity(Object.class);
    }
}
