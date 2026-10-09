package ru.practicum.ewm.events.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import ru.practicum.ewm.events.dto.EventFullDto;
import ru.practicum.ewm.events.dto.UpdateEventAdminRequest;
import ru.practicum.ewm.events.model.EventState;
import ru.practicum.ewm.events.service.EventService;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/admin/events")
@RequiredArgsConstructor
public class AdminEventController {
    private final EventService eventService;

    @GetMapping
    public List<EventFullDto> find(@RequestParam(required = false) List<Long> users,
                                   @RequestParam(required = false) List<EventState> states,
                                   @RequestParam(required = false) List<Long> categories,
                                   @RequestParam(required = false)
                                   @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime rangeStart,
                                   @RequestParam(required = false)
                                   @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime rangeEnd,
                                   @RequestParam(defaultValue = "0") @PositiveOrZero int from,
                                   @RequestParam(defaultValue = "10") @Positive int size) {
        return eventService.findAdmin(users, states, categories, rangeStart, rangeEnd, from, size);
    }

    @PatchMapping("/{eventId}")
    public EventFullDto update(@PathVariable Long eventId, @Valid @RequestBody UpdateEventAdminRequest dto) {
        return eventService.updateAdmin(eventId, dto);
    }
}
