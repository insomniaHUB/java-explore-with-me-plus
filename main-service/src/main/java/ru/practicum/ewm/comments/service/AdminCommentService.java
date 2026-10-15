package ru.practicum.ewm.comments.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.comments.dto.CommentDto;
import ru.practicum.ewm.comments.mapper.CommentMapper;
import ru.practicum.ewm.comments.model.Comment;
import ru.practicum.ewm.comments.repository.CommentQueryRepository;
import ru.practicum.ewm.comments.repository.CommentRepository;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.exception.ValidationException;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminCommentService {
    private final CommentRepository commentRepository;
    private final CommentQueryRepository commentQueryRepository;
    private final CommentMapper commentMapper;

    public List<CommentDto> findComments(List<Long> users, List<Long> events,
                                         LocalDateTime rangeStart, LocalDateTime rangeEnd, int from, int size) {
        if (rangeStart != null && rangeEnd != null && rangeStart.isAfter(rangeEnd)) {
            throw new ValidationException("Начало диапазона не может быть позже его окончания");
        }
        return commentQueryRepository.find(users, events, rangeStart, rangeEnd, from, size).stream()
                .map(commentMapper::toCommentDto)
                .toList();
    }

    public CommentDto getComment(Long commentId) {
        return commentMapper.toCommentDto(findComment(commentId));
    }

    @Transactional
    public void deleteComment(Long commentId) {
        commentRepository.delete(findComment(commentId));
    }

    private Comment findComment(Long commentId) {
        return commentRepository.findById(commentId)
                .orElseThrow(() -> new NotFoundException("Комментарий с id=" + commentId + " не найден"));
    }
}
