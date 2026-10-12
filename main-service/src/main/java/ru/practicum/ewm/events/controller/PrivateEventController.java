package ru.practicum.ewm.events.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.practicum.ewm.events.dto.EventFullDto;
import ru.practicum.ewm.events.dto.EventShortDto;
import ru.practicum.ewm.events.dto.NewEventDto;
import ru.practicum.ewm.events.dto.UpdateEventUserRequest;
import ru.practicum.ewm.events.service.EventService;

import java.util.List;

@RestController
@RequestMapping("/users/{userId}/events")
@RequiredArgsConstructor
public class PrivateEventController {
    private final EventService eventService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventFullDto create(@PathVariable Long userId, @Valid @RequestBody NewEventDto dto) {
        return eventService.create(userId, dto);
    }

    @GetMapping
    public List<EventShortDto> findOwn(@PathVariable Long userId,
                                      @RequestParam(defaultValue = "0") @PositiveOrZero int from,
                                      @RequestParam(defaultValue = "10") @Positive int size) {
        return eventService.findOwn(userId, from, size);
    }

    @GetMapping("/{eventId}")
    public EventFullDto getOwn(@PathVariable Long userId, @PathVariable Long eventId) {
        return eventService.getOwn(userId, eventId);
    }

    @PatchMapping("/{eventId}")
    public EventFullDto updateOwn(@PathVariable Long userId, @PathVariable Long eventId,
                                 @Valid @RequestBody UpdateEventUserRequest dto) {
        return eventService.updateOwn(userId, eventId, dto);
    }
}
