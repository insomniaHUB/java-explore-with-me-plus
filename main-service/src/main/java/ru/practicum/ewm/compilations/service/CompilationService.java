package ru.practicum.ewm.compilations.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.common.OffsetPageRequest;
import ru.practicum.ewm.compilations.dto.CompilationDto;
import ru.practicum.ewm.compilations.dto.NewCompilationDto;
import ru.practicum.ewm.compilations.dto.UpdateCompilationRequest;
import ru.practicum.ewm.compilations.model.Compilation;
import ru.practicum.ewm.compilations.repository.CompilationRepository;
import ru.practicum.ewm.events.mapper.EventMapper;
import ru.practicum.ewm.events.metrics.EventMetrics;
import ru.practicum.ewm.events.metrics.EventMetricsService;
import ru.practicum.ewm.events.model.Event;
import ru.practicum.ewm.events.repository.EventRepository;
import ru.practicum.ewm.exception.NotFoundException;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompilationService {
    private final CompilationRepository compilationRepository;
    private final EventRepository eventRepository;
    private final EventMapper eventMapper;
    private final EventMetricsService metricsService;

    @Transactional
    public CompilationDto create(NewCompilationDto request) {
        Set<Event> events = findEvents(request.getEvents());
        EventMetrics metrics = loadMetrics(events);
        Compilation compilation = Compilation.builder()
                .title(request.getTitle()).pinned(Boolean.TRUE.equals(request.getPinned())).events(events).build();
        return toDto(compilationRepository.save(compilation), metrics);
    }

    @Transactional
    public CompilationDto update(Long id, UpdateCompilationRequest request) {
        Compilation compilation = findCompilation(id);
        Set<Event> events = request.getEvents() == null ? compilation.getEvents() : findEvents(request.getEvents());
        EventMetrics metrics = loadMetrics(events);
        if (request.getTitle() != null) {
            compilation.setTitle(request.getTitle());
        }
        if (request.getPinned() != null) {
            compilation.setPinned(request.getPinned());
        }
        if (request.getEvents() != null) {
            compilation.setEvents(events);
        }
        return toDto(compilation, metrics);
    }

    @Transactional
    public void delete(Long id) {
        Compilation compilation = compilationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Подборка не найдена"));
        compilationRepository.delete(compilation);
    }

    public CompilationDto get(Long id) {
        Compilation compilation = findCompilation(id);
        return toDto(compilation, loadMetrics(compilation.getEvents()));
    }

    public List<CompilationDto> find(Boolean pinned, int from, int size) {
        List<Long> ids = compilationRepository.findIds(pinned, new OffsetPageRequest(from, size));
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Compilation> compilations = compilationRepository.findWithEventsByIdIn(new LinkedHashSet<>(ids));
        Set<Event> events = compilations.stream().flatMap(compilation -> compilation.getEvents().stream())
                .collect(Collectors.toSet());
        EventMetrics metrics = loadMetrics(events);
        return compilations.stream().map(compilation -> toDto(compilation, metrics)).toList();
    }

    private Compilation findCompilation(Long id) {
        return compilationRepository.findWithEventsById(id)
                .orElseThrow(() -> new NotFoundException("Подборка не найдена"));
    }

    private Set<Event> findEvents(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new LinkedHashSet<>();
        }
        List<Event> events = eventRepository.findByIdIn(ids);
        if (events.size() != ids.size()) {
            throw new NotFoundException("Одно или несколько событий подборки не найдены");
        }
        return events.stream().sorted(Comparator.comparing(Event::getId))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private EventMetrics loadMetrics(Set<Event> events) {
        return metricsService.load(events.stream().map(Event::getId).collect(Collectors.toSet()));
    }

    private CompilationDto toDto(Compilation compilation, EventMetrics metrics) {
        return CompilationDto.builder().id(compilation.getId()).title(compilation.getTitle())
                .pinned(compilation.isPinned())
                .events(compilation.getEvents().stream().sorted(Comparator.comparing(Event::getId))
                        .map(event -> eventMapper.toShortDto(event, metrics.confirmed(event.getId()),
                                metrics.views(event.getId()))).toList())
                .build();
    }
}
