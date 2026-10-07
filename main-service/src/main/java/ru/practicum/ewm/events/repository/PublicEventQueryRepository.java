package ru.practicum.ewm.events.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import ru.practicum.ewm.events.dto.PublicEventSearchRequest;
import ru.practicum.ewm.events.model.Event;
import ru.practicum.ewm.events.model.EventState;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Repository
@RequiredArgsConstructor
public class PublicEventQueryRepository {
    private final EntityManager entityManager;

    public List<Event> findPage(PublicEventSearchRequest request, LocalDateTime now) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Event> query = builder.createQuery(Event.class);
        Root<Event> event = query.from(Event.class);
        event.fetch("category");
        event.fetch("initiator");
        query.select(event).where(filters(request, now, builder, event))
                .orderBy(builder.asc(event.get("eventDate")), builder.asc(event.get("id")));
        return entityManager.createQuery(query).setFirstResult(request.getFrom())
                .setMaxResults(request.getSize()).getResultList();
    }

    public List<PublicEventCandidate> findCandidates(PublicEventSearchRequest request, LocalDateTime now) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<PublicEventCandidate> query = builder.createQuery(PublicEventCandidate.class);
        Root<Event> event = query.from(Event.class);
        query.select(builder.construct(PublicEventCandidate.class,
                        event.get("id"), event.get("eventDate"), event.get("participantLimit")))
                .where(filters(request, now, builder, event))
                .orderBy(builder.asc(event.get("eventDate")), builder.asc(event.get("id")));
        return entityManager.createQuery(query).getResultList();
    }

    private Predicate[] filters(PublicEventSearchRequest request, LocalDateTime now,
                                CriteriaBuilder builder, Root<Event> event) {
        List<Predicate> filters = new ArrayList<>();
        filters.add(builder.equal(event.get("state"), EventState.PUBLISHED));
        if (request.getText() != null) {
            String text = request.getText().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            filters.add(builder.or(builder.like(builder.lower(event.get("annotation")), "%" + text + "%", '\\'),
                    builder.like(builder.lower(event.get("description")), "%" + text + "%", '\\')));
        }
        if (request.getCategories() != null && !request.getCategories().isEmpty()) {
            filters.add(event.get("category").get("id").in(request.getCategories()));
        }
        if (request.getPaid() != null) {
            filters.add(builder.equal(event.get("paid"), request.getPaid()));
        }
        if (request.getRangeStart() == null && request.getRangeEnd() == null) {
            filters.add(builder.greaterThan(event.get("eventDate"), now));
        } else {
            if (request.getRangeStart() != null) {
                filters.add(builder.greaterThanOrEqualTo(event.get("eventDate"), request.getRangeStart()));
            }
            if (request.getRangeEnd() != null) {
                filters.add(builder.lessThanOrEqualTo(event.get("eventDate"), request.getRangeEnd()));
            }
        }
        return filters.toArray(Predicate[]::new);
    }
}
