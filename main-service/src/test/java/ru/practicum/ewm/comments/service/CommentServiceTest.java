package ru.practicum.ewm.comments.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.practicum.ewm.comments.dto.CommentDto;
import ru.practicum.ewm.comments.dto.UpdateCommentDto;
import ru.practicum.ewm.comments.mapper.CommentMapper;
import ru.practicum.ewm.comments.model.Comment;
import ru.practicum.ewm.comments.repository.CommentRepository;
import ru.practicum.ewm.events.model.Event;
import ru.practicum.ewm.events.repository.EventRepository;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.users.model.User;
import ru.practicum.ewm.users.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 14, 12, 0);

    @Mock
    private CommentRepository commentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private EventRepository eventRepository;
    private CommentService commentService;
    private Comment comment;

    @BeforeEach
    void setUp() {
        commentService = new CommentService(new CommentMapper(), commentRepository, userRepository, eventRepository);
        User author = User.builder().id(1L).name("Author").email("author@example.com").build();
        Event event = Event.builder().id(10L).build();
        comment = Comment.builder()
                .id(5L)
                .text("Old text")
                .author(author)
                .event(event)
                .created(CREATED)
                .build();
    }

    @Test
    void authorUpdatesOnlyCommentText() {
        when(userRepository.existsById(1L)).thenReturn(true);
        when(commentRepository.findByIdAndAuthorId(5L, 1L)).thenReturn(Optional.of(comment));
        when(commentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CommentDto result = commentService.updateComment(
                UpdateCommentDto.builder().text("New text").build(), 1L, 5L);

        assertThat(result.getText()).isEqualTo("New text");
        assertThat(result.getAuthorName()).isEqualTo("Author");
        assertThat(result.getEventId()).isEqualTo(10L);
        assertThat(result.getCreated()).isEqualTo(CREATED);
        verify(commentRepository).save(comment);
    }

    @Test
    void authorDeletesOwnComment() {
        when(userRepository.existsById(1L)).thenReturn(true);
        when(commentRepository.findByIdAndAuthorId(5L, 1L)).thenReturn(Optional.of(comment));

        commentService.deleteComment(1L, 5L);

        verify(commentRepository).delete(comment);
    }

    @Test
    void missingUserCannotUpdateOrDeleteComment() {
        assertThatThrownBy(() -> commentService.updateComment(new UpdateCommentDto("New text"), 1L, 5L))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> commentService.deleteComment(1L, 5L))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(commentRepository);
    }

    @Test
    void anotherUsersCommentIsNotAvailable() {
        when(userRepository.existsById(2L)).thenReturn(true);

        assertThatThrownBy(() -> commentService.updateComment(new UpdateCommentDto("New text"), 2L, 5L))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> commentService.deleteComment(2L, 5L))
                .isInstanceOf(NotFoundException.class);
        verify(commentRepository, times(2)).findByIdAndAuthorId(5L, 2L);
        verify(commentRepository, never()).save(any());
        verify(commentRepository, never()).delete(any());
    }
}
