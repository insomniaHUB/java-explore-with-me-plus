package ru.practicum.ewm.compilations.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.practicum.ewm.compilations.model.Compilation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface CompilationRepository extends JpaRepository<Compilation, Long> {
    @Query("SELECT c.id FROM Compilation c WHERE :pinned IS NULL OR c.pinned = :pinned ORDER BY c.id")
    List<Long> findIds(@Param("pinned") Boolean pinned, Pageable pageable);

    // Коллекцию загружаем после выбора страницы ID: LIMIT над JOIN FETCH коллекции некорректен.
    @Query("SELECT DISTINCT c FROM Compilation c LEFT JOIN FETCH c.events e "
            + "LEFT JOIN FETCH e.category LEFT JOIN FETCH e.initiator WHERE c.id IN :ids ORDER BY c.id")
    List<Compilation> findWithEventsByIdIn(@Param("ids") Set<Long> ids);

    @Query("SELECT c FROM Compilation c LEFT JOIN FETCH c.events e "
            + "LEFT JOIN FETCH e.category LEFT JOIN FETCH e.initiator WHERE c.id = :id")
    Optional<Compilation> findWithEventsById(@Param("id") Long id);
}
