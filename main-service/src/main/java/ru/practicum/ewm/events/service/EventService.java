package ru.practicum.ewm.events.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import ru.practicum.ewm.exception.ValidationException;
import ru.practicum.ewm.users.model.User;
import ru.practicum.ewm.users.repository.UserRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventService {
    private final EventRepository eventRepository;
    private final EventQueryRepository eventQueryRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final EventMapper eventMapper;
    private final EventMetricsService metricsService;
    private final Clock eventClock;

    @Transactional
    public EventFullDto create(Long userId, NewEventDto dto) {
        User initiator = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("Пользователь не найден"));
        Category category = findCategory(dto.getCategory());
        LocalDateTime now = LocalDateTime.now(eventClock);
        requireDate(dto.getEventDate(), now.plusHours(2));
        Event event = eventRepository.save(eventMapper.toEvent(dto, initiator, category, now));
        // У новой записи ещё нет заявок и просмотров. Внешние вызовы здесь не нужны.
        return eventMapper.toFullDto(event, 0, 0);
    }

    public List<EventShortDto> findOwn(Long userId, int from, int size) {
        requireUser(userId);
        requirePagination(from, size);
        List<Event> events = eventQueryRepository.find(List.of(userId), null, null, null, null, from, size);
        EventMetrics metrics = loadMetrics(events);
        return events.stream().map(event -> eventMapper.toShortDto(event,
                metrics.confirmed(event.getId()), metrics.views(event.getId()))).toList();
    }

    public EventFullDto getOwn(Long userId, Long eventId) {
        requireUser(userId);
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Событие не найдено"));
        requireOwner(event, userId);
        return fullDto(event, loadMetrics(List.of(event)));
    }

    @Transactional
    public EventFullDto updateOwn(Long userId, Long eventId, UpdateEventUserRequest dto) {
        requireUser(userId);
        Event event = findForUpdate(eventId);
        requireOwner(event, userId);
        if (event.getState() == EventState.PUBLISHED) {
            throw new ConflictException("Изменять можно только ожидающие модерации или отменённые события");
        }
        if (dto.getEventDate() != null) {
            requireDate(dto.getEventDate(), LocalDateTime.now(eventClock).plusHours(2));
        }
        Category category = dto.getCategory() == null ? null : findCategory(dto.getCategory());
        EventMetrics metrics = loadMetrics(List.of(event));
        requireLimit(dto.getParticipantLimit(), metrics.confirmed(eventId));
        applyChanges(event, dto, category);
        if (dto.getStateAction() == UserStateAction.SEND_TO_REVIEW) {
            event.setState(EventState.PENDING);
        } else if (dto.getStateAction() == UserStateAction.CANCEL_REVIEW) {
            event.setState(EventState.CANCELED);
        }
        return fullDto(event, metrics);
    }

    public List<EventFullDto> findAdmin(List<Long> users, List<EventState> states, List<Long> categories,
                                       LocalDateTime start, LocalDateTime end, int from, int size) {
        requirePagination(from, size);
        if (start != null && end != null && start.isAfter(end)) {
            throw new ValidationException("Начало диапазона должно быть раньше или равно окончанию");
        }
        List<Event> events = eventQueryRepository.find(users, states, categories, start, end, from, size);
        EventMetrics metrics = loadMetrics(events);
        return events.stream().map(event -> fullDto(event, metrics)).toList();
    }

    @Transactional
    public EventFullDto updateAdmin(Long eventId, UpdateEventAdminRequest dto) {
        Event event = findForUpdate(eventId);
        LocalDateTime now = LocalDateTime.now(eventClock);
        if (dto.getStateAction() == AdminStateAction.PUBLISH_EVENT && event.getState() != EventState.PENDING) {
            throw new ConflictException("Публиковать можно только ожидающее модерации событие");
        }
        if (dto.getStateAction() == AdminStateAction.REJECT_EVENT && event.getState() == EventState.PUBLISHED) {
            throw new ConflictException("Нельзя отклонить опубликованное событие");
        }
        LocalDateTime publicationDate = event.getPublishedOn() == null ? now : event.getPublishedOn();
        if (dto.getStateAction() == AdminStateAction.PUBLISH_EVENT) {
            requireDate(dto.getEventDate() == null ? event.getEventDate() : dto.getEventDate(), now.plusHours(1));
        } else if (dto.getEventDate() != null) {
            requireDate(dto.getEventDate(), publicationDate.plusHours(1));
        }
        Category category = dto.getCategory() == null ? null : findCategory(dto.getCategory());
        EventMetrics metrics = loadMetrics(List.of(event));
        requireLimit(dto.getParticipantLimit(), metrics.confirmed(eventId));
        // Все потенциально неуспешные внешние обращения выполняются до изменения Entity.
        applyChanges(event, dto, category);
        if (dto.getStateAction() == AdminStateAction.PUBLISH_EVENT) {
            event.setState(EventState.PUBLISHED);
            event.setPublishedOn(now);
        } else if (dto.getStateAction() == AdminStateAction.REJECT_EVENT) {
            event.setState(EventState.CANCELED);
        }
        return fullDto(event, metrics);
    }

    private void applyChanges(Event event, EventUpdateRequest dto, Category category) {
        if (dto.getAnnotation() != null) {
            event.setAnnotation(dto.getAnnotation());
        }
        if (dto.getDescription() != null) {
            event.setDescription(dto.getDescription());
        }
        if (dto.getTitle() != null) {
            event.setTitle(dto.getTitle());
        }
        if (category != null) {
            event.setCategory(category);
        }
        if (dto.getEventDate() != null) {
            event.setEventDate(dto.getEventDate());
        }
        if (dto.getLocation() != null) {
            event.setLocation(eventMapper.toLocation(dto.getLocation()));
        }
        if (dto.getPaid() != null) {
            event.setPaid(dto.getPaid());
        }
        if (dto.getParticipantLimit() != null) {
            event.setParticipantLimit(dto.getParticipantLimit());
        }
        if (dto.getRequestModeration() != null) {
            event.setRequestModeration(dto.getRequestModeration());
        }
    }

    private Event findForUpdate(Long eventId) {
        return eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new NotFoundException("Событие не найдено"));
    }

    private Category findCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new NotFoundException("Категория не найдена"));
    }

    private void requireUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new NotFoundException("Пользователь не найден");
        }
    }

    private void requireOwner(Event event, Long userId) {
        if (!event.getInitiator().getId().equals(userId)) {
            throw new NotFoundException("Событие не найдено или недоступно пользователю");
        }
    }

    private void requireDate(LocalDateTime eventDate, LocalDateTime earliest) {
        if (eventDate.isBefore(earliest)) {
            throw new ConflictException("Дата события должна быть не раньше " + earliest);
        }
    }

    private void requireLimit(Integer limit, long confirmedRequests) {
        if (limit != null && limit < 0) {
            throw new ValidationException("Лимит участников не может быть отрицательным");
        }
        if (limit != null && limit > 0 && limit < confirmedRequests) {
            throw new ConflictException("Лимит меньше количества подтверждённых заявок");
        }
    }

    private void requirePagination(int from, int size) {
        if (from < 0 || size <= 0) {
            throw new ValidationException("from должен быть неотрицательным, size — положительным");
        }
    }

    private EventMetrics loadMetrics(List<Event> events) {
        Set<Long> ids = events.stream().map(Event::getId).collect(Collectors.toSet());
        return metricsService.load(ids);
    }

    private EventFullDto fullDto(Event event, EventMetrics metrics) {
        return eventMapper.toFullDto(event, metrics.confirmed(event.getId()), metrics.views(event.getId()));
    }
}
