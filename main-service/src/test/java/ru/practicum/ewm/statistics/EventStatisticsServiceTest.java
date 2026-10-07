package ru.practicum.ewm.statistics;

import client.StatsClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import ru.practicum.dto.EndpointHitDto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventStatisticsServiceTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 6, 0, 0);
    private static final LocalDateTime END = START.plusDays(1);

    @Mock
    private StatsClient statsClient;

    private EventStatisticsService service;

    @BeforeEach
    void setUp() {
        service = new EventStatisticsService(statsClient, new ObjectMapper());
    }

    @Test
    void recordsApplicationUriIpAndCurrentTimestamp() {
        LocalDateTime before = LocalDateTime.now();
        service.recordHit("/events/1", "2001:db8::1");
        LocalDateTime after = LocalDateTime.now();

        ArgumentCaptor<EndpointHitDto> captor = ArgumentCaptor.forClass(EndpointHitDto.class);
        verify(statsClient).save(captor.capture());
        verifyNoMoreInteractions(statsClient);
        EndpointHitDto hit = captor.getValue();
        assertThat(hit.app()).isEqualTo("ewm-main-service");
        assertThat(hit.uri()).isEqualTo("/events/1");
        assertThat(hit.ip()).isEqualTo("2001:db8::1");
        assertThat(hit.timestamp()).isBetween(before, after);
        assertThat(hit.id()).isNull();
    }

    @Test
    void getsViewsForAllEventsInOneRequestAndUsesZeroForMissingStats() {
        List<String> uris = List.of("/events/1", "/events/2", "/events/3");
        when(statsClient.get(START, END, uris, true)).thenReturn(ResponseEntity.ok(List.of(
                Map.of("app", "ewm-main-service", "uri", "/events/1", "hits", 7),
                Map.of("app", "ewm-main-service", "uri", "/events/3", "hits", 3_000_000_000L),
                Map.of("app", "another-service", "uri", "/events/1", "hits", 99),
                Map.of("app", "ewm-main-service", "uri", "/events/99", "hits", 88)
        )));

        Map<Long, Long> views = service.getViews(List.of(1L, 2L, 1L, 3L), START, END, true);

        assertThat(views).containsExactlyInAnyOrderEntriesOf(Map.of(1L, 7L, 2L, 0L, 3L, 3_000_000_000L));
        verify(statsClient).get(START, END, uris, true);
        verifyNoMoreInteractions(statsClient);
    }

    @Test
    void doesNotCallStatsServerForEmptyEventList() {
        assertThat(service.getViews(List.of(), START, END, true)).isEmpty();
        verifyNoInteractions(statsClient);
    }

    @Test
    void passesNonUniqueModeAndHandlesEmptyStats() {
        when(statsClient.get(START, END, List.of("/events/1"), false))
                .thenReturn(ResponseEntity.ok(List.of()));

        assertThat(service.getViews(List.of(1L), START, END, false)).containsEntry(1L, 0L);
        verify(statsClient).get(START, END, List.of("/events/1"), false);
        verifyNoMoreInteractions(statsClient);
    }

    @Test
    void doesNotTreatMissingResponseBodyAsZeroViews() {
        when(statsClient.get(START, END, List.of("/events/1"), true))
                .thenReturn(ResponseEntity.ok().build());

        assertThatThrownBy(() -> service.getViews(List.of(1L), START, END, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("пустое тело ответа");
    }

    @Test
    void propagatesConnectionFailureInsteadOfInventingZeroViews() {
        when(statsClient.get(START, END, List.of("/events/1"), true))
                .thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> service.getViews(List.of(1L), START, END, true))
                .isInstanceOf(ResourceAccessException.class);
    }
}
