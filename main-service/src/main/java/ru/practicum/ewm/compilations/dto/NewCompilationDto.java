package ru.practicum.ewm.compilations.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewCompilationDto {
    @NotBlank
    @Size(max = 50)
    private String title;

    @Builder.Default
    @JsonSetter(nulls = Nulls.SKIP)
    private Boolean pinned = false;

    @Builder.Default
    @JsonSetter(nulls = Nulls.SKIP)
    private Set<@NotNull Long> events = new HashSet<>();
}
