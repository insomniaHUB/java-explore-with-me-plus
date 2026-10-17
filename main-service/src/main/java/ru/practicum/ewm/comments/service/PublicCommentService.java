package ru.practicum.ewm.comments.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.comments.dto.CommentDto;
import ru.practicum.ewm.comments.mapper.CommentMapper;
import ru.practicum.ewm.comments.repository.CommentRepository;
import ru.practicum.ewm.common.OffsetPageRequest;
import ru.practicum.ewm.events.model.EventState;
import ru.practicum.ewm.events.repository.EventRepository;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.exception.ValidationException;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicCommentService {
    private final CommentRepository commentRepository;
    private final EventRepository eventRepository;
    private final CommentMapper commentMapper;

    public List<CommentDto> findForEvent(Long eventId, int from, int size) {
        if (from < 0 || size <= 0) {
            throw new ValidationException("from должен быть неотрицательным, size — положительным");
        }
        if (!eventRepository.existsByIdAndState(eventId, EventState.PUBLISHED)) {
            throw new NotFoundException("Опубликованное событие не найдено");
        }
        return commentRepository.findByEventIdOrderByCreatedDescIdDesc(eventId, new OffsetPageRequest(from, size))
                .stream()
                .map(commentMapper::toCommentDto)
                .toList();
    }
}
