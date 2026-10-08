package ru.practicum.ewm.requests.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.practicum.ewm.events.model.Event;
import ru.practicum.ewm.events.model.EventState;
import ru.practicum.ewm.events.repository.EventRepository;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.requests.dto.EventRequestStatusUpdateRequest;
import ru.practicum.ewm.requests.dto.EventRequestStatusUpdateResult;
import ru.practicum.ewm.requests.dto.ParticipationRequestDto;
import ru.practicum.ewm.requests.dto.RequestUpdateStatus;
import ru.practicum.ewm.requests.mapper.RequestMapper;
import ru.practicum.ewm.requests.model.ParticipationRequest;
import ru.practicum.ewm.requests.model.RequestStatus;
import ru.practicum.ewm.requests.repository.RequestRepository;
import ru.practicum.ewm.users.model.User;
import ru.practicum.ewm.users.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T12:00:00.123456789Z"), ZoneOffset.UTC);
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 6, 12, 0, 0, 123_000_000);
    private static final long OWNER_ID = 1L;
    private static final long REQUESTER_ID = 2L;
    private static final long EVENT_ID = 10L;

    private final User owner = User.builder().id(OWNER_ID).name("Owner").email("owner@example.com").build();
    private final User requester = User.builder().id(REQUESTER_ID).name("Guest").email("guest@example.com").build();

    @Mock
    private RequestRepository requests;
    @Mock
    private EventRepository events;
    @Mock
    private UserRepository users;
    private RequestService service;

    @BeforeEach
    void setUp() {
        service = new RequestService(requests, events, users, new RequestMapper(), CLOCK);
    }

    @Test
    void createReturnsPendingRequestWhenModerationIsOnAndLimitIsSet() {
        Event event = event(5, true, EventState.PUBLISHED);
        prepareCreation(event);
        when(requests.countByEventIdAndStatus(EVENT_ID, RequestStatus.CONFIRMED)).thenReturn(4L);

        ParticipationRequestDto result = service.create(REQUESTER_ID, EVENT_ID);

        assertThat(result.getId()).isEqualTo(100L);
        assertThat(result.getEvent()).isEqualTo(EVENT_ID);
        assertThat(result.getRequester()).isEqualTo(REQUESTER_ID);
        assertThat(result.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(result.getCreated()).isEqualTo(CREATED);
    }

    @ParameterizedTest
    @CsvSource({"0,true", "0,false", "3,false"})
    void createConfirmsRequestWhenModerationIsOffOrLimitIsZero(int limit, boolean moderation) {
        prepareCreation(event(limit, moderation, EventState.PUBLISHED));
        if (limit > 0) {
            when(requests.countByEventIdAndStatus(EVENT_ID, RequestStatus.CONFIRMED)).thenReturn(0L);
        }

        assertThat(service.create(REQUESTER_ID, EVENT_ID).getStatus()).isEqualTo(RequestStatus.CONFIRMED);
    }

    @Test
    void createRejectsRequestWhenLimitIsReached() {
        when(users.findById(REQUESTER_ID)).thenReturn(Optional.of(requester));
        when(events.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(event(2, true, EventState.PUBLISHED)));
        when(requests.countByEventIdAndStatus(EVENT_ID, RequestStatus.CONFIRMED)).thenReturn(2L);

        assertThatThrownBy(() -> service.create(REQUESTER_ID, EVENT_ID)).isInstanceOf(ConflictException.class);
        verify(requests, never()).save(any());
    }

    @Test
    void createRejectsInitiatorOfEvent() {
        when(users.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        when(events.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(event(0, true, EventState.PUBLISHED)));

        assertThatThrownBy(() -> service.create(OWNER_ID, EVENT_ID)).isInstanceOf(ConflictException.class);
        verify(requests, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = EventState.class, names = {"PENDING", "CANCELED"})
    void createRejectsUnpublishedEvent(EventState state) {
        when(users.findById(REQUESTER_ID)).thenReturn(Optional.of(requester));
        when(events.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(event(0, true, state)));

        assertThatThrownBy(() -> service.create(REQUESTER_ID, EVENT_ID)).isInstanceOf(ConflictException.class);
        verify(requests, never()).save(any());
    }

    @Test
    void createRejectsRepeatedRequest() {
        when(users.findById(REQUESTER_ID)).thenReturn(Optional.of(requester));
        when(events.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(event(0, true, EventState.PUBLISHED)));
        when(requests.existsByEventIdAndRequesterId(EVENT_ID, REQUESTER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.create(REQUESTER_ID, EVENT_ID)).isInstanceOf(ConflictException.class);
        verify(requests, never()).save(any());
    }

    @Test
    void createReturnsNotFoundForMissingUserOrEvent() {
        when(users.findById(REQUESTER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(REQUESTER_ID, EVENT_ID)).isInstanceOf(NotFoundException.class);

        when(users.findById(REQUESTER_ID)).thenReturn(Optional.of(requester));
        when(events.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(REQUESTER_ID, EVENT_ID)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void cancelKeepsCreatedTimeAndSetsCanceledStatus() {
        ParticipationRequest request = request(7L, RequestStatus.CONFIRMED);
        when(users.existsById(REQUESTER_ID)).thenReturn(true);
        when(requests.findByIdAndRequesterId(7L, REQUESTER_ID)).thenReturn(Optional.of(request));

        ParticipationRequestDto result = service.cancel(REQUESTER_ID, 7L);

        assertThat(result.getStatus()).isEqualTo(RequestStatus.CANCELED);
        assertThat(result.getCreated()).isEqualTo(CREATED);
    }

    @Test
    void cancelReturnsNotFoundForForeignRequest() {
        when(users.existsById(REQUESTER_ID)).thenReturn(true);
        when(requests.findByIdAndRequesterId(7L, REQUESTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(REQUESTER_ID, 7L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void findOwnReturnsNotFoundForMissingUser() {
        when(users.existsById(REQUESTER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.findOwn(REQUESTER_ID)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void findForEventIsAvailableOnlyToInitiator() {
        when(events.findById(EVENT_ID)).thenReturn(Optional.of(event(0, true, EventState.PUBLISHED)));
        when(requests.findAllByEventIdOrderById(EVENT_ID)).thenReturn(List.of(request(7L, RequestStatus.PENDING)));

        assertThat(service.findForEvent(OWNER_ID, EVENT_ID)).extracting(ParticipationRequestDto::getId)
                .containsExactly(7L);
        assertThatThrownBy(() -> service.findForEvent(REQUESTER_ID, EVENT_ID)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateRejectsAllRequestedPendingRequests() {
        ParticipationRequest first = request(7L, RequestStatus.PENDING);
        ParticipationRequest second = request(8L, RequestStatus.PENDING);
        prepareUpdate(event(1, true, EventState.PUBLISHED), List.of(7L, 8L), List.of(first, second));

        EventRequestStatusUpdateResult result = service.updateStatuses(OWNER_ID, EVENT_ID,
                update(RequestUpdateStatus.REJECTED, 7L, 8L));

        assertThat(result.getConfirmedRequests()).isEmpty();
        assertThat(result.getRejectedRequests()).extracting(ParticipationRequestDto::getStatus)
                .containsOnly(RequestStatus.REJECTED);
        verify(requests, never()).countByEventIdAndStatus(anyLong(), any());
    }

    @Test
    void updateConfirmsUntilLimitAndRejectsTheRest() {
        ParticipationRequest first = request(7L, RequestStatus.PENDING);
        ParticipationRequest second = request(8L, RequestStatus.PENDING);
        ParticipationRequest third = request(9L, RequestStatus.PENDING);
        prepareUpdate(event(3, true, EventState.PUBLISHED), List.of(7L, 8L, 9L), List.of(first, second, third));
        when(requests.countByEventIdAndStatus(EVENT_ID, RequestStatus.CONFIRMED)).thenReturn(1L);

        EventRequestStatusUpdateResult result = service.updateStatuses(OWNER_ID, EVENT_ID,
                update(RequestUpdateStatus.CONFIRMED, 7L, 8L, 9L));

        assertThat(result.getConfirmedRequests()).extracting(ParticipationRequestDto::getId).containsExactly(7L, 8L);
        assertThat(result.getRejectedRequests()).extracting(ParticipationRequestDto::getId).containsExactly(9L);
        assertThat(third.getStatus()).isEqualTo(RequestStatus.REJECTED);
        verify(requests).updateStatusByEventId(EVENT_ID, RequestStatus.PENDING, RequestStatus.REJECTED);
    }

    @Test
    void updateConfirmsWithoutLimitWhenLimitIsZero() {
        ParticipationRequest first = request(7L, RequestStatus.PENDING);
        prepareUpdate(event(0, true, EventState.PUBLISHED), List.of(7L), List.of(first));
        when(requests.countByEventIdAndStatus(EVENT_ID, RequestStatus.CONFIRMED)).thenReturn(100L);

        EventRequestStatusUpdateResult result = service.updateStatuses(OWNER_ID, EVENT_ID,
                update(RequestUpdateStatus.CONFIRMED, 7L));

        assertThat(result.getConfirmedRequests()).hasSize(1);
        verify(requests, never()).updateStatusByEventId(anyLong(), any(), any());
    }

    @Test
    void updateReturnsConflictWhenLimitIsAlreadyReached() {
        prepareUpdate(event(2, true, EventState.PUBLISHED), List.of(7L), List.of(request(7L, RequestStatus.PENDING)));
        when(requests.countByEventIdAndStatus(EVENT_ID, RequestStatus.CONFIRMED)).thenReturn(2L);

        assertThatThrownBy(() -> service.updateStatuses(OWNER_ID, EVENT_ID, update(RequestUpdateStatus.CONFIRMED, 7L)))
                .isInstanceOf(ConflictException.class);
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"CONFIRMED", "REJECTED", "CANCELED"})
    void updateReturnsConflictForNotPendingRequest(RequestStatus status) {
        prepareUpdate(event(5, true, EventState.PUBLISHED), List.of(7L), List.of(request(7L, status)));

        assertThatThrownBy(() -> service.updateStatuses(OWNER_ID, EVENT_ID, update(RequestUpdateStatus.REJECTED, 7L)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void updateReturnsNotFoundForUnknownRequestOrForeignEvent() {
        prepareUpdate(event(5, true, EventState.PUBLISHED), List.of(7L, 8L), List.of(request(7L, RequestStatus.PENDING)));
        assertThatThrownBy(() -> service.updateStatuses(OWNER_ID, EVENT_ID, update(RequestUpdateStatus.CONFIRMED, 7L, 8L)))
                .isInstanceOf(NotFoundException.class);

        assertThatThrownBy(() -> service.updateStatuses(REQUESTER_ID, EVENT_ID, update(RequestUpdateStatus.CONFIRMED, 7L)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateIgnoresDuplicatedIds() {
        prepareUpdate(event(0, true, EventState.PUBLISHED), List.of(7L), List.of(request(7L, RequestStatus.PENDING)));
        when(requests.countByEventIdAndStatus(EVENT_ID, RequestStatus.CONFIRMED)).thenReturn(0L);

        EventRequestStatusUpdateResult result = service.updateStatuses(OWNER_ID, EVENT_ID,
                update(RequestUpdateStatus.CONFIRMED, 7L, 7L));

        assertThat(result.getConfirmedRequests()).hasSize(1);
    }

    private void prepareCreation(Event event) {
        when(users.findById(REQUESTER_ID)).thenReturn(Optional.of(requester));
        when(events.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(event));
        when(requests.existsByEventIdAndRequesterId(EVENT_ID, REQUESTER_ID)).thenReturn(false);
        when(requests.save(any())).thenAnswer(invocation -> {
            ParticipationRequest saved = invocation.getArgument(0);
            saved.setId(100L);
            return saved;
        });
    }

    private void prepareUpdate(Event event, List<Long> ids, List<ParticipationRequest> found) {
        when(events.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(event));
        when(requests.findAllByEventIdAndIdInOrderById(EVENT_ID, ids)).thenReturn(found);
    }

    private Event event(int limit, boolean moderation, EventState state) {
        return Event.builder()
                .id(EVENT_ID)
                .initiator(owner)
                .participantLimit(limit)
                .requestModeration(moderation)
                .state(state)
                .build();
    }

    private ParticipationRequest request(Long id, RequestStatus status) {
        return ParticipationRequest.builder()
                .id(id)
                .event(event(0, true, EventState.PUBLISHED))
                .requester(requester)
                .status(status)
                .created(CREATED)
                .build();
    }

    private EventRequestStatusUpdateRequest update(RequestUpdateStatus status, Long... ids) {
        return EventRequestStatusUpdateRequest.builder()
                .requestIds(List.of(ids))
                .status(status)
                .build();
    }
}
