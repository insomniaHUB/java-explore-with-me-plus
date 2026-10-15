package ru.practicum.ewm.comments.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.practicum.ewm.comments.dto.CommentDto;
import ru.practicum.ewm.comments.mapper.CommentMapper;
import ru.practicum.ewm.comments.model.Comment;
import ru.practicum.ewm.comments.repository.CommentQueryRepository;
import ru.practicum.ewm.comments.repository.CommentRepository;
import ru.practicum.ewm.events.model.Event;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.exception.ValidationException;
import ru.practicum.ewm.users.model.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCommentServiceTest {
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 14, 12, 0, 0);

    @Mock
    private CommentRepository commentRepository;
    @Mock
    private CommentQueryRepository commentQueryRepository;
    private AdminCommentService service;

    @BeforeEach
    void setUp() {
        service = new AdminCommentService(commentRepository, commentQueryRepository, new CommentMapper());
    }

    @Test
    void findCommentsPassesFiltersAndMapsResult() {
        LocalDateTime start = CREATED.minusDays(1);
        LocalDateTime end = CREATED.plusDays(1);
        when(commentQueryRepository.find(List.of(2L), List.of(10L), start, end, 5, 20))
                .thenReturn(List.of(comment(7L), comment(8L)));

        List<CommentDto> result = service.findComments(List.of(2L), List.of(10L), start, end, 5, 20);

        assertThat(result).extracting(CommentDto::getId).containsExactly(7L, 8L);
        assertThat(result.get(0).getAuthorName()).isEqualTo("Author");
        assertThat(result.get(0).getEventId()).isEqualTo(10L);
        assertThat(result.get(0).getCreated()).isEqualTo(CREATED);
    }

    @Test
    void findCommentsWithoutFiltersIsAllowed() {
        when(commentQueryRepository.find(null, null, null, null, 0, 10)).thenReturn(List.of());

        assertThat(service.findComments(null, null, null, null, 0, 10)).isEmpty();
    }

    @Test
    void findCommentsAcceptsEqualRangeBoundaries() {
        when(commentQueryRepository.find(null, null, CREATED, CREATED, 0, 10)).thenReturn(List.of(comment(7L)));

        assertThat(service.findComments(null, null, CREATED, CREATED, 0, 10)).hasSize(1);
    }

    @Test
    void findCommentsRejectsStartAfterEnd() {
        assertThatThrownBy(() -> service.findComments(null, null, CREATED.plusSeconds(1), CREATED, 0, 10))
                .isInstanceOf(ValidationException.class);
        verifyNoInteractions(commentQueryRepository);
    }

    @Test
    void getCommentReturnsCommentById() {
        when(commentRepository.findById(7L)).thenReturn(Optional.of(comment(7L)));

        assertThat(service.getComment(7L).getText()).isEqualTo("Text of comment 7");
    }

    @Test
    void getCommentReturnsNotFoundForMissingComment() {
        when(commentRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getComment(7L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteCommentRemovesAnyComment() {
        Comment comment = comment(7L);
        when(commentRepository.findById(7L)).thenReturn(Optional.of(comment));

        service.deleteComment(7L);

        verify(commentRepository).delete(comment);
    }

    @Test
    void deleteCommentReturnsNotFoundForMissingComment() {
        when(commentRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteComment(7L)).isInstanceOf(NotFoundException.class);
        verify(commentRepository, never()).delete(any());
    }

    private Comment comment(Long id) {
        return Comment.builder()
                .id(id)
                .text("Text of comment " + id)
                .author(User.builder().id(2L).name("Author").email("author@example.com").build())
                .event(Event.builder().id(10L).build())
                .created(CREATED)
                .build();
    }
}
