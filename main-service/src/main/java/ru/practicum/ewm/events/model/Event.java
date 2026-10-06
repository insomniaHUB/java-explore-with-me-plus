package ru.practicum.ewm.events.model;

import jakarta.persistence.*;
import lombok.*;
import ru.practicum.ewm.categories.model.Category;
import ru.practicum.ewm.users.model.User;

import java.time.LocalDateTime;

@Entity
@Table(name = "events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Event {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 2000)
    private String annotation;

    @Column(nullable = false, length = 7000)
    private String description;

    @Column(nullable = false, length = 120)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiator_id", nullable = false)
    private User initiator;

    @Embedded
    private Location location;

    @Column(name = "event_date", nullable = false)
    private LocalDateTime eventDate;

    @Column(name = "created_on", nullable = false)
    private LocalDateTime createdOn;

    @Column(name = "published_on")
    private LocalDateTime publishedOn;

    @Column(nullable = false)
    private boolean paid;

    @Column(name = "participant_limit", nullable = false)
    private int participantLimit;

    @Column(name = "request_moderation", nullable = false)
    private boolean requestModeration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventState state;
}
