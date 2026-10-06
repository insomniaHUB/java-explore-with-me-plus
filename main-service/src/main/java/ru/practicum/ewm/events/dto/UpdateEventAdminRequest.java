package ru.practicum.ewm.events.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class UpdateEventAdminRequest {
    private String annotation;
    private Long category;
    private String description;
    @JsonFormat(pattern = "uuuu-MM-dd HH:mm:ss", lenient = OptBoolean.FALSE)
    private LocalDateTime eventDate;
    private LocationDto location;
    private Boolean paid;
    private Integer participantLimit;
    private Boolean requestModeration;
    private AdminStateAction stateAction;
    private String title;
}
