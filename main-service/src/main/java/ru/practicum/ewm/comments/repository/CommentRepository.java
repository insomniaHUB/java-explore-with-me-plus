package ru.practicum.ewm.comments.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.practicum.ewm.comments.model.Comment;

import java.util.List;
import java.util.Optional;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    Optional<Comment> findByIdAndAuthorId(Long id, Long authorId);

    @EntityGraph(attributePaths = "author")
    List<Comment> findByEventIdOrderByCreatedDescIdDesc(Long eventId, Pageable pageable);
}