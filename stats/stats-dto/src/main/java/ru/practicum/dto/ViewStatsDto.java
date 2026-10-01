package ru.practicum.dto;

public record ViewStatsDto(
        String app,
        String uri,
        Long hits
) {}
