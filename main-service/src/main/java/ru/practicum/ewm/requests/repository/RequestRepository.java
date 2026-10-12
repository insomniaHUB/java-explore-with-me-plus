package ru.practicum.ewm.requests.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.practicum.ewm.requests.model.ParticipationRequest;
import ru.practicum.ewm.requests.model.RequestStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RequestRepository extends JpaRepository<ParticipationRequest, Long> {

    List<ParticipationRequest> findAllByRequesterIdOrderById(Long requesterId);

    List<ParticipationRequest> findAllByEventIdOrderById(Long eventId);

    List<ParticipationRequest> findAllByEventIdAndIdInOrderById(Long eventId, Collection<Long> ids);

    Optional<ParticipationRequest> findByIdAndRequesterId(Long id, Long requesterId);

    boolean existsByEventIdAndRequesterId(Long eventId, Long requesterId);

    long countByEventIdAndStatus(Long eventId, RequestStatus status);

    @Query("""
            SELECT r.event.id AS eventId, COUNT(r) AS total
            FROM ParticipationRequest r
            WHERE r.event.id IN :eventIds AND r.status = :status
            GROUP BY r.event.id
            """)
    List<EventRequestCount> countByEventIdsAndStatus(@Param("eventIds") Collection<Long> eventIds,
                                                     @Param("status") RequestStatus status);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE ParticipationRequest r
            SET r.status = :newStatus
            WHERE r.event.id = :eventId AND r.status = :oldStatus
            """)
    int updateStatusByEventId(@Param("eventId") Long eventId,
                              @Param("oldStatus") RequestStatus oldStatus,
                              @Param("newStatus") RequestStatus newStatus);
}
