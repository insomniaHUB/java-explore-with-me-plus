package ru.practicum.ewm.requests.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.practicum.ewm.requests.model.RequestStatus;
import ru.practicum.ewm.requests.repository.EventRequestCount;
import ru.practicum.ewm.requests.repository.RequestRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestConfirmedRequestsProviderTest {
    @Mock
    private RequestRepository requests;
    @InjectMocks
    private RequestConfirmedRequestsProvider provider;

    @Test
    void countsConfirmedRequestsWithOneBatchQuery() {
        Set<Long> ids = Set.of(1L, 2L, 3L);
        when(requests.countByEventIdsAndStatus(ids, RequestStatus.CONFIRMED))
                .thenReturn(List.of(count(1L, 4L), count(3L, 1L)));

        assertThat(provider.countConfirmedRequests(ids)).isEqualTo(Map.of(1L, 4L, 3L, 1L));
        verify(requests).countByEventIdsAndStatus(ids, RequestStatus.CONFIRMED);
    }

    @Test
    void returnsEmptyMapWithoutQueryForEmptyIds() {
        assertThat(provider.countConfirmedRequests(Set.of())).isEmpty();
        verifyNoInteractions(requests);
    }

    private EventRequestCount count(Long eventId, Long total) {
        return new EventRequestCount() {
            @Override
            public Long getEventId() {
                return eventId;
            }

            @Override
            public Long getTotal() {
                return total;
            }
        };
    }
}
