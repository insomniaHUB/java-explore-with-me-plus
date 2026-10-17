package ru.practicum.ewm.comments.mapper;

import org.springframework.stereotype.Component;
import ru.practicum.ewm.comments.dto.CommentDto;
import ru.practicum.ewm.comments.dto.NewCommentDto;
import ru.practicum.ewm.comments.model.Comment;
import ru.practicum.ewm.events.model.Event;
import ru.practicum.ewm.users.model.User;

import java.time.LocalDateTime;

@Component
public class CommentMapper {
    public CommentDto toCommentDto(Comment comment) {
        return CommentDto.builder()
                .id(comment.getId())
                .text(comment.getText())
                .authorName(comment.getAuthor().getName())
                .eventId(comment.getEvent().getId())
                .created(comment.getCreated())
                .build();
    }

    public Comment toComment(NewCommentDto dto, User author, Event event, LocalDateTime now) {
        return Comment.builder()
                .text(dto.getText())
                .author(author)
                .event(event)
                .created(now)
                .build();
    }
}
