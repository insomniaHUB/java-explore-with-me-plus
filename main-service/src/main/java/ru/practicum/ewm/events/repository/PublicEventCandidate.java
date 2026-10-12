package ru.practicum.ewm.events.repository;

import java.time.LocalDateTime;

public record PublicEventCandidate(Long id, LocalDateTime eventDate, int participantLimit) {
}
