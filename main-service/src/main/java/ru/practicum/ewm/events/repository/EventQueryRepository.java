package ru.practicum.ewm.events.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import ru.practicum.ewm.events.model.Event;
import ru.practicum.ewm.events.model.EventState;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class EventQueryRepository {
    private final EntityManager entityManager;

    public List<Event> find(List<Long> users, List<EventState> states, List<Long> categories,
                            LocalDateTime start, LocalDateTime end, int from, int size) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Event> query = builder.createQuery(Event.class);
        Root<Event> event = query.from(Event.class);
        event.fetch("initiator");
        event.fetch("category");
        List<Predicate> filters = new ArrayList<>();
        if (users != null && !users.isEmpty()) {
            filters.add(event.get("initiator").get("id").in(users));
        }
        if (states != null && !states.isEmpty()) {
            filters.add(event.get("state").in(states));
        }
        if (categories != null && !categories.isEmpty()) {
            filters.add(event.get("category").get("id").in(categories));
        }
        if (start != null) {
            filters.add(builder.greaterThanOrEqualTo(event.get("eventDate"), start));
        }
        if (end != null) {
            filters.add(builder.lessThanOrEqualTo(event.get("eventDate"), end));
        }
        query.select(event).where(filters.toArray(Predicate[]::new)).orderBy(builder.asc(event.get("id")));
        // from — точное смещение, а не номер страницы. Дополнительный COUNT не требуется.
        return entityManager.createQuery(query).setFirstResult(from).setMaxResults(size).getResultList();
    }
}
