package ru.practicum.ewm.comments.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.practicum.ewm.comments.model.Comment;

public interface CommentRepository extends JpaRepository<Comment, Long> {
}
