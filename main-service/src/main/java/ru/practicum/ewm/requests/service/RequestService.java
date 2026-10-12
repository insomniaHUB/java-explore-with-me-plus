package ru.practicum.ewm.requests.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RequestService {
    private final RequestRepository requestRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final RequestMapper requestMapper;
    private final Clock eventClock;

    @Transactional
    public ParticipationRequestDto create(Long userId, Long eventId) {
        User requester = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("Пользователь с id=" + userId + " не найден"));
        Event event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new NotFoundException("Событие с id=" + eventId + " не найдено"));

        if (event.getInitiator().getId().equals(userId)) {
            throw new ConflictException("Инициатор события не может подать заявку на участие в нём");
        }
        if (event.getState() != EventState.PUBLISHED) {
            throw new ConflictException("Нельзя участвовать в неопубликованном событии");
        }
        if (requestRepository.existsByEventIdAndRequesterId(eventId, userId)) {
            throw new ConflictException("Заявка на участие в этом событии уже подана");
        }
        int limit = event.getParticipantLimit();
        if (limit > 0 && countConfirmed(eventId) >= limit) {
            throw new ConflictException("Достигнут лимит участников события");
        }

        RequestStatus status = !event.isRequestModeration() || limit == 0
                ? RequestStatus.CONFIRMED
                : RequestStatus.PENDING;
        ParticipationRequest request = ParticipationRequest.builder()
                .event(event)
                .requester(requester)
                .status(status)
                .created(now())
                .build();
        return requestMapper.toDto(requestRepository.save(request));
    }

    public List<ParticipationRequestDto> findOwn(Long userId) {
        requireUser(userId);
        return requestMapper.toDtoList(requestRepository.findAllByRequesterIdOrderById(userId));
    }

    @Transactional
    public ParticipationRequestDto cancel(Long userId, Long requestId) {
        requireUser(userId);
        ParticipationRequest request = requestRepository.findByIdAndRequesterId(requestId, userId)
                .orElseThrow(() -> new NotFoundException("Заявка с id=" + requestId + " не найдена"));
        request.setStatus(RequestStatus.CANCELED);
        return requestMapper.toDto(request);
    }

    public List<ParticipationRequestDto> findForEvent(Long userId, Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Событие с id=" + eventId + " не найдено"));
        requireOwner(event, userId);
        return requestMapper.toDtoList(requestRepository.findAllByEventIdOrderById(eventId));
    }

    @Transactional
    public EventRequestStatusUpdateResult updateStatuses(Long userId, Long eventId,
                                                         EventRequestStatusUpdateRequest dto) {
        Event event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new NotFoundException("Событие с id=" + eventId + " не найдено"));
        requireOwner(event, userId);

        List<Long> ids = dto.getRequestIds().stream().distinct().toList();
        List<ParticipationRequest> requests = requestRepository.findAllByEventIdAndIdInOrderById(eventId, ids);
        if (requests.size() != ids.size()) {
            throw new NotFoundException("Не все заявки найдены среди заявок события с id=" + eventId);
        }
        if (requests.stream().anyMatch(request -> request.getStatus() != RequestStatus.PENDING)) {
            throw new ConflictException("Статус можно изменить только у заявок, находящихся в состоянии ожидания");
        }

        List<ParticipationRequest> confirmed = new ArrayList<>();
        List<ParticipationRequest> rejected = new ArrayList<>();
        if (dto.getStatus() == RequestUpdateStatus.REJECTED) {
            requests.forEach(request -> request.setStatus(RequestStatus.REJECTED));
            rejected.addAll(requests);
        } else {
            confirm(event, requests, confirmed, rejected);
        }

        return EventRequestStatusUpdateResult.builder()
                .confirmedRequests(requestMapper.toDtoList(confirmed))
                .rejectedRequests(requestMapper.toDtoList(rejected))
                .build();
    }

    private void confirm(Event event, List<ParticipationRequest> requests,
                         List<ParticipationRequest> confirmed, List<ParticipationRequest> rejected) {
        int limit = event.getParticipantLimit();
        long confirmedCount = countConfirmed(event.getId());
        if (limit > 0 && confirmedCount >= limit) {
            throw new ConflictException("Достигнут лимит участников события");
        }

        for (ParticipationRequest request : requests) {
            if (limit == 0 || confirmedCount < limit) {
                request.setStatus(RequestStatus.CONFIRMED);
                confirmed.add(request);
                confirmedCount++;
            } else {
                request.setStatus(RequestStatus.REJECTED);
                rejected.add(request);
            }
        }

        if (limit > 0 && confirmedCount >= limit) {
            requestRepository.updateStatusByEventId(event.getId(), RequestStatus.PENDING, RequestStatus.REJECTED);
        }
    }

    private long countConfirmed(Long eventId) {
        return requestRepository.countByEventIdAndStatus(eventId, RequestStatus.CONFIRMED);
    }

    private void requireUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new NotFoundException("Пользователь с id=" + userId + " не найден");
        }
    }

    private void requireOwner(Event event, Long userId) {
        if (!event.getInitiator().getId().equals(userId)) {
            throw new NotFoundException("Событие с id=" + event.getId() + " не найдено или недоступно пользователю");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(eventClock).truncatedTo(ChronoUnit.MILLIS);
    }
}
