package ru.practicum.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.OptBoolean;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record EndpointHitDto(
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        Long id,

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
        // uuuu сохраняет формат API и позволяет строго проверять календарную дату без указания эры.
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd HH:mm:ss", lenient = OptBoolean.FALSE)
        LocalDateTime timestamp
) {
    @JsonCreator
    public EndpointHitDto(@JsonProperty("app") String app,
                          @JsonProperty("uri") String uri,
                          @JsonProperty("ip") String ip,
                          @JsonProperty("timestamp") LocalDateTime timestamp) {
        this(null, app, uri, ip, timestamp);
    }
}
