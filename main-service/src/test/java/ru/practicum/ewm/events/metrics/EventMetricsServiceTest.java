package ru.practicum.ewm.events.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.ResourceAccessException;
import ru.practicum.ewm.exception.ServiceUnavailableException;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventMetricsServiceTest {
    @Mock
    private ObjectProvider<ConfirmedRequestsProvider> requestsBeans;
    @Mock
    private ObjectProvider<EventViewsProvider> viewsBeans;
    @Mock
    private ConfirmedRequestsProvider requests;
    @Mock
    private EventViewsProvider views;
    private EventMetricsService service;

    @BeforeEach
    void setUp() {
        service = new EventMetricsService(requestsBeans, viewsBeans);
    }

    @Test
    void emptySelectionDoesNotRequireProviders() {
        assertThat(service.load(Set.of()).confirmedRequests()).isEmpty();
        verifyNoInteractions(requestsBeans, viewsBeans);
    }

    @Test
    void missingProviderIsUnavailableNotZero() {
        assertThatThrownBy(() -> service.load(Set.of(1L))).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void successfulEmptyResponseMeansZeroAndEachProviderIsCalledOnce() {
        providers();
        when(requests.countConfirmedRequests(Set.of(1L, 2L))).thenReturn(Map.of(1L, 3L));
        when(views.countUniqueViews(Set.of(1L, 2L))).thenReturn(Map.of());
        EventMetrics result = service.load(Set.of(1L, 2L));
        assertThat(result.confirmed(1L)).isEqualTo(3);
        assertThat(result.confirmed(2L)).isZero();
        assertThat(result.views(1L)).isZero();
        verify(requests).countConfirmedRequests(Set.of(1L, 2L));
        verify(views).countUniqueViews(Set.of(1L, 2L));
        verifyNoMoreInteractions(requests, views);
    }

    @Test
    void networkFailureIsUnavailableNotZero() {
        providers();
        when(requests.countConfirmedRequests(Set.of(1L))).thenReturn(Map.of());
        when(views.countUniqueViews(Set.of(1L))).thenThrow(new ResourceAccessException("offline"));
        assertThatThrownBy(() -> service.load(Set.of(1L))).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void invalidCounterResponseIsUnavailable() {
        when(requestsBeans.getIfAvailable()).thenReturn(requests);
        when(requests.countConfirmedRequests(Set.of(1L))).thenReturn(Map.of(1L, -1L));
        assertThatThrownBy(() -> service.load(Set.of(1L))).isInstanceOf(ServiceUnavailableException.class);
        verifyNoInteractions(views);
    }

    @Test
    void separateCounterQueriesDoNotCallOtherProvider() {
        when(requestsBeans.getIfAvailable()).thenReturn(requests);
        when(requests.countConfirmedRequests(Set.of(1L))).thenReturn(Map.of(1L, 2L));
        assertThat(service.loadConfirmedRequests(Set.of(1L))).containsEntry(1L, 2L);
        verifyNoInteractions(viewsBeans, views);
    }

    @Test
    void viewsCanBeLoadedWithoutRequestsProviderAndEmptySelectionsNeedNeither() {
        when(viewsBeans.getIfAvailable()).thenReturn(views);
        when(views.countUniqueViews(Set.of(1L))).thenReturn(Map.of(1L, 3L));
        assertThat(service.loadViews(Set.of(1L))).containsEntry(1L, 3L);
        assertThat(service.loadViews(Set.of())).isEmpty();
        assertThat(service.loadConfirmedRequests(Set.of())).isEmpty();
        verifyNoInteractions(requestsBeans, requests);
    }

    private void providers() {
        when(requestsBeans.getIfAvailable()).thenReturn(requests);
        when(viewsBeans.getIfAvailable()).thenReturn(views);
    }
}
