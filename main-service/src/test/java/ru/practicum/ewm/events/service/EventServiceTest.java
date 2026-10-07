package ru.practicum.ewm.events.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.practicum.ewm.categories.mapper.CategoryMapper;
import ru.practicum.ewm.categories.model.Category;
import ru.practicum.ewm.categories.repository.CategoryRepository;
import ru.practicum.ewm.events.dto.*;
import ru.practicum.ewm.events.mapper.EventMapper;
import ru.practicum.ewm.events.metrics.EventMetrics;
import ru.practicum.ewm.events.metrics.EventMetricsService;
import ru.practicum.ewm.events.model.Event;
import ru.practicum.ewm.events.model.EventState;
import ru.practicum.ewm.events.repository.EventQueryRepository;
import ru.practicum.ewm.events.repository.EventRepository;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.exception.ServiceUnavailableException;
import ru.practicum.ewm.exception.ValidationException;
import ru.practicum.ewm.users.mapper.UserMapper;
import ru.practicum.ewm.users.model.User;
import ru.practicum.ewm.users.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private final User owner = User.builder().id(1L).name("Owner").email("owner@example.com").build();
    private final Category category = Category.builder().id(2L).name("Concerts").build();
    private final EventMapper mapper = new EventMapper(new CategoryMapper(), new UserMapper());

    @Mock
    private EventRepository repository;
    @Mock
    private EventQueryRepository queryRepository;
    @Mock
    private UserRepository users;
    @Mock
    private CategoryRepository categories;
    @Mock
    private EventMetricsService metrics;
    private EventService service;

    @BeforeEach
    void setUp() {
        service = new EventService(repository, queryRepository, users, categories, mapper, metrics, CLOCK);
    }

    @Test
    void createsPendingEventAtExactTwoHourBoundaryWithDefaultsWithoutCounters() {
        when(users.findById(1L)).thenReturn(Optional.of(owner));
        when(categories.findById(2L)).thenReturn(Optional.of(category));
        when(repository.save(any())).thenAnswer(invocation -> {
            Event event = invocation.getArgument(0);
            event.setId(3L);
            return event;
        });

        EventFullDto result = service.create(1L, newEvent());

        assertThat(result.getId()).isEqualTo(3L);
        assertThat(result.getInitiator().getId()).isEqualTo(1L);
        assertThat(result.getState()).isEqualTo(EventState.PENDING);
        assertThat(result.getCreatedOn()).isEqualTo(NOW);
        assertThat(result.getPublishedOn()).isNull();
        assertThat(result.getPaid()).isFalse();
        assertThat(result.getParticipantLimit()).isZero();
        assertThat(result.getRequestModeration()).isTrue();
        assertThat(result.getViews()).isZero();
        assertThat(result.getConfirmedRequests()).isZero();
        verifyNoInteractions(metrics);
    }

    @Test
    void rejectsCreationOneSecondBeforeTwoHours() {
        when(users.findById(1L)).thenReturn(Optional.of(owner));
        when(categories.findById(2L)).thenReturn(Optional.of(category));
        NewEventDto dto = newEvent();
        dto.setEventDate(NOW.plusHours(2).minusSeconds(1));
        assertThatThrownBy(() -> service.create(1L, dto)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(repository, metrics);
    }

    @Test
    void returnsNotFoundForMissingUserAndCategory() {
        assertThatThrownBy(() -> service.create(1L, newEvent())).isInstanceOf(NotFoundException.class);
        when(users.findById(1L)).thenReturn(Optional.of(owner));
        assertThatThrownBy(() -> service.create(1L, newEvent())).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(repository, metrics);
    }

    @ParameterizedTest
    @EnumSource(value = EventState.class, names = {"PENDING", "CANCELED"})
    void updatesOnlySuppliedFieldsIncludingFalseAndZero(EventState state) {
        Event event = lockedOwnEvent(state);
        event.setPaid(true);
        event.setParticipantLimit(10);
        successfulMetrics();
        UpdateEventUserRequest dto = new UpdateEventUserRequest();
        dto.setPaid(false);
        dto.setParticipantLimit(0);
        dto.setRequestModeration(false);
        EventFullDto result = service.updateOwn(1L, 3L, dto);
        assertThat(result.getPaid()).isFalse();
        assertThat(result.getParticipantLimit()).isZero();
        assertThat(result.getRequestModeration()).isFalse();
        assertThat(result.getTitle()).isEqualTo("Event title");
        assertThat(result.getLocation().getLat()).isEqualTo(55.7f);
        assertThat(result.getEventDate()).isEqualTo(NOW.plusHours(2));
        assertThat(result.getConfirmedRequests()).isEqualTo(2L);
        assertThat(result.getViews()).isEqualTo(7L);
    }

    @Test
    void rejectsUserEditOfPublishedEventBeforeLoadingCounters() {
        lockedOwnEvent(EventState.PUBLISHED);
        assertThatThrownBy(() -> service.updateOwn(1L, 3L, new UpdateEventUserRequest()))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(metrics);
    }

    @Test
    void rejectsAnotherUsersEventForReadAndUpdate() {
        Event event = event(EventState.PENDING);
        when(users.existsById(10L)).thenReturn(true);
        when(repository.findById(3L)).thenReturn(Optional.of(event));
        when(repository.findByIdForUpdate(3L)).thenReturn(Optional.of(event));
        assertThatThrownBy(() -> service.getOwn(10L, 3L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.updateOwn(10L, 3L, new UpdateEventUserRequest()))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(metrics);
    }

    @Test
    void cancelsAndResubmitsForReview() {
        Event event = lockedOwnEvent(EventState.PENDING);
        successfulMetrics();
        UpdateEventUserRequest dto = new UpdateEventUserRequest();
        dto.setStateAction(UserStateAction.CANCEL_REVIEW);
        assertThat(service.updateOwn(1L, 3L, dto).getState()).isEqualTo(EventState.CANCELED);
        dto.setStateAction(UserStateAction.SEND_TO_REVIEW);
        assertThat(service.updateOwn(1L, 3L, dto).getState()).isEqualTo(EventState.PENDING);
        assertThat(event.getPublishedOn()).isNull();
    }

    @Test
    void rejectsUserDateBeforeTwoHours() {
        lockedOwnEvent(EventState.PENDING);
        UpdateEventUserRequest dto = new UpdateEventUserRequest();
        dto.setEventDate(NOW.plusHours(2).minusSeconds(1));
        assertThatThrownBy(() -> service.updateOwn(1L, 3L, dto)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(metrics);
    }

    @Test
    void publishesAtExactOneHourBoundaryAndSetsPublicationTime() {
        Event event = lockedEvent(EventState.PENDING);
        successfulMetrics();
        UpdateEventAdminRequest dto = new UpdateEventAdminRequest();
        dto.setEventDate(NOW.plusHours(1));
        dto.setStateAction(AdminStateAction.PUBLISH_EVENT);
        EventFullDto result = service.updateAdmin(3L, dto);
        assertThat(result.getState()).isEqualTo(EventState.PUBLISHED);
        assertThat(event.getPublishedOn()).isEqualTo(NOW);
        assertThat(event.getCreatedOn()).isEqualTo(NOW.minusHours(1));
    }

    @Test
    void publicationChecksExistingDateIfPatchDoesNotContainDate() {
        Event event = lockedEvent(EventState.PENDING);
        event.setEventDate(NOW.plusHours(1).minusSeconds(1));
        UpdateEventAdminRequest dto = new UpdateEventAdminRequest();
        dto.setStateAction(AdminStateAction.PUBLISH_EVENT);
        assertThatThrownBy(() -> service.updateAdmin(3L, dto)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(metrics);
    }

    @ParameterizedTest
    @EnumSource(value = EventState.class, names = {"PUBLISHED", "CANCELED"})
    void rejectsPublicationOutsidePending(EventState state) {
        lockedEvent(state);
        UpdateEventAdminRequest dto = new UpdateEventAdminRequest();
        dto.setStateAction(AdminStateAction.PUBLISH_EVENT);
        assertThatThrownBy(() -> service.updateAdmin(3L, dto)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(metrics);
    }

    @ParameterizedTest
    @EnumSource(value = EventState.class, names = {"PENDING", "CANCELED"})
    void adminCanRejectUnpublishedEvent(EventState state) {
        lockedEvent(state);
        successfulMetrics();
        UpdateEventAdminRequest dto = new UpdateEventAdminRequest();
        dto.setStateAction(AdminStateAction.REJECT_EVENT);
        assertThat(service.updateAdmin(3L, dto).getState()).isEqualTo(EventState.CANCELED);
    }

    @Test
    void adminCannotRejectPublishedEvent() {
        lockedEvent(EventState.PUBLISHED);
        UpdateEventAdminRequest dto = new UpdateEventAdminRequest();
        dto.setStateAction(AdminStateAction.REJECT_EVENT);
        assertThatThrownBy(() -> service.updateAdmin(3L, dto)).isInstanceOf(ConflictException.class);
    }

    @Test
    void editOfPublishedDateUsesOriginalPublicationTime() {
        Event event = lockedEvent(EventState.PUBLISHED);
        event.setPublishedOn(NOW.minusDays(1));
        successfulMetrics();
        UpdateEventAdminRequest dto = new UpdateEventAdminRequest();
        dto.setEventDate(event.getPublishedOn().plusHours(1));
        assertThat(service.updateAdmin(3L, dto).getPublishedOn()).isEqualTo(NOW.minusDays(1));
    }

    @Test
    void refusesLimitBelowConfirmedCount() {
        Event event = lockedEvent(EventState.PUBLISHED);
        event.setParticipantLimit(10);
        successfulMetrics();
        UpdateEventAdminRequest dto = new UpdateEventAdminRequest();
        dto.setParticipantLimit(1);
        assertThatThrownBy(() -> service.updateAdmin(3L, dto)).isInstanceOf(ConflictException.class);
        assertThat(event.getParticipantLimit()).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 3})
    void allowsUnlimitedOrSufficientLimit(int limit) {
        lockedEvent(EventState.PUBLISHED);
        successfulMetrics();
        UpdateEventAdminRequest dto = new UpdateEventAdminRequest();
        dto.setParticipantLimit(limit);
        assertThat(service.updateAdmin(3L, dto).getParticipantLimit()).isEqualTo(limit);
    }

    @Test
    void doesNotChangeEventWhenCountersAreUnavailable() {
        Event event = lockedEvent(EventState.PENDING);
        when(metrics.load(Set.of(3L))).thenThrow(new ServiceUnavailableException("offline"));
        UpdateEventAdminRequest dto = new UpdateEventAdminRequest();
        dto.setTitle("Changed title");
        dto.setStateAction(AdminStateAction.PUBLISH_EVENT);
        assertThatThrownBy(() -> service.updateAdmin(3L, dto)).isInstanceOf(ServiceUnavailableException.class);
        assertThat(event.getTitle()).isEqualTo("Event title");
        assertThat(event.getState()).isEqualTo(EventState.PENDING);
        assertThat(event.getPublishedOn()).isNull();
    }

    @Test
    void listLoadsCountersOnceForAllReturnedIds() {
        Event first = event(EventState.PENDING);
        Event second = event(EventState.CANCELED);
        second.setId(4L);
        when(users.existsById(1L)).thenReturn(true);
        when(queryRepository.find(List.of(1L), null, null, null, null, 1, 2))
                .thenReturn(List.of(first, second));
        when(metrics.load(Set.of(3L, 4L))).thenReturn(new EventMetrics(Map.of(3L, 2L), Map.of(4L, 7L)));
        List<EventShortDto> result = service.findOwn(1L, 1, 2);
        assertThat(result).extracting(EventShortDto::getConfirmedRequests).containsExactly(2L, 0L);
        assertThat(result).extracting(EventShortDto::getViews).containsExactly(0L, 7L);
        verify(metrics).load(Set.of(3L, 4L));
        verifyNoMoreInteractions(metrics);
    }

    @Test
    void rejectsReversedAdminRangeWithoutDatabaseAccess() {
        assertThatThrownBy(() -> service.findAdmin(null, null, null, NOW.plusDays(1), NOW, 0, 10))
                .isInstanceOf(ValidationException.class);
        verifyNoInteractions(queryRepository, metrics);
    }

    private Event lockedOwnEvent(EventState state) {
        when(users.existsById(1L)).thenReturn(true);
        return lockedEvent(state);
    }

    private Event lockedEvent(EventState state) {
        Event event = event(state);
        when(repository.findByIdForUpdate(3L)).thenReturn(Optional.of(event));
        return event;
    }

    private Event event(EventState state) {
        Event event = mapper.toEvent(newEvent(), owner, category, NOW.minusHours(1));
        event.setId(3L);
        event.setState(state);
        return event;
    }

    private void successfulMetrics() {
        when(metrics.load(Set.of(3L))).thenReturn(new EventMetrics(Map.of(3L, 2L), Map.of(3L, 7L)));
    }

    private NewEventDto newEvent() {
        NewEventDto dto = new NewEventDto();
        dto.setTitle("Event title");
        dto.setAnnotation("An annotation long enough for validation");
        dto.setDescription("A description long enough for validation");
        dto.setCategory(2L);
        dto.setLocation(new LocationDto(55.7f, 37.6f));
        dto.setEventDate(NOW.plusHours(2));
        return dto;
    }
}
