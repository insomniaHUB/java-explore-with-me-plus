package ru.practicum.ewm.events.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.practicum.ewm.events.dto.EventFullDto;
import ru.practicum.ewm.events.dto.EventShortDto;
import ru.practicum.ewm.events.dto.PublicEventSearchRequest;
import ru.practicum.ewm.events.service.PublicEventService;

import java.util.List;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class PublicEventController {
    private final PublicEventService eventService;

    @GetMapping
    public List<EventShortDto> find(@Valid @ModelAttribute PublicEventSearchRequest request,
                                    HttpServletRequest servletRequest) {
        return eventService.find(request, servletRequest.getRemoteAddr());
    }

    @GetMapping("/{id}")
    public EventFullDto get(@PathVariable Long id, HttpServletRequest request) {
        return eventService.get(id, request.getRemoteAddr());
    }
}
