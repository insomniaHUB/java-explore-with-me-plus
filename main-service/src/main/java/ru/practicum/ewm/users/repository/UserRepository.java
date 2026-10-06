package ru.practicum.ewm.users.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.practicum.ewm.users.model.User;

import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {
    User findByEmail(String email);

    @Query(value = "SELECT * FROM users WHERE id = ANY(:ids) LIMIT :size OFFSET :from", nativeQuery = true)
    List<User> findByIdInWithOffset(@Param("ids") List<Long> ids, Integer from, Integer size);

    @Query(value = "SELECT * FROM users LIMIT :size OFFSET :from", nativeQuery = true)
    List<User> findAllWithOffset(Integer from, Integer size);
}
