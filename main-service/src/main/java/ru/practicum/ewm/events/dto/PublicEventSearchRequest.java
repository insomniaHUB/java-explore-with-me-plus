package ru.practicum.ewm.events.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import ru.practicum.dto.StatsDateTimeFormat;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
public class PublicEventSearchRequest {
    @Size(min = 1, max = 7000)
    private String text;

    private List<@NotNull Long> categories;

    private Boolean paid;

    @DateTimeFormat(pattern = StatsDateTimeFormat.PATTERN)
    private LocalDateTime rangeStart;

    @DateTimeFormat(pattern = StatsDateTimeFormat.PATTERN)
    private LocalDateTime rangeEnd;

    private boolean onlyAvailable;

    @NotNull
    private PublicEventSort sort = PublicEventSort.EVENT_DATE;

    @PositiveOrZero
    private int from;

    @Positive
    private int size = 10;

    @AssertTrue(message = "Начало периода должно быть раньше или равно окончанию")
    public boolean isDateRangeValid() {
        return rangeStart == null || rangeEnd == null || !rangeStart.isAfter(rangeEnd);
    }
}
