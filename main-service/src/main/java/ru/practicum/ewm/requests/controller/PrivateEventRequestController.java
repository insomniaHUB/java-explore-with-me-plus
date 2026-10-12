package ru.practicum.ewm.requests.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.ewm.requests.dto.EventRequestStatusUpdateRequest;
import ru.practicum.ewm.requests.dto.EventRequestStatusUpdateResult;
import ru.practicum.ewm.requests.dto.ParticipationRequestDto;
import ru.practicum.ewm.requests.service.RequestService;

import java.util.List;

@RestController
@RequestMapping("/users/{userId}/events/{eventId}/requests")
@RequiredArgsConstructor
public class PrivateEventRequestController {
    private final RequestService requestService;

    @GetMapping
    public List<ParticipationRequestDto> findForEvent(@PathVariable Long userId, @PathVariable Long eventId) {
        return requestService.findForEvent(userId, eventId);
    }

    @PatchMapping
    public EventRequestStatusUpdateResult updateStatuses(@PathVariable Long userId, @PathVariable Long eventId,
                                                         @Valid @RequestBody EventRequestStatusUpdateRequest dto) {
        return requestService.updateStatuses(userId, eventId, dto);
    }
}
