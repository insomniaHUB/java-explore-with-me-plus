package ru.practicum.ewm.comments.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import ru.practicum.ewm.comments.model.Comment;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class CommentQueryRepository {
    private final EntityManager entityManager;

    public List<Comment> find(List<Long> users, List<Long> events,
                              LocalDateTime start, LocalDateTime end, int from, int size) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Comment> query = builder.createQuery(Comment.class);
        Root<Comment> comment = query.from(Comment.class);
        comment.fetch("author");

        List<Predicate> filters = new ArrayList<>();
        if (users != null && !users.isEmpty()) {
            filters.add(comment.get("author").get("id").in(users));
        }
        if (events != null && !events.isEmpty()) {
            filters.add(comment.get("event").get("id").in(events));
        }
        if (start != null) {
            filters.add(builder.greaterThanOrEqualTo(comment.get("created"), start));
        }
        if (end != null) {
            filters.add(builder.lessThanOrEqualTo(comment.get("created"), end));
        }

        query.select(comment)
                .where(filters.toArray(Predicate[]::new))
                .orderBy(builder.desc(comment.get("created")), builder.desc(comment.get("id")));
        return entityManager.createQuery(query)
                .setFirstResult(from)
                .setMaxResults(size)
                .getResultList();
    }
}
