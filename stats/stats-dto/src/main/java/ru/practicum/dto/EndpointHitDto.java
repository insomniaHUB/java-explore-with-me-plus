package ru.practicum.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record EndpointHitDto(
        @NotBlank
        @Size(max = 255)
        String app,

        @NotBlank
        @Size(max = 512)
        String uri,

        @NotBlank
        @Size(max = 45)
        String ip,

        @NotNull
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = StatsDateTimeFormat.PATTERN)
        LocalDateTime timestamp
) {
}
