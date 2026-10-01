package ru.practicum.dto;

import java.time.format.DateTimeFormatter;

public final class StatsDateTimeFormat {

    public static final String PATTERN = "yyyy-MM-dd HH:mm:ss";

    public static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern(PATTERN);

    private StatsDateTimeFormat() {
    }
}
